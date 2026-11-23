package com.kitchendesigner.unity

import java.lang.reflect.Method

/**
 * Real [UnityBridge] backed by Unity-as-a-Library, with a graceful fallback.
 *
 * Transport (Kotlin -> Unity): `UnityPlayer.UnitySendMessage("KitchenBridge",
 * method, json)`, where `method` is the command name (`LoadProject`,
 * `SetMaterial`, `SetLighting`) and `json` is the v1 wire shape produced by
 * [UnityBridgeBase.toJson].
 *
 * The UnityPlayer class is reached **by reflection only** — this file has no
 * Android imports and no compile-time dependency on the `:unityLibrary`
 * module, so the pure-Kotlin core (and its unit tests) compiles and runs
 * without Unity present. Presence is probed once, eagerly, in the
 * constructor:
 *
 *  - Unity present: [send] invokes `UnitySendMessage` reflectively on
 *    `com.unity3d.player.UnityPlayer`, addressed to the GameObject named
 *    `KitchenBridge` (must match the C# `KitchenBridge` MonoBehaviour's
 *    GameObject in the Unity scene).
 *  - Unity absent (unit tests, CI, or an APK built before the Unity export
 *    lands): [send] delegates to an internal [StubUnityBridge] and
 *    [usingFallback] is `true`, so the rest of the app behaves exactly as it
 *    did with the stub — including the stub's instant `onSceneReady`.
 *
 * Transport (Unity -> Kotlin): the C# side calls the @JvmStatic methods of
 * [UnityCallbackProxy] (via `AndroidJavaClass` / an `AndroidJavaProxy`
 * implementation in `KitchenBridgeCallback.cs`), which forward to the
 * listener registered on the active [UnityFacade] instance — see
 * [UnityCallbackProxy] below.
 */
class UnityFacade : UnityBridgeBase() {

    /** The reflectively resolved `UnityPlayer.UnitySendMessage(String, String, String)`, or null when Unity is absent. */
    private val unitySendMessage: Method? = resolveUnitySendMessage()

    /** True when Unity is not on the classpath and commands go to the stub instead. */
    val usingFallback: Boolean get() = unitySendMessage == null

    /** Stub used when Unity is absent; also receives the listener so its callbacks keep working. */
    private val fallback = StubUnityBridge()

    private var listener: UnityBridgeListener? = null

    init {
        // UnityCallbackProxy forwards Unity -> Kotlin callbacks to whichever
        // facade is active; the most recently constructed facade wins.
        UnityCallbackProxy.activeFacade = this
    }

    override fun setListener(listener: UnityBridgeListener?) {
        this.listener = listener
        fallback.setListener(listener)
    }

    override fun send(command: UnityCommand) {
        val sendMessage = unitySendMessage
        if (sendMessage == null) {
            fallback.send(command)
            return
        }
        val methodName = when (command) {
            is UnityCommand.LoadProject -> "LoadProject"
            is UnityCommand.SetMaterial -> "SetMaterial"
            is UnityCommand.SetLighting -> "SetLighting"
        }
        try {
            // UnitySendMessage is a static native method on UnityPlayer;
            // invoke(null, ...) is correct for a static Method.
            sendMessage.invoke(null, GAME_OBJECT_NAME, methodName, toJson(command))
        } catch (e: Exception) {
            // Unity was present at probe time but the call failed (e.g. the
            // Unity player is not running yet). Report through the bridge
            // error channel instead of crashing the caller.
            listener?.onBridgeError("SEND_FAILED", e.cause?.message ?: e.message ?: "UnitySendMessage failed")
        }
    }

    /** Called by [UnityCallbackProxy] when the C# side reports back. */
    internal fun dispatchSceneReady(projectId: String) {
        listener?.onSceneReady(projectId)
    }

    /** Called by [UnityCallbackProxy] when the C# side reports back. */
    internal fun dispatchUnitSelected(unitIndex: Int) {
        listener?.onUnitSelected(unitIndex)
    }

    /** Called by [UnityCallbackProxy] when the C# side reports back. */
    internal fun dispatchBridgeError(code: String, message: String) {
        listener?.onBridgeError(code, message)
    }

    private fun resolveUnitySendMessage(): Method? = try {
        val unityPlayerClass = Class.forName(UNITY_PLAYER_CLASS)
        unityPlayerClass.getMethod(
            "UnitySendMessage",
            String::class.java,
            String::class.java,
            String::class.java,
        )
    } catch (e: Exception) {
        // ClassNotFoundException / NoSuchMethodException / linkage errors all
        // mean "no usable Unity runtime" — fall back to the stub.
        null
    }

    companion object {
        /** Fully-qualified name of Unity's player class, provided by the exported `:unityLibrary` module. */
        private const val UNITY_PLAYER_CLASS = "com.unity3d.player.UnityPlayer"

        /** GameObject name the C# bridge lives on; must match the Unity scene exactly. */
        private const val GAME_OBJECT_NAME = "KitchenBridge"
    }
}

/**
 * Entry points for Unity -> Kotlin callbacks.
 *
 * The C# bridge (`KitchenBridge.cs` / `KitchenBridgeCallback.cs` in the
 * Unity project) calls these methods from Unity through JNI:
 *
 *  - via `AndroidJavaClass("com.kitchendesigner.unity.UnityCallbackProxy")`
 *    and `CallStatic("onSceneReady", projectId)` etc. — the @JvmStatic
 *    methods below compile to real static methods on this class, which is
 *    what `AndroidJavaClass.CallStatic` requires; or
 *  - via an `AndroidJavaProxy` implementation whose interface methods
 *    forward into the same dispatcher on the C# side.
 *
 * Calls arrive on a Unity thread, not the Android main thread — listeners
 * must be prepared to marshal to the UI thread themselves (e.g. Compose
 * state holders / `runOnUiThread` in the hosting activity).
 *
 * Each call is forwarded to the listener registered on the active
 * [UnityFacade] (the most recently constructed instance). If no facade
 * exists yet, the callback is dropped — Unity cannot legitimately report
 * scene events before the app has created its bridge.
 */
object UnityCallbackProxy {

    /** The facade whose listener receives callbacks; set by [UnityFacade]'s constructor. */
    @Volatile
    internal var activeFacade: UnityFacade? = null

    /** Unity -> Kotlin: the scene for [projectId] has been built and is ready. */
    @JvmStatic
    fun onSceneReady(projectId: String) {
        activeFacade?.dispatchSceneReady(projectId)
    }

    /** Unity -> Kotlin: the user selected the unit at [index] in the scene. */
    @JvmStatic
    fun onUnitSelected(index: Int) {
        activeFacade?.dispatchUnitSelected(index)
    }

    /** Unity -> Kotlin: the bridge hit an error ([code], human-readable [message]). */
    @JvmStatic
    fun onBridgeError(code: String, message: String) {
        activeFacade?.dispatchBridgeError(code, message)
    }
}
