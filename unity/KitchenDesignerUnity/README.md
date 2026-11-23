# KitchenDesignerUnity

Unity 6 (URP) project for the Kitchen Designer Android app. Unity is used
purely as the 3D renderer/editor (Unity-as-a-Library): the project model,
NKBA rules and BOM live in Kotlin and reach this project through the
versioned JSON bridge (`"v":1`, see `unity/README.md` in the Android repo
and `UnityBridge.kt` on the Kotlin side).

## What's here

| Path | Contents |
|---|---|
| `ProjectSettings/ProjectVersion.txt` | Unity 6000.0.35f1 |
| `Packages/manifest.json` | URP 17, AR Foundation 6 + ARCore XR Plug-in, Mathematics, uGUI/TextMeshPro, Test Framework |
| `Assets/Scripts/KitchenBridge.cs` | `KitchenBridge` MonoBehaviour — the `UnitySendMessage` target. Parses the v1 JSON commands (`LoadProject`, `SetMaterial`, `SetLighting`) with JsonUtility DTOs and delegates to the scene components. |
| `Assets/Scripts/KitchenBridgeCallback.cs` | Unity → Kotlin callbacks: `IKitchenNativeCallback`, a static `RegisterCallback` hook, an `AndroidJavaProxy` implementation, and the dispatcher that calls the Kotlin `UnityCallbackProxy` statics on Android (logs in the Editor). |
| `Assets/Scripts/SceneBuilder.cs` | Builds floor, 4 walls and one box per unit at mm → m scale. v1 sizes come from a built-in table keyed by catalogId prefix (`base` 0.86 m, `wall` 0.72 m mounted at 1.45 m, `tall` 2.1 m); width is parsed from the catalogId suffix (`base-600`). |
| `Assets/Scripts/MaterialLibrary.cs` | Code-created URP Lit (fallback: Standard) PBR materials for the catalog materialIds (`front-oak`, `counter-quartz`, `floor-porcelain`, …). |
| `Assets/Scripts/LightingController.cs` | DAY / EVENING / NIGHT presets: sun colour/intensity/angle, ambient, warm point light. |
| `Assets/Scripts/CameraOrbitController.cs` | Mouse/touch orbit + pinch zoom, clamped pitch/distance. |
| `Assets/Editor/SceneSetup.cs` | Menu **Kitchen Designer → Setup Main Scene**: builds `Assets/Scenes/Main.unity` programmatically. |
| `Assets/Editor/BuildAndroidLibrary.cs` | Menu **Kitchen Designer → Export Android Library**: IL2CPP / ARM64 / min API 29 PlayerSettings + Gradle project export. |
| `Assets/Scenes/README.md` | Why there is no hand-authored `Main.unity` and how to generate it. |

## Open in Unity 6

1. Install Unity Hub + Unity **6000.0.35f1** (or any Unity 6) with the
   **Android Build Support** module (SDK & NDK tools).
2. Unity Hub → Add → select this folder (`KitchenDesignerUnity`).
3. Open the project; let the Package Manager resolve `manifest.json`.

## Generate the scene (one time)

Menu: **Kitchen Designer → Setup Main Scene**. This creates
`Assets/Scenes/Main.unity` with the `KitchenBridge` GameObject (name is
part of the contract), the directional light, the warm point light and
the orbit camera. See `Assets/Scenes/README.md`.

Press Play and the bridge is live in the Editor: without Android attached,
callbacks are logged to the Console, and commands can be exercised by
calling the `KitchenBridge` methods with sample v1 JSON from a test
harness or the Inspector's context tools.

## Export as an Android library

1. Menu: **Kitchen Designer → Export Android Library**.
2. The export writes a Gradle project to `Builds/AndroidExport/`; copy its
   **`unityLibrary`** folder into the Android repo root, beside `app/`.
3. In the Android repo: `include(":unityLibrary")` in
   `settings.gradle.kts` and `implementation(project(":unityLibrary"))`
   in `app/build.gradle.kts`. Full checklist: `unity/README.md` in the
   Android repo.

Once the library is present, the Kotlin `UnityFacade` detects
`com.unity3d.player.UnityPlayer` at runtime and sends real commands;
without it, it transparently falls back to `StubUnityBridge`.

## Honest limitations

- **The C# in this repo has NOT been compiled or run.** It was written
  carefully against the Unity 6 API surface, but there is no Unity
  installation in the environment where this source tree was authored, so
  the first open in Unity may surface small compile errors to fix.
- No `Main.unity` is checked in (see `Assets/Scenes/README.md`) — run the
  SceneSetup menu item once.
- v1 scene building is deliberately simple: units are plain boxes with
  flat-colour PBR materials, sizes come from the catalogId table in
  `SceneBuilder` (the v1 JSON carries no dimensions), and there are no
  textures, prefabs, LODs or lightmaps yet.
- AR Foundation / ARCore packages are declared in the manifest for the
  planned guided-scan feature but no AR scene or code exists yet.
