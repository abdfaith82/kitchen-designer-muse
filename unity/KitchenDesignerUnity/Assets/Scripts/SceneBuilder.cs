// SceneBuilder.cs
// Builds the kitchen scene from a LoadProject command: floor, 4 walls and
// one box per placed unit. Base units also get a countertop slab whose
// front edge follows the chosen CountertopEdgeProfile (see
// BuildCountertop — a documented geometry approximation, not a lathed
// profile mesh).
//
// Coordinate mapping (Kotlin model -> Unity):
//   Kotlin: millimetres, origin at the room's south-west floor corner,
//           x = east (room width), y = north (room depth).
//   Unity:  metres, x = east, z = north, y = up.  => divide mm by 1000.
// A unit's position in the JSON is its south-west floor corner, so the box
// centre is placed at
//   x = (xMm + widthMm  / 2) / 1000
//   z = (yMm + depthMm  / 2) / 1000
// and rotated around Y by rotationDeg.
//
// IMPORTANT (v1 limitation): the v1 JSON does NOT carry per-unit
// width/height/depth — only catalogId. Sizes are therefore derived here
// from the catalogId itself:
//   * width  = trailing number of the id ("base-600" -> 600 mm, default 600)
//   * height/depth/mounting = the built-in table below, keyed by the id
//     prefix before the first '-' ("base" / "wall" / "tall").
// In v2 the Kotlin catalog (CabinetUnit.widthMm/heightMm/depthMm) should be
// added to the JSON and this table deleted — the Kotlin side is the source
// of truth for dimensions.

using System.Collections.Generic;
using UnityEngine;

namespace KitchenDesigner
{
    /// <summary>Tags a built unit with its index in the LoadProject units array.</summary>
    public class UnitIndexMarker : MonoBehaviour
    {
        public int Index;
    }

    /// <summary>
    /// Countertop front-edge profile. SQUARE is a plain slab; BULLNOSE and
    /// BEVEL are approximated with inset + primitive geometry (see
    /// SceneBuilder.BuildCountertop).
    /// </summary>
    public enum CountertopEdgeProfile
    {
        Square,
        Bullnose,
        Bevel,
    }

    public class SceneBuilder : MonoBehaviour
    {
        [Header("Countertop")]
        [SerializeField] private CountertopEdgeProfile countertopEdgeProfile = CountertopEdgeProfile.Square;
        [SerializeField] private string countertopMaterialId = "counter-quartz";

        // Countertop slab dimensions (metres): 40 mm slab, overhanging the
        // cabinet box 20 mm at the front, 10 mm at the back and sides.
        private const float CountertopThicknessM = 0.04f;
        private const float CountertopFrontOverhangM = 0.02f;
        private const float CountertopBackOverhangM = 0.01f;
        private const float CountertopSideOverhangM = 0.01f;
        /// <summary>Horizontal (and vertical) leg of the BEVEL chamfer.</summary>
        private const float CountertopBevelM = 0.012f;

        /// <summary>The edge profile the next Build() will use.</summary>
        public CountertopEdgeProfile EdgeProfile => countertopEdgeProfile;

        /// <summary>
        /// Choose the countertop edge profile by wire name
        /// ("SQUARE" | "BULLNOSE" | "BEVEL", any case). Returns false for an
        /// unknown name (current profile kept). Applies on the next Build().
        /// </summary>
        public bool SetCountertopEdgeProfile(string profileName)
        {
            if (System.Enum.TryParse(profileName ?? string.Empty, ignoreCase: true,
                    out CountertopEdgeProfile parsed))
            {
                countertopEdgeProfile = parsed;
                return true;
            }
            return false;
        }

        /// <summary>Set the countertop edge profile directly. Applies on the next Build().</summary>
        public void SetCountertopEdgeProfile(CountertopEdgeProfile profile)
        {
            countertopEdgeProfile = profile;
        }

        /// <summary>Material id (see MaterialLibrary) for countertop slabs.</summary>
        public void SetCountertopMaterial(string materialId)
        {
            if (!string.IsNullOrEmpty(materialId)) countertopMaterialId = materialId;
        }

