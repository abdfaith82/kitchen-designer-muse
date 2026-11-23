// MaterialLibrary.cs
// Creates and caches full-PBR materials by materialId.
//
// Materials are built in code (no .mat assets to keep in sync) using the
// URP Lit shader when available, falling back to the Built-in Standard
// shader so the same code also runs in a non-URP project.
//
// Every material is described by a complete PbrMaterialSpec:
//   * BaseColor            — linear RGB albedo.
//   * Roughness            — 0..1 PBR roughness (converted to the shader's
//                            Smoothness = 1 - Roughness when applied).
//   * Metallic             — 0..1.
//   * NormalScale          — strength of the normal map (_BumpScale), ready
//                            for when texture maps are bound.
//   * TextureRepeatWorldSize — TEXTURE TILING IN WORLD UNITS: the size, in
//                            metres, that one texture repeat covers on a
//                            surface (e.g. a 0.6 m floor tile, a 1.2 m wood
//                            plank). ComputeTextureScale() turns a surface's
//                            world size into the UV repeat count, so a
//                            texture looks the same physical size on a 2 m
//                            and a 6 m wall. Unity primitive UVs run 0..1
//                            across each face regardless of world size, so
//                            the scale must be set per surface size — see
//                            ApplyWorldTiling().
//   * Pattern*             — secondary-colour pattern parameters (wood
//                            grain, granite speckle, quartz fleck): the
//                            colour, world-space scale and strength a
//                            procedural texture generator consumes.
//   * MarbleVeining        — marble only: vein colour / intensity / scale /
//                            turbulence for the veining generator.
//
// HONEST SCOPE: no texture maps are bound yet (the asset pipeline is a
// later step), so today only BaseColor / Metallic / Roughness reach the
// shader; NormalScale, tiling and the pattern/veining parameters are the
// authored data those maps will be generated and bound from, kept here so
// materials do not need re-authoring when the pipeline lands.

using System.Collections.Generic;
using UnityEngine;

namespace KitchenDesigner
{
    /// <summary>Veining parameters for marble materials (procedural).</summary>
    public struct MarbleVeining
    {
        public Color VeinColor;    // colour of the veins
        public float Intensity;    // 0..1 vein contrast/coverage
        public float Scale;        // vein features per metre (world space)
        public float Turbulence;   // 0..1 how wavy/broken the veins are
    }

    /// <summary>Complete PBR parameter set for one material.</summary>
    public struct PbrMaterialSpec
    {
        public Color BaseColor;
        public float Roughness;              // 0..1
        public float Metallic;               // 0..1
        public float NormalScale;            // normal-map strength
        public Vector2 TextureRepeatWorldSize; // metres per texture repeat (x, y)
        public Color PatternColor;           // grain / speckle secondary colour
        public float PatternScale;           // pattern features per metre
        public float PatternStrength;        // 0..1 pattern blend
        public MarbleVeining Veining;        // marble only; Intensity 0 = unused
    }

