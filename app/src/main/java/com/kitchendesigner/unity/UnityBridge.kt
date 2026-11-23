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
 *                 "units":[{"catalogId":"base-600","xMm":0,"yMm":3040,"rotationDeg":180,"materialId":"front-oak",
 *                            "widthMm":600,"heightMm":860,"depthMm":560,"doorStyle":"SHAKER","handleStyle":"BAR",
 *                            "handleFinishId":"handle-black","interiorLed":false}],
 *                 "finishes":{"countertopMaterialId":"counter-granite-black","backsplashId":"backsplash-greek-key",
 *                             "floorFinishId":"floor-marble-white","wallFinishId":"ivory","plinthFinishId":"plinth-black",
 *                             "corniceFinishId":"cornice-black","pendantId":null},
 *                 "lighting":{"underCabinetLed":true,"coveLed":true}}
 *  (v0.5.0: unit sizes + door/handle customization, design finishes and LED
 *  lighting flags are additive fields on the same v1 shape — the Unity side
 *  reads what it knows and ignores the rest.)
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
                append(",\"materialId\":\"").append(u.unit.materialId).append("\"")
                append(",\"widthMm\":").append(u.unit.widthMm).append(",\"heightMm\":").append(u.unit.heightMm)
                append(",\"depthMm\":").append(u.unit.depthMm)
                append(",\"doorStyle\":\"").append(u.unit.custom.doorStyle.name).append("\"")
                append(",\"handleStyle\":\"").append(u.unit.custom.handleStyle.name).append("\"")
                append(",\"handleFinishId\":\"").append(u.unit.custom.handleFinishId).append("\"")
                append(",\"interiorLed\":").append(u.unit.custom.interiorLed).append("}")
            }
            val f = p.finishes
            append("],\"finishes\":{")
            append("\"countertopMaterialId\":\"").append(f.countertopMaterialId).append("\"")
            append(",\"backsplashId\":\"").append(f.backsplashId).append("\"")
            append(",\"floorFinishId\":\"").append(f.floorFinishId).append("\"")
            append(",\"wallFinishId\":\"").append(f.wallFinishId).append("\"")
            append(",\"plinthFinishId\":\"").append(f.plinthFinishId).append("\"")
            append(",\"corniceFinishId\":\"").append(f.corniceFinishId).append("\"")
            append(",\"pendantId\":").append(if (f.pendantId == null) "null" else "\"${f.pendantId}\"")
            append("},\"lighting\":{")
            append("\"underCabinetLed\":").append(f.underCabinetLed)
            append(",\"coveLed\":").append(f.coveLed).append("}}")
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