        /// <summary>Built-in v1 size table, keyed by catalogId prefix.</summary>
        private struct SizeSpec
        {
            public float HeightM;   // box height
            public float DepthM;    // footprint depth (front-to-back)
            public float BaseYM;    // height of the box's bottom above the floor
        }

        private static readonly Dictionary<string, SizeSpec> SizeTable = new Dictionary<string, SizeSpec>
        {
            // Base cabinets: 860 mm tall on the floor, 560 mm deep.
            { "base", new SizeSpec { HeightM = 0.86f, DepthM = 0.56f, BaseYM = 0f } },
            // Wall cabinets: 720 mm tall, 320 mm deep, mounted with the
            // bottom 1450 mm above the floor.
            { "wall", new SizeSpec { HeightM = 0.72f, DepthM = 0.32f, BaseYM = 1.45f } },
            // Tall units (larder / oven housing / fridge): 2100 mm, 600 mm deep.
            { "tall", new SizeSpec { HeightM = 2.10f, DepthM = 0.60f, BaseYM = 0f } },
        };

        private const float WallThicknessM = 0.05f;

        private GameObject roomRoot;
        private readonly List<GameObject> unitRoots = new List<GameObject>();
        private readonly List<Renderer> unitRenderers = new List<Renderer>();

        /// <summary>Number of units in the currently built scene.</summary>
        public int UnitCount => unitRoots.Count;

        /// <summary>Clear any previous scene and build the room + units from <paramref name="cmd"/>.</summary>
        public void Build(KitchenBridge.LoadProjectCmd cmd)
        {
            Clear();

            roomRoot = new GameObject("Room");
            roomRoot.transform.SetParent(transform, false);

            float widthM = cmd.room.widthMm / 1000f;
            float depthM = cmd.room.depthMm / 1000f;
            float heightM = Mathf.Max(cmd.room.ceilingHeightMm, 1000) / 1000f;

            BuildFloor(widthM, depthM);
            BuildWalls(widthM, depthM, heightM);

            if (cmd.units != null)
            {
                for (int i = 0; i < cmd.units.Length; i++)
                    BuildUnit(i, cmd.units[i]);
            }
        }

        /// <summary>Remove the previously built room and units, if any.</summary>
        public void Clear()
        {
            if (roomRoot != null)
            {
                Destroy(roomRoot);
                roomRoot = null;
            }
            unitRoots.Clear();
            unitRenderers.Clear();
        }

        /// <summary>
        /// Swap the material of the unit at <paramref name="unitIndex"/>.
        /// Returns false when the index is out of range.
        /// </summary>
        public bool SetUnitMaterial(int unitIndex, string materialId)
        {
            if (unitIndex < 0 || unitIndex >= unitRenderers.Count) return false;
            var renderer = unitRenderers[unitIndex];
            if (renderer != null) renderer.sharedMaterial = MaterialLibrary.Get(materialId);
            return true;
        }

        // ------------------------------------------------------------------

        private void BuildFloor(float widthM, float depthM)
        {
            var floor = GameObject.CreatePrimitive(PrimitiveType.Plane);
            floor.name = "Floor";
            floor.transform.SetParent(roomRoot.transform, false);
            // Unity's Plane primitive is 10x10 m, so scale to the room size.
            floor.transform.localScale = new Vector3(widthM / 10f, 1f, depthM / 10f);
            floor.transform.position = new Vector3(widthM / 2f, 0f, depthM / 2f);
            var renderer = floor.GetComponent<Renderer>();
            if (renderer != null)
            {
                renderer.sharedMaterial = MaterialLibrary.Get("floor-porcelain");
                // World-unit tiling: tiles stay 0.6 m whatever the room size.
                MaterialLibrary.ApplyWorldTiling(renderer, new Vector2(widthM, depthM));
            }
        }

