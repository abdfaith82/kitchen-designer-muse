// SceneSetup.cs  (Editor only)
// One-click creation of the Main scene.
//
// Menu: Kitchen Designer -> Setup Main Scene
//
// Builds Assets/Scenes/Main.unity with exactly the objects the bridge
// contract needs:
//   * "KitchenBridge" GameObject (name is part of the wire contract —
//     Kotlin addresses it via UnitySendMessage("KitchenBridge", ...))
//     carrying KitchenBridge + SceneBuilder + LightingController,
//   * a Directional Light (the "sun" the LightingController retunes),
//   * the Main Camera carrying CameraOrbitController.
// Then saves the scene. Run this once after opening the project; after
// that the scene can be edited by hand like any other.

#if UNITY_EDITOR
using KitchenDesigner;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

public static class SceneSetup
{
    private const string ScenePath = "Assets/Scenes/Main.unity";

    [MenuItem("Kitchen Designer/Setup Main Scene")]
    public static void SetupMainScene()
    {
        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

        // --- Bridge root -------------------------------------------------
        var bridge = new GameObject("KitchenBridge");
        bridge.AddComponent<KitchenBridge>();
        bridge.AddComponent<SceneBuilder>();
        bridge.AddComponent<LightingController>();

        // --- Sun ----------------------------------------------------------
        var sun = new GameObject("Directional Light");
        var sunLight = sun.AddComponent<Light>();
        sunLight.type = LightType.Directional;
        sunLight.intensity = 3f;
        sunLight.color = new Color(1.00f, 0.97f, 0.90f);
        sun.transform.rotation = Quaternion.Euler(50f, -30f, 0f);

        // --- Warm point light (EVENING / NIGHT), starts off --------------
        var warm = new GameObject("WarmPointLight");
        warm.transform.position = new Vector3(2f, 2.5f, 2f);
        var warmLight = warm.AddComponent<Light>();
        warmLight.type = LightType.Point;
        warmLight.color = new Color(1.00f, 0.80f, 0.58f);
        warmLight.range = 8f;
        warm.SetActive(false);

        // --- Camera -------------------------------------------------------
        var cameraGo = new GameObject("Main Camera");
        cameraGo.tag = "MainCamera";
        var camera = cameraGo.AddComponent<Camera>();
        camera.clearFlags = CameraClearFlags.SolidColor;
        camera.backgroundColor = new Color(0.10f, 0.11f, 0.13f);
        cameraGo.AddComponent<AudioListener>();
        var orbit = cameraGo.AddComponent<CameraOrbitController>();
        orbit.SetTarget(new Vector3(2f, 0f, 2f), 7f);
        cameraGo.transform.position = new Vector3(2f, 4f, -3f);
        cameraGo.transform.LookAt(new Vector3(2f, 0f, 2f));

        // --- Save ----------------------------------------------------------
        System.IO.Directory.CreateDirectory("Assets/Scenes");
        EditorSceneManager.SaveScene(scene, ScenePath);
        Debug.Log($"[SceneSetup] Main scene created at {ScenePath}");
    }
}
#endif
