# Unity 6 (URP) — setup & Unity-as-a-Library integration

The 3D scene lives in a **separate Unity project** (not in this repo yet). The Android
app treats Unity purely as a renderer/editor for the parametric `Project` model —
the model, NKBA rules and BOM stay in Kotlin and never depend on Unity.

## 1. Create the Unity project
1. Unity Hub → New Project → **Universal 3D (URP)** template, Unity **6.x**.
2. Project name: `KitchenDesignerUnity`. Target: Android, IL2CPP, ARM64.
3. Add packages: **AR Foundation** + **ARCore XR Plug-in** (for the guided scan / AR view).
4. Performance budgets (from the research spec): URP with baked lightmaps/GI,
   LOD0–2, ASTC textures, texture atlases, ≤150 draw calls in a typical scene,
   30 fps on a 6 GB RAM mid-range phone, automatic "Lite" quality tier.

## 2. Export as a library
1. Player Settings → **Export Project** (Android) — or use
   *File → Build Settings → Android → Export Project*.
2. Copy/symlink the exported `unityLibrary` module into the Android repo root.
3. In the Android repo: uncomment `include(":unityLibrary")` in `settings.gradle.kts`
   and `implementation(project(":unityLibrary"))` in `app/build.gradle.kts`.
4. Add Unity's `UnityPlayerActivity` (or a hosting fragment) to the manifest and
   launch it from the Compose shell when the user opens the 3D view.

## 3. Bridge contract (must match `unity/UnityBridge.kt` on the Kotlin side)
Kotlin → Unity: `UnityPlayer.UnitySendMessage("KitchenBridge", method, json)` with
versioned JSON (`"v":1`):
- `LoadProject` — `{"v":1,"cmd":"LoadProject","projectId":"…","room":{"widthMm":…,"depthMm":…,"ceilingHeightMm":…},"units":[{"catalogId":"…","xMm":…,"yMm":…,"rotationDeg":…,"materialId":"…"}]}`
- `SetMaterial` — `{"v":1,"cmd":"SetMaterial","unitIndex":3,"materialId":"…"}`
- `SetLighting` — `{"v":1,"cmd":"SetLighting","mode":"DAY|EVENING|NIGHT","intensity":0.0-1.0}`

Unity → Kotlin: call the Android side through `AndroidJavaProxy` / a JNI facade that
forwards to `UnityBridgeListener`: `onSceneReady(projectId)`, `onUnitSelected(unitIndex)`,
`onBridgeError(code, message)`.

## 4. C# bridge example (put on a GameObject named exactly `KitchenBridge`)
```csharp
using UnityEngine;

public class KitchenBridge : MonoBehaviour
{
    // Called from Android via UnitySendMessage("KitchenBridge", "LoadProject", json)
    public void LoadProject(string json)
    {
        var cmd = JsonUtility.FromJson<LoadProjectCmd>(json);
        // TODO: clear scene, build room (mm -> metres: divide by 1000f),
        // instantiate parametric cabinet prefabs per unit, apply materials.
        NativeCallback("onSceneReady", cmd.projectId);
    }

    public void SetMaterial(string json)
    {
        var cmd = JsonUtility.FromJson<SetMaterialCmd>(json);
        // TODO: swap the PBR material of unit cmd.unitIndex to cmd.materialId.
    }

    public void SetLighting(string json)
    {
        var cmd = JsonUtility.FromJson<SetLightingCmd>(json);
        // TODO: switch DAY/EVENING/NIGHT lighting rig + intensity.
    }

    private void NativeCallback(string method, string arg)
    {
#if UNITY_ANDROID && !UNITY_EDITOR
        using var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
        using var activity = player.GetStatic<AndroidJavaObject>("currentActivity");
        using var facade = new AndroidJavaClass("com.kitchendesigner.unity.UnityFacade");
        facade.CallStatic(method, activity, arg);
#endif
    }

    [System.Serializable] public class LoadProjectCmd { public int v; public string cmd; public string projectId; public Room room; public Unit[] units; }
    [System.Serializable] public class Room { public int widthMm; public int depthMm; public int ceilingHeightMm; }
    [System.Serializable] public class Unit { public string catalogId; public int xMm; public int yMm; public int rotationDeg; public string materialId; }
    [System.Serializable] public class SetMaterialCmd { public int v; public string cmd; public int unitIndex; public string materialId; }
    [System.Serializable] public class SetLightingCmd { public int v; public string cmd; public string mode; public float intensity; }
}
```
Note: the Kotlin `UnityFacade` JNI shim referenced above is **not yet written** — it is
step 1 when the Unity export lands. Until then the app uses `StubUnityBridge`, which
records commands and immediately reports the scene as ready.

## 5. Scene-building rules for the Unity side
- Units are **parametric**: build cabinet geometry from width/height/depth in the JSON,
  do not ship one fixed mesh per SKU.
- Pivot at the unit's south-west floor corner; rotation is degrees clockwise in plan view.
- Materials are PBR (albedo/normal/roughness) identified by `materialId` from the Kotlin catalog.
