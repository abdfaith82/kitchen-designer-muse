// BuildAndroidLibrary.cs  (Editor only)
// Unity-as-a-Library export for the Kitchen Designer Android app.
//
// Menu: Kitchen Designer -> Export Android Library
//
// What it does:
//   1. Configures PlayerSettings for the Android library build used by the
//      Kotlin app: IL2CPP scripting backend, ARM64 only, min SDK API 29,
//      landscape-capable "KitchenDesignerUnity" product.
//   2. Enables "Export Project" and runs BuildPipeline.BuildPlayer for the
//      Main scene, writing a Gradle project to <project>/Builds/AndroidExport.
//   3. The export contains a `unityLibrary` Gradle module. Copy or symlink
//      that folder into the Android repo root (next to the `app` module),
//      then in the Android repo:
//        * settings.gradle.kts:  include(":unityLibrary")
//        * app/build.gradle.kts: implementation(project(":unityLibrary"))
//      See unity/README.md in the Android repo for the full checklist.
//
// Note: exporting requires the Android Build Support module (with SDK/NDK)
// installed for this Unity version via Unity Hub.

#if UNITY_EDITOR
using System.IO;
using UnityEditor;
using UnityEngine;

public static class BuildAndroidLibrary
{
    private const string ExportPath = "Builds/AndroidExport";

    [MenuItem("Kitchen Designer/Export Android Library")]
    public static void ExportAndroidLibrary()
    {
        ConfigurePlayerSettings();

        string fullExportPath = Path.Combine(Directory.GetCurrentDirectory(), ExportPath);
        Directory.CreateDirectory(fullExportPath);

        var options = new BuildPlayerOptions
        {
            scenes = new[] { "Assets/Scenes/Main.unity" },
            locationPathName = fullExportPath,
            target = BuildTarget.Android,
            options = BuildOptions.None,
        };

        var report = BuildPipeline.BuildPlayer(options);
        if (report.summary.result == UnityEditor.Build.Reporting.BuildResult.Succeeded)
        {
            Debug.Log($"[BuildAndroidLibrary] Export succeeded: {fullExportPath}\n" +
                      "Copy the generated 'unityLibrary' folder into the Android repo root, " +
                      "then include(\":unityLibrary\") in settings.gradle.kts and add " +
                      "implementation(project(\":unityLibrary\")) to app/build.gradle.kts.");
        }
        else
        {
            Debug.LogError($"[BuildAndroidLibrary] Export failed: {report.summary.result} " +
                           $"({report.summary.totalErrors} errors). See the build report for details.");
        }
    }

    private static void ConfigurePlayerSettings()
    {
        PlayerSettings.companyName = "KitchenDesigner";
        PlayerSettings.productName = "KitchenDesignerUnity";

        // Unity-as-a-Library requirements for the Kotlin host app.
        PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
        PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
        PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29;
        PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;

        // "Export Project" (produce a Gradle project with a unityLibrary
        // module instead of an APK/AAB).
        EditorUserBuildSettings.exportAsGoogleAndroidProject = true;
        EditorUserBuildSettings.buildAppBundle = false;

        // The host app provides the activity; Unity renders into it.
        PlayerSettings.Android.forceInternetPermission = false;
        PlayerSettings.Android.forceSDCardPermission = false;
    }
}
#endif