        private void BuildWalls(float widthM, float depthM, float heightM)
        {
            var wallMaterial = MaterialLibrary.Get("default");
            // South (z = 0) and North (z = depth) walls run along x.
            MakeWall("WallSouth", new Vector3(widthM / 2f, heightM / 2f, -WallThicknessM / 2f),
                     new Vector3(widthM, heightM, WallThicknessM), wallMaterial);
            MakeWall("WallNorth", new Vector3(widthM / 2f, heightM / 2f, depthM + WallThicknessM / 2f),
                     new Vector3(widthM, heightM, WallThicknessM), wallMaterial);
            // West (x = 0) and East (x = width) walls run along z.
            MakeWall("WallWest", new Vector3(-WallThicknessM / 2f, heightM / 2f, depthM / 2f),
                     new Vector3(WallThicknessM, heightM, depthM), wallMaterial);
            MakeWall("WallEast", new Vector3(widthM + WallThicknessM / 2f, heightM / 2f, depthM / 2f),
                     new Vector3(WallThicknessM, heightM, depthM), wallMaterial);
        }

        private void MakeWall(string name, Vector3 center, Vector3 size, Material material)
        {
            var wall = GameObject.CreatePrimitive(PrimitiveType.Cube);
            wall.name = name;
            wall.transform.SetParent(roomRoot.transform, false);
            wall.transform.position = center;
            wall.transform.localScale = size;
            var renderer = wall.GetComponent<Renderer>();
            if (renderer != null) renderer.sharedMaterial = material;
        }

        private void BuildUnit(int index, KitchenBridge.UnitDto dto)
        {
            SizeSpec spec = SpecFor(dto.catalogId);
            float widthM = WidthMmFor(dto.catalogId) / 1000f;

            var unit = GameObject.CreatePrimitive(PrimitiveType.Cube);
            unit.name = $"Unit_{index}_{dto.catalogId}";
            unit.transform.SetParent(roomRoot.transform, false);
            unit.transform.localScale = new Vector3(widthM, spec.HeightM, spec.DepthM);
            unit.transform.position = new Vector3(
                (dto.xMm + WidthMmFor(dto.catalogId) / 2f) / 1000f,
                spec.BaseYM + spec.HeightM / 2f,
                (dto.yMm + spec.DepthM * 1000f / 2f) / 1000f);
            unit.transform.rotation = Quaternion.Euler(0f, dto.rotationDeg, 0f);

            var marker = unit.AddComponent<UnitIndexMarker>();
            marker.Index = index;

            var renderer = unit.GetComponent<Renderer>();
            if (renderer != null)
                renderer.sharedMaterial = MaterialLibrary.Get(dto.materialId);

            unitRoots.Add(unit);
            unitRenderers.Add(renderer);

            // Only base cabinets carry a worktop.
            string prefix = PrefixFor(dto.catalogId);
            if (prefix == "base")
            {
                BuildCountertop(index, spec, widthM,
                    unit.transform.position, unit.transform.rotation);
            }
        }

