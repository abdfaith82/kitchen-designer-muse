package com.kitchendesigner.unity

import com.kitchendesigner.model.Project

/** Contract between the Kotlin shell and the Unity 6 (URP) scene.
 *
 * Transport: Unity-as-a-Library. Kotlin -> Unity uses
 * UnityPlayer.UnitySendMessage("KitchenBridge", "<method>", "<json>").
 * Unity -> Kotlin calls the [UnityBridgeListener] methods (wired in MainActivity
 * via a small Java/Kotlin facade the Unity C# side invokes through AndroidJavaProxy).
 *
 * Message JSON shapes (versioned by "v":1 — bump together on both sides):
 *  LoadProject : {"v":1,"cmd":"LoadProject","projectId":"p1","room":{"widthMm":4200,"depthMm":3600,"ceilingHeightMm":2700},
 *                 "units":[{"catalogId":"base-600","xMm":0,"yMm":3040,"rotationDeg":180,"materialId":"front-oak"}]}
 *  SetMaterial : {"v":1,"cmd":"SetMaterial","unitIndex":3,"materialId":"front-acrylic-white"}
 *  SetLighting : {"v":1,"cmd":"SetLighting","mode":"DAY|EVENING|NIGHT","intensity":0.0-1.0}
 * Unity replies: OnSceneReady(projectId), OnUnitSelected(unitIndex), OnBridgeError(code,message)
 */
sealed interface UnityCommand {
    data class LoadProject(val project: Project) : UnityCommand
    data class SetMaterial(val unitIndex: Int, val materialId: String) : UnityCommand
    data class SetLighting(val mode: LightingMode, val intensity: Float = 1f) : UnityCommand
}
enum class LightingMode { DAY, EVENING, NIGHT }

interface UnityBridgeListener {
    fun onSceneReady(projectId: String)
    fun onUnitSelected(unitIndex: Int)
    fun onBridgeError(code: String, message: String)
}

interface UnityBridge {
    fun send(command: UnityCommand)
    fun setListener(listener: UnityBridgeListener?)
    /** Serialises a command to the documented JSON wire shape (pure Kotlin, unit-testable). */
    fun toJson(command: UnityCommand): String
}

abstract class UnityBridgeBase : UnityBridge {
    override fun toJson(command: UnityCommand): String = when (command) {
        is UnityCommand.LoadProject -> buildString {
            val p = command.project
            append("{\"v\":1,\"cmd\":\"LoadProject\",\"projectId\":\"").append(p.id).append("\",")
            append("\"room\":{\"widthMm\":").append(p.room.widthMm).append(",\"depthMm\":").append(p.room.depthMm)
            append(",\"ceilingHeightMm\":").append(p.room.ceilingHeightMm).append("},\"units\":[")
            p.units.forEachIndexed { i, u ->
                if (i > 0) append(",")
                append("{\"catalogId\":\"").append(u.unit.catalogId).append("\",\"xMm\":").append(u.position.xMm)
                append(",\"yMm\":").append(u.position.yMm).append(",\"rotationDeg\":").append(u.rotationDeg)
                append(",\"materialId\":\"").append(u.unit.materialId).append("\"}")
            }
            append("]}")
        }
        is UnityCommand.SetMaterial ->
            "{\"v\":1,\"cmd\":\"SetMaterial\",\"unitIndex\":${command.unitIndex},\"materialId\":\"${command.materialId}\"}"
        is UnityCommand.SetLighting ->
            "{\"v\":1,\"cmd\":\"SetLighting\",\"mode\":\"${command.mode.name}\",\"intensity\":${command.intensity}}"
    }
}

/** STUB — records commands instead of talking to Unity. Replace with a
 *  UnityPlayer-backed implementation once :unityLibrary is exported (unity/README.md). */
class StubUnityBridge : UnityBridgeBase() {
    val sentCommands = mutableListOf<UnityCommand>()
    val sentJson = mutableListOf<String>()
    private var listener: UnityBridgeListener? = null
    override fun setListener(listener: UnityBridgeListener?) { this.listener = listener }
    override fun send(command: UnityCommand) {
        sentCommands += command; sentJson += toJson(command)
        if (command is UnityCommand.LoadProject) listener?.onSceneReady(command.project.id) // STUB: instant "ready"
    }
}