    public static class MaterialLibrary
    {
        private static readonly Dictionary<string, PbrMaterialSpec> Specs = new Dictionary<string, PbrMaterialSpec>
        {
            // ---- Cabinet fronts -------------------------------------------
            { "front-oak", new PbrMaterialSpec {
                BaseColor = new Color(0.62f, 0.44f, 0.28f), Roughness = 0.65f, Metallic = 0.00f,
                NormalScale = 0.40f, TextureRepeatWorldSize = new Vector2(0.60f, 0.60f),
                PatternColor = new Color(0.45f, 0.30f, 0.17f), PatternScale = 6f, PatternStrength = 0.35f } },
            { "front-acrylic-white", new PbrMaterialSpec {
                BaseColor = new Color(0.95f, 0.95f, 0.96f), Roughness = 0.20f, Metallic = 0.00f,
                NormalScale = 0.00f, TextureRepeatWorldSize = new Vector2(1.00f, 1.00f),
                PatternColor = new Color(0.95f, 0.95f, 0.96f), PatternScale = 1f, PatternStrength = 0.00f } },
            { "front-hpl-grey", new PbrMaterialSpec {
                BaseColor = new Color(0.45f, 0.46f, 0.48f), Roughness = 0.50f, Metallic = 0.00f,
                NormalScale = 0.15f, TextureRepeatWorldSize = new Vector2(1.00f, 1.00f),
                PatternColor = new Color(0.40f, 0.41f, 0.43f), PatternScale = 3f, PatternStrength = 0.15f } },

            // ---- Worktops ---------------------------------------------------
            { "counter-quartz", new PbrMaterialSpec {
                BaseColor = new Color(0.90f, 0.89f, 0.87f), Roughness = 0.30f, Metallic = 0.00f,
                NormalScale = 0.10f, TextureRepeatWorldSize = new Vector2(1.50f, 1.50f),
                PatternColor = new Color(0.78f, 0.76f, 0.73f), PatternScale = 40f, PatternStrength = 0.15f } },
            // Granite: dark polished stone; the slight metallic reads as mica
            // sparkle once speckle maps are bound (granite itself is dielectric).
            { "counter-granite", new PbrMaterialSpec {
                BaseColor = new Color(0.25f, 0.24f, 0.26f), Roughness = 0.35f, Metallic = 0.05f,
                NormalScale = 0.35f, TextureRepeatWorldSize = new Vector2(1.20f, 1.20f),
                PatternColor = new Color(0.75f, 0.70f, 0.60f), PatternScale = 60f, PatternStrength = 0.50f } },
            { "counter-laminate", new PbrMaterialSpec {
                BaseColor = new Color(0.76f, 0.68f, 0.55f), Roughness = 0.60f, Metallic = 0.00f,
                NormalScale = 0.30f, TextureRepeatWorldSize = new Vector2(0.80f, 0.80f),
                PatternColor = new Color(0.62f, 0.52f, 0.40f), PatternScale = 6f, PatternStrength = 0.30f } },
            // Marble (Carrara-style): bright polished stone, grey veining.
            { "counter-marble", new PbrMaterialSpec {
                BaseColor = new Color(0.93f, 0.92f, 0.90f), Roughness = 0.22f, Metallic = 0.00f,
                NormalScale = 0.15f, TextureRepeatWorldSize = new Vector2(1.50f, 1.50f),
                PatternColor = new Color(0.85f, 0.84f, 0.82f), PatternScale = 20f, PatternStrength = 0.10f,
                Veining = new MarbleVeining {
                    VeinColor = new Color(0.52f, 0.53f, 0.57f), Intensity = 0.80f,
                    Scale = 1.2f, Turbulence = 0.60f } } },

            // ---- Metals -----------------------------------------------------
            // Brushed steel: full metal, mid roughness. True anisotropic
            // highlights need a physical/aniso shader; on URP Lit the fine
            // NormalScale stands in for the brushed grain.
            { "steel-brushed", new PbrMaterialSpec {
                BaseColor = new Color(0.72f, 0.73f, 0.75f), Roughness = 0.38f, Metallic = 1.00f,
                NormalScale = 0.25f, TextureRepeatWorldSize = new Vector2(0.50f, 0.50f),
                PatternColor = new Color(0.66f, 0.67f, 0.69f), PatternScale = 120f, PatternStrength = 0.20f } },

            // ---- Woods (solid / worktop / furniture use) --------------------
            { "wood-oak", new PbrMaterialSpec {
                BaseColor = new Color(0.60f, 0.42f, 0.25f), Roughness = 0.58f, Metallic = 0.00f,
                NormalScale = 0.50f, TextureRepeatWorldSize = new Vector2(0.80f, 0.80f),
                PatternColor = new Color(0.42f, 0.27f, 0.14f), PatternScale = 5f, PatternStrength = 0.45f } },
            { "wood-walnut", new PbrMaterialSpec {
                BaseColor = new Color(0.32f, 0.20f, 0.13f), Roughness = 0.52f, Metallic = 0.00f,
                NormalScale = 0.50f, TextureRepeatWorldSize = new Vector2(0.80f, 0.80f),
                PatternColor = new Color(0.18f, 0.10f, 0.06f), PatternScale = 5f, PatternStrength = 0.50f } },

            // ---- Ceramics / floors ------------------------------------------
            // Matte ceramic: near-lambertian, subtle surface bump, 30 cm tiles.
            { "ceramic-matte", new PbrMaterialSpec {
                BaseColor = new Color(0.88f, 0.86f, 0.82f), Roughness = 0.85f, Metallic = 0.00f,
                NormalScale = 0.30f, TextureRepeatWorldSize = new Vector2(0.30f, 0.30f),
                PatternColor = new Color(0.82f, 0.80f, 0.76f), PatternScale = 8f, PatternStrength = 0.10f } },
            { "floor-porcelain", new PbrMaterialSpec {
                BaseColor = new Color(0.82f, 0.81f, 0.78f), Roughness = 0.30f, Metallic = 0.00f,
                NormalScale = 0.20f, TextureRepeatWorldSize = new Vector2(0.60f, 0.60f),
                PatternColor = new Color(0.76f, 0.75f, 0.72f), PatternScale = 10f, PatternStrength = 0.08f } },
            { "floor-oak-laminate", new PbrMaterialSpec {
                BaseColor = new Color(0.58f, 0.42f, 0.27f), Roughness = 0.60f, Metallic = 0.00f,
                NormalScale = 0.45f, TextureRepeatWorldSize = new Vector2(1.20f, 0.19f), // one plank
                PatternColor = new Color(0.42f, 0.28f, 0.15f), PatternScale = 5f, PatternStrength = 0.40f } },

            // ---- Fallback for walls and unknown ids (matte plaster) ---------
            { "default", new PbrMaterialSpec {
                BaseColor = new Color(0.80f, 0.80f, 0.80f), Roughness = 0.85f, Metallic = 0.00f,
                NormalScale = 0.25f, TextureRepeatWorldSize = new Vector2(2.00f, 2.00f),
                PatternColor = new Color(0.78f, 0.78f, 0.78f), PatternScale = 4f, PatternStrength = 0.05f } },
        };

