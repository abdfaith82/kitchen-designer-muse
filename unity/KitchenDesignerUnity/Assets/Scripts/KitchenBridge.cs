// KitchenBridge.cs
// Kotlin -> Unity entry point for the Kitchen Designer bridge (JSON v1).
//
// Attach this component to a GameObject named EXACTLY "KitchenBridge" —
// the Kotlin side addresses it with
//     UnityPlayer.UnitySendMessage("KitchenBridge", "<Method>", json)
// so both the GameObject name and the public method names below are part of
// the wire contract and must not be renamed.
//
// Wire shapes (v1, produced by UnityBridgeBase.toJson on the Kotlin side):
//   LoadProject : {"v":1,"cmd":"LoadProject","projectId":"p1",
//                  "room":{"widthMm":4200,"depthMm":3600,"ceilingHeightMm":2700},
//                  "units":[{"catalogId":"base-600","xMm":0,"yMm":3040,
//                             "rotationDeg":180,"materialId":"front-oak"}]}
//   SetMaterial : {"v":1,"cmd":"SetMaterial","unitIndex":3,"materialId":"front-acrylic-white"}
//   SetLighting : {"v":1,"cmd":"SetLighting","mode":"DAY|EVENING|NIGHT","intensity":0.0-1.0}
//
// Unity -> Kotlin replies go through KitchenBridgeCallbackDispatcher:
//   OnSceneReady(projectId), OnUnitSelected(unitIndex), OnBridgeError(code, message).
//
// JSON parsing uses JsonUtility with the fixed DTO shapes below. That is
// safe here because the shapes are fixed and the only array (`units`) is
// nested inside the root object — JsonUtility cannot parse a top-level
// array, and v1 never sends one.

using System;
using UnityEngine;

namespace KitchenDesigner
{
    public class KitchenBridge : MonoBehaviour
    {
        [Header("Scene collaborators (auto-found if left empty)")]
        [SerializeField] private SceneBuilder sceneBuilder;
        [SerializeField] private LightingController lightingController;
        [SerializeField] private CameraOrbitController cameraOrbit;

        // ------------------------------------------------------------------
        // Callback registration (see KitchenBridgeCallback.cs)
        // ------------------------------------------------------------------
        private static IKitchenNativeCallback registeredCallback;

        /// <summary>
        /// Register a C# callback sink for Unity -> Kotlin events. Hosts that
        /// embed this scene outside the standard Android facade (Editor tests,
        /// alternative players) use this instead of the JNI dispatcher.
        /// Pass null to revert to the platform default (AndroidJavaClass
        /// statics on Android, Debug.Log elsewhere).
        /// </summary>
        public static void RegisterCallback(IKitchenNativeCallback callback)
        {
            registeredCallback = callback;
        }

        internal static bool TryDispatch(Action<IKitchenNativeCallback> call)
        {
            if (registeredCallback == null) return false;
            try { call(registeredCallback); }
            catch (Exception e) { Debug.LogError($"[KitchenBridge] Registered callback threw: {e}"); }
            return true;
        }

        internal static void ForwardFromNative(Action<IKitchenNativeCallback> call) => TryDispatch(call);

        // ------------------------------------------------------------------
        // Lifecycle
        // ------------------------------------------------------------------
        private void Awake()
        {
            if (sceneBuilder == null) sceneBuilder = GetComponent<SceneBuilder>();
            if (sceneBuilder == null) sceneBuilder = gameObject.AddComponent<SceneBuilder>();
            if (lightingController == null) lightingController = FindObjectOfType<LightingController>();
            if (cameraOrbit == null) cameraOrbit = FindObjectOfType<CameraOrbitController>();
        }

        // ------------------------------------------------------------------
        // Kotlin -> Unity commands (invoked via UnitySendMessage)
        // ------------------------------------------------------------------

        /// <summary>Rebuild the whole room + units from a LoadProject JSON payload.</summary>
        public void LoadProject(string json)
        {
            LoadProjectCmd cmd;
            try
            {
                cmd = JsonUtility.FromJson<LoadProjectCmd>(json);
            }
            catch (Exception e)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("PARSE_ERROR", $"LoadProject: {e.Message}");
                return;
            }

