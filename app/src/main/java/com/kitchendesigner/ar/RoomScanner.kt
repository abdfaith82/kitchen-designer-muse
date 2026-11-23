package com.kitchendesigner.ar

import com.kitchendesigner.model.Room

/** Room capture. Research reality on Android: no LiDAR in practice, ARCore Depth
 *  is depth-from-motion (best 0.5–5 m) — so scanning is GUIDED and always
 *  correctable, and manual / Bluetooth-laser entry is the accuracy path. */
data class ScanResult(val room: Room, val confidence: Float, val source: ScanSource, val notes: List<String> = emptyList())
enum class ScanSource { ARCORE_GUIDED, MANUAL, LASER }

interface ScanCallback { fun onResult(result: ScanResult); fun onError(code: String, message: String) }

interface RoomScanner {
    val source: ScanSource
    fun isAvailable(): Boolean
    fun startScan(callback: ScanCallback)
    fun stopScan()
}

/** The accuracy path that always works: the user types wall dimensions. */
class ManualRoomScanner(private val widthMm: Int, private val depthMm: Int, private val ceilingHeightMm: Int = 2_700) : RoomScanner {
    override val source = ScanSource.MANUAL
    override fun isAvailable(): Boolean = true
    override fun startScan(callback: ScanCallback) =
        callback.onResult(ScanResult(Room(widthMm, depthMm, ceilingHeightMm), confidence = 1.0f, source = source,
            notes = listOf("Manual entry — verify with a tape or laser before ordering")))
    override fun stopScan() = Unit
}

/** STUB — real implementation uses ARCore Session + Depth API + plane fusion
 *  inside the Unity AR Foundation scene (or a native ARCore activity), then
 *  snaps detected planes to right angles and asks the user to confirm lengths.
 *  TODO(ar): implement behind this interface; do not market as survey-grade. */
class StubArCoreRoomScanner : RoomScanner {
    override val source = ScanSource.ARCORE_GUIDED
    override fun isAvailable(): Boolean = false // STUB: until the ARCore session code lands
    override fun startScan(callback: ScanCallback) =
        callback.onError("NOT_IMPLEMENTED", "STUB: ARCore guided scan is not implemented yet — use manual entry")
    override fun stopScan() = Unit
}
