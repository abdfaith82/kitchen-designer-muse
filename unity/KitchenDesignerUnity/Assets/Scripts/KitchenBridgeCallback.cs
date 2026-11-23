// KitchenBridgeCallback.cs
// Unity -> Kotlin callback plumbing for the Kitchen Designer bridge (JSON v1).
//
// Two cooperating pieces live here:
//
//  1. IKitchenNativeCallback + KitchenBridge.RegisterCallback(...)
//     A plain C# callback interface. Any host (an Android facade, an Editor
//     test harness, a unit test) can register an implementation and receive
//     OnSceneReady / OnUnitSelected / OnBridgeError without touching JNI.
//
//  2. AndroidCallbackProxy (AndroidJavaProxy)
//     The production Android path. AndroidJavaProxy lets Unity expose a C#
//     object to Java/Kotlin as if it implemented a Java interface; here the
//     interface name is "com.kitchendesigner.unity.KitchenBridgeCallback".
//     The methods below simply forward into KitchenBridge's registered
//     callback / static dispatcher, so the C# scene code never talks JNI
//     directly.
//
// The dispatcher (KitchenBridgeCallbackDispatcher) is what SceneBuilder and
// KitchenBridge actually call. On a real Android device it invokes the static
// methods of the Kotlin object `com.kitchendesigner.unity.UnityCallbackProxy`
// (onSceneReady / onUnitSelected / onBridgeError) via AndroidJavaClass —
// those @JvmStatic methods forward to the app's UnityBridgeListener.
// In the Editor (or any non-Android player) it just logs, so the whole flow
// is testable without a device.
//
// Fallback note: if a future host cannot use AndroidJavaClass statics, the
// reverse direction can also be done UnitySendMessage-style — i.e. Kotlin
// polls / the Unity side writes into a well-known GameObject — but
// UnitySendMessage only travels Kotlin -> Unity, so for Unity -> Kotlin the
// AndroidJavaProxy / AndroidJavaClass route above is the supported one.

using System;
using UnityEngine;

namespace KitchenDesigner
{
    /// <summary>C# side of the Unity -> Kotlin callback contract (v1).</summary>
    public interface IKitchenNativeCallback
    {
        void OnSceneReady(string projectId);
        void OnUnitSelected(int unitIndex);
        void OnBridgeError(string code, string message);
    }

#if UNITY_ANDROID && !UNITY_EDITOR
    /// <summary>
    /// AndroidJavaProxy implementation bound to the Java interface name
    /// "com.kitchendesigner.unity.KitchenBridgeCallback". If a Kotlin/Java
    /// host ever needs a callback *object* (rather than the static
    /// UnityCallbackProxy entry points), it can receive an instance of this
    /// proxy through JNI and invoke the interface methods on it; each call
    /// lands here on the Unity main thread and is forwarded to the
    /// registered <see cref="IKitchenNativeCallback"/>.
    /// </summary>
    public sealed class AndroidCallbackProxy : AndroidJavaProxy
    {
        public AndroidCallbackProxy() : base("com.kitchendesigner.unity.KitchenBridgeCallback") { }

        // Method names/signatures intentionally match the Kotlin listener.
        public void onSceneReady(string projectId) => KitchenBridge.ForwardFromNative(s => s.OnSceneReady(projectId));
        public void onUnitSelected(int unitIndex) => KitchenBridge.ForwardFromNative(s => s.OnUnitSelected(unitIndex));
        public void onBridgeError(string code, string message) => KitchenBridge.ForwardFromNative(s => s.OnBridgeError(code, message));
    }
#endif

    /// <summary>
    /// Single exit point for every Unity -> Kotlin callback. Prefers a
    /// C#-registered callback (Editor tests, alternative hosts); on Android
    /// it calls the Kotlin <c>UnityCallbackProxy</c> statics; everywhere
    /// else it logs.
    /// </summary>
    public static class KitchenBridgeCallbackDispatcher
    {
        public static void SceneReady(string projectId)
        {
            if (KitchenBridge.TryDispatch(c => c.OnSceneReady(projectId))) return;
#if UNITY_ANDROID && !UNITY_EDITOR
            CallKotlinStatic("onSceneReady", projectId);
#else
            Debug.Log($"[KitchenBridge] OnSceneReady(projectId={projectId})");
#endif
        }

        public static void UnitSelected(int unitIndex)
        {
            if (KitchenBridge.TryDispatch(c => c.OnUnitSelected(unitIndex))) return;
#if UNITY_ANDROID && !UNITY_EDITOR
            CallKotlinStatic("onUnitSelected", unitIndex);
#else
            Debug.Log($"[KitchenBridge] OnUnitSelected(unitIndex={unitIndex})");
#endif
        }

        public static void BridgeError(string code, string message)
        {
            if (KitchenBridge.TryDispatch(c => c.OnBridgeError(code, message))) return;
#if UNITY_ANDROID && !UNITY_EDITOR
            CallKotlinStatic("onBridgeError", code, message);
#else
            Debug.LogError($"[KitchenBridge] OnBridgeError(code={code}, message={message})");
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private static void CallKotlinStatic(string method, params object[] args)
        {
            try
            {
                using (var proxy = new AndroidJavaClass("com.kitchendesigner.unity.UnityCallbackProxy"))
                {
                    proxy.CallStatic(method, args);
                }
            }
            catch (Exception e)
            {
                // Never let a callback failure crash the render loop.
                Debug.LogError($"[KitchenBridge] Failed to call UnityCallbackProxy.{method}: {e.Message}");
            }
        }
#endif
    }
}