            if (cmd == null || cmd.v != 1)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("UNSUPPORTED_VERSION",
                    $"LoadProject: expected v=1, got {(cmd == null ? "null payload" : cmd.v.ToString())}");
                return;
            }
            if (cmd.room == null)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("INVALID_COMMAND", "LoadProject: missing room");
                return;
            }

            try
            {
                sceneBuilder.Build(cmd);
                // Frame the new room with the orbit camera.
                if (cameraOrbit != null)
                {
                    float w = cmd.room.widthMm / 1000f;
                    float d = cmd.room.depthMm / 1000f;
                    cameraOrbit.SetTarget(new Vector3(w / 2f, 0f, d / 2f), Mathf.Max(w, d) * 1.1f);
                }
                KitchenBridgeCallbackDispatcher.SceneReady(cmd.projectId ?? string.Empty);
            }
            catch (Exception e)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("BUILD_FAILED", $"LoadProject: {e.Message}");
            }
        }

        /// <summary>Swap the material of one already-placed unit.</summary>
        public void SetMaterial(string json)
        {
            SetMaterialCmd cmd;
            try { cmd = JsonUtility.FromJson<SetMaterialCmd>(json); }
            catch (Exception e)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("PARSE_ERROR", $"SetMaterial: {e.Message}");
                return;
            }
            if (cmd == null || cmd.v != 1)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("UNSUPPORTED_VERSION", "SetMaterial: expected v=1");
                return;
            }
            if (!sceneBuilder.SetUnitMaterial(cmd.unitIndex, cmd.materialId))
            {
                KitchenBridgeCallbackDispatcher.BridgeError("INVALID_UNIT_INDEX",
                    $"SetMaterial: no unit at index {cmd.unitIndex}");
            }
        }

        /// <summary>Switch the lighting rig to a DAY / EVENING / NIGHT preset.</summary>
        public void SetLighting(string json)
        {
            SetLightingCmd cmd;
            try { cmd = JsonUtility.FromJson<SetLightingCmd>(json); }
            catch (Exception e)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("PARSE_ERROR", $"SetLighting: {e.Message}");
                return;
            }
            if (cmd == null || cmd.v != 1)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("UNSUPPORTED_VERSION", "SetLighting: expected v=1");
                return;
            }
            if (lightingController == null)
            {
                KitchenBridgeCallbackDispatcher.BridgeError("NO_LIGHTING_RIG", "SetLighting: no LightingController in scene");
                return;
            }
            lightingController.Apply(cmd.mode, Mathf.Clamp01(cmd.intensity));
        }

        // ------------------------------------------------------------------
        // Tap-to-select: a quick tap (not an orbit drag) raycasts into the
        // scene and reports the tapped unit back to Kotlin.
        // ------------------------------------------------------------------
        private Vector2 pointerDownPos;
        private float pointerDownTime;
        private bool pointerDown;

        private void Update()
        {
            if (Input.GetMouseButtonDown(0))
            {
                pointerDown = true;
                pointerDownPos = Input.mousePosition;
                pointerDownTime = Time.unscaledTime;
            }
            else if (pointerDown && Input.GetMouseButtonUp(0))
            {
                pointerDown = false;
                bool quickTap = Time.unscaledTime - pointerDownTime < 0.3f;
                bool smallMove = Vector2.Distance(pointerDownPos, Input.mousePosition) < 12f;
                if (quickTap && smallMove) TrySelectAt(Input.mousePosition);
            }
        }

        private void TrySelectAt(Vector2 screenPos)
        {
            var cam = Camera.main;
            if (cam == null || sceneBuilder == null) return;
            Ray ray = cam.ScreenPointToRay(screenPos);
            if (Physics.Raycast(ray, out RaycastHit hit, 100f))
            {
                var marker = hit.collider.GetComponentInParent<UnitIndexMarker>();
                if (marker != null)
                    KitchenBridgeCallbackDispatcher.UnitSelected(marker.Index);
            }
        }

        // ------------------------------------------------------------------
        // DTOs — field names must match the v1 JSON exactly (JsonUtility is
        // case-sensitive and ignores unknown fields).
        // ------------------------------------------------------------------
        [Serializable]
        public class RoomDto
        {
            public int widthMm;
            public int depthMm;
            public int ceilingHeightMm;
        }

        [Serializable]
        public class UnitDto
        {
            public string catalogId;
            public int xMm;
            public int yMm;
            public int rotationDeg;
            public string materialId;
        }

        [Serializable]
        public class LoadProjectCmd
        {
            public int v;
            public string cmd;
            public string projectId;
            public RoomDto room;
            public UnitDto[] units;
        }

        [Serializable]
        public class SetMaterialCmd
        {
            public int v;
            public string cmd;
            public int unitIndex;
            public string materialId;
        }

        [Serializable]
        public class SetLightingCmd
        {
            public int v;
            public string cmd;
            public string mode;      // "DAY" | "EVENING" | "NIGHT"
            public float intensity; // 0..1
        }
    }
}