        /// <summary>Short alias ids accepted alongside the canonical ones.</summary>
        private static readonly Dictionary<string, string> Aliases = new Dictionary<string, string>
        {
            { "granite", "counter-granite" },
            { "marble", "counter-marble" },
            { "brushed-steel", "steel-brushed" },
            { "oak", "wood-oak" },
            { "walnut", "wood-walnut" },
            { "ceramic", "ceramic-matte" },
        };

        private static readonly Dictionary<string, Material> Cache = new Dictionary<string, Material>();
        private static Shader cachedShader;

        /// <summary>All canonical material ids with a full PBR spec.</summary>
        public static IEnumerable<string> MaterialIds => Specs.Keys;

        /// <summary>Resolve an id or alias to its canonical id ("default" when unknown).</summary>
        public static string CanonicalId(string materialId)
        {
            if (string.IsNullOrEmpty(materialId)) return "default";
            if (Specs.ContainsKey(materialId)) return materialId;
            if (Aliases.TryGetValue(materialId, out string canonical)) return canonical;
            return "default";
        }

        /// <summary>The full PBR spec for an id/alias (unknown ids return "default").</summary>
        public static PbrMaterialSpec GetSpec(string materialId) => Specs[CanonicalId(materialId)];

        /// <summary>
        /// UV repeat counts so the material's texture keeps its authored
        /// world size on a surface of <paramref name="surfaceSizeM"/> metres:
        /// repeats = surfaceSize / TextureRepeatWorldSize, per axis.
        /// </summary>
        public static Vector2 ComputeTextureScale(string materialId, Vector2 surfaceSizeM)
        {
            Vector2 repeat = GetSpec(materialId).TextureRepeatWorldSize;
            return new Vector2(
                surfaceSizeM.x / Mathf.Max(repeat.x, 0.01f),
                surfaceSizeM.y / Mathf.Max(repeat.y, 0.01f));
        }

        /// <summary>
        /// Apply world-unit tiling to the (shared, cached) material behind a
        /// renderer for a surface of the given world size. Because the
        /// material is shared, the last call wins — intended for surfaces
        /// that own their material (floor, worktops), not per-unit fronts.
        /// No-op visually until texture maps are bound; the scale is stored
        /// on the material either way.
        /// </summary>
        public static void ApplyWorldTiling(Renderer renderer, Vector2 surfaceSizeM)
        {
            if (renderer == null || renderer.sharedMaterial == null) return;
            Material material = renderer.sharedMaterial;
            string id = material.name.StartsWith("KD_") ? material.name.Substring(3) : "default";
            Vector2 scale = ComputeTextureScale(id, surfaceSizeM);
            material.mainTextureScale = scale;
            if (material.HasProperty("_BaseMap")) material.SetTextureScale("_BaseMap", scale);
            if (material.HasProperty("_BumpMap")) material.SetTextureScale("_BumpMap", scale);
        }

        /// <summary>
        /// Get (creating and caching on first use) the material for
        /// <paramref name="materialId"/>. Unknown ids return "default".
        /// </summary>
        public static Material Get(string materialId)
        {
            materialId = CanonicalId(materialId);
            if (Cache.TryGetValue(materialId, out Material existing)) return existing;

            PbrMaterialSpec spec = Specs[materialId];
            var material = new Material(ResolveShader()) { name = $"KD_{materialId}" };

            // URP Lit uses _BaseColor; Built-in Standard uses _Color. Set both
            // when present so one code path serves either pipeline.
            if (material.HasProperty("_BaseColor")) material.SetColor("_BaseColor", spec.BaseColor);
            if (material.HasProperty("_Color")) material.SetColor("_Color", spec.BaseColor);
            if (material.HasProperty("_Metallic")) material.SetFloat("_Metallic", spec.Metallic);
            // PBR Roughness -> shader Smoothness (URP _Smoothness / Standard _Glossiness).
            float smoothness = 1f - Mathf.Clamp01(spec.Roughness);
            if (material.HasProperty("_Smoothness")) material.SetFloat("_Smoothness", smoothness);
            if (material.HasProperty("_Glossiness")) material.SetFloat("_Glossiness", smoothness);
            // Normal-map strength, applied now so bound maps need no re-setup.
            if (material.HasProperty("_BumpScale")) material.SetFloat("_BumpScale", spec.NormalScale);

            Cache[materialId] = material;
            return material;
        }

        private static Shader ResolveShader()
        {
            if (cachedShader != null) return cachedShader;
            cachedShader = Shader.Find("Universal Render Pipeline/Lit");
            if (cachedShader == null) cachedShader = Shader.Find("Standard");
            return cachedShader;
        }
    }
}