        /// <summary>
        /// Build the countertop slab for a base unit, with the front edge
        /// shaped by <see cref="countertopEdgeProfile"/>.
        ///
        /// APPROXIMATION (documented, deliberate): there is no lathed or
        /// extruded profile mesh. The slab is a box whose front is inset by
        /// the profile's depth, plus one primitive filling the inset:
        ///   SQUARE   — no inset, no filler: the plain box slab.
        ///   BEVEL    — front inset by CountertopBevelM; a thin box strip
        ///              rotated -45° about X bridges the top-front corner,
        ///              reading as a 45° chamfer with equal legs.
        ///   BULLNOSE — front inset by the slab radius (thickness/2); a
        ///              half-cylinder (radius = thickness/2, axis along the
        ///              slab width) sits in the inset, its embedded half
        ///              hidden inside the slab, its round half forming the
        ///              nose. Total front extent matches the SQUARE slab.
        /// "Front" is the unit's local -Z (before its Y rotation), matching
        /// how units face into the room in the v1 placement convention.
        /// All pieces are parented to the room root, so Clear() removes them
        /// with the room.
        /// </summary>
        private void BuildCountertop(int index, SizeSpec spec, float widthM,
                                     Vector3 unitCenter, Quaternion unitRotation)
        {
            float slabW = widthM + 2f * CountertopSideOverhangM;
            float slabT = CountertopThicknessM;
            float frontFull = -spec.DepthM / 2f - CountertopFrontOverhangM; // local z of the finished front face
            float back = spec.DepthM / 2f + CountertopBackOverhangM;         // local z of the back face
            float topLocalY = spec.HeightM / 2f + slabT / 2f;                // slab centre, relative to unit centre

            float inset = 0f;
            if (countertopEdgeProfile == CountertopEdgeProfile.Bevel) inset = CountertopBevelM;
            else if (countertopEdgeProfile == CountertopEdgeProfile.Bullnose) inset = slabT / 2f;

            float mainFront = frontFull + inset;
            float mainDepth = back - mainFront;
            var slabLocalPos = new Vector3(0f, topLocalY, (mainFront + back) / 2f);
            MakeCountertopPiece($"Countertop_{index}", PrimitiveType.Cube,
                unitCenter, unitRotation, slabLocalPos, Quaternion.identity,
                new Vector3(slabW, slabT, mainDepth));

            if (countertopEdgeProfile == CountertopEdgeProfile.Bevel)
            {
                float diag = CountertopBevelM * 1.4142f; // strip cross-section so its face spans the chamfer
                var stripLocalPos = new Vector3(0f,
                    spec.HeightM / 2f + slabT - CountertopBevelM / 2f,
                    (mainFront + frontFull) / 2f);
                MakeCountertopPiece($"CountertopEdge_{index}", PrimitiveType.Cube,
                    unitCenter, unitRotation, stripLocalPos,
                    Quaternion.Euler(-45f, 0f, 0f),
                    new Vector3(slabW, diag, diag));
            }
            else if (countertopEdgeProfile == CountertopEdgeProfile.Bullnose)
            {
                float r = slabT / 2f;
                var noseLocalPos = new Vector3(0f, topLocalY, mainFront);
                // Cylinder primitive: height 2 along Y, diameter 1. Rotated
                // 90° about Z its axis lies along X (the slab width); the
                // scale is in pre-rotation local space.
                MakeCountertopPiece($"CountertopEdge_{index}", PrimitiveType.Cylinder,
                    unitCenter, unitRotation, noseLocalPos,
                    Quaternion.Euler(0f, 0f, 90f),
                    new Vector3(2f * r, slabW / 2f, 2f * r));
            }
        }

        private void MakeCountertopPiece(string name, PrimitiveType type,
                                         Vector3 unitCenter, Quaternion unitRotation,
                                         Vector3 localPos, Quaternion localRotation,
                                         Vector3 localScale)
        {
            var piece = GameObject.CreatePrimitive(type);
            piece.name = name;
            piece.transform.SetParent(roomRoot.transform, false);
            piece.transform.position = unitCenter + unitRotation * localPos;
            piece.transform.rotation = unitRotation * localRotation;
            piece.transform.localScale = localScale;
            var renderer = piece.GetComponent<Renderer>();
            if (renderer != null) renderer.sharedMaterial = MaterialLibrary.Get(countertopMaterialId);
        }

        /// <summary>The catalogId prefix before the first '-' ("base-600" -> "base").</summary>
        private static string PrefixFor(string catalogId)
        {
            if (string.IsNullOrEmpty(catalogId)) return string.Empty;
            int dash = catalogId.IndexOf('-');
            return dash >= 0 ? catalogId.Substring(0, dash) : catalogId;
        }

        /// <summary>Size spec for a catalogId, from the prefix before the first '-'.</summary>
        private static SizeSpec SpecFor(string catalogId)
        {
            if (!string.IsNullOrEmpty(catalogId))
            {
                int dash = catalogId.IndexOf('-');
                string prefix = dash >= 0 ? catalogId.Substring(0, dash) : catalogId;
                if (SizeTable.TryGetValue(prefix, out SizeSpec spec)) return spec;
            }
            return SizeTable["base"]; // unknown ids are drawn as base cabinets
        }

        /// <summary>Width in mm from the trailing number of the catalogId ("base-600" -> 600); default 600.</summary>
        private static int WidthMmFor(string catalogId)
        {
            if (!string.IsNullOrEmpty(catalogId))
            {
                int dash = catalogId.LastIndexOf('-');
                if (dash >= 0 && int.TryParse(catalogId.Substring(dash + 1), out int w) && w > 0)
                    return w;
            }
            return 600;
        }
    }
}
