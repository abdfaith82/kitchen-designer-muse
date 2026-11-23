# Assets/Scenes

`Main.unity` is **not checked in as a hand-written file** — Unity scene
files are version-specific YAML that are easy to corrupt by hand, so the
scene is created programmatically instead.

## One-time setup

1. Open this project in Unity 6 (see the project README).
2. Menu: **Kitchen Designer → Setup Main Scene**.
3. The Editor script (`Assets/Editor/SceneSetup.cs`) creates and saves
   `Assets/Scenes/Main.unity` containing:
   - **KitchenBridge** — an empty GameObject named exactly `KitchenBridge`
     (the name is part of the bridge contract: Kotlin calls
     `UnitySendMessage("KitchenBridge", …)`) with the `KitchenBridge`,
     `SceneBuilder` and `LightingController` components attached.
   - **Directional Light** — the sun that `LightingController` retunes for
     the DAY / EVENING / NIGHT presets.
   - **WarmPointLight** — a warm point light, initially inactive, switched
     on by the EVENING / NIGHT presets.
   - **Main Camera** — tagged `MainCamera`, carrying
     `CameraOrbitController` (drag to orbit, scroll/pinch to zoom).
4. After that, open `Main.unity` and edit it like any normal scene.

If `Main.unity` already exists, running the menu item again recreates it
from scratch (unsaved changes to the generated scene will be lost).
