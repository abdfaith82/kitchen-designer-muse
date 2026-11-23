package com.kitchendesigner.ar

import com.kitchendesigner.model.Room

/**
 * Fusion of room-dimension evidence from guided ARCore scanning and from a
 * Bluetooth laser measure, into a single best estimate per dimension.
 *
 * Rules (deterministic, unit-testable, no Android dependencies):
 *  - A laser measurement is treated as confidence 1.0 and OVERRIDES all AR
 *    estimates for that dimension — the laser is the accuracy path.
 *  - Otherwise the highest-confidence AR observation wins; ties go to the
 *    LATEST observation (the user re-measuring supersedes an equal reading).
 *  - A dimension lands in [RoomEstimate.needsConfirmation] when it is missing
 *    entirely or its winning confidence is below [confidenceThreshold].
 */

enum class DimensionKind { WIDTH, DEPTH, HEIGHT }

/** One AR (or manual) reading of a single room dimension. */
data class DimensionObservation(
    val kind: DimensionKind,
    val valueMm: Int,
    val confidence: Float,
    val source: ScanSource,
)

/** One laser reading of a single room dimension. Always confidence 1.0. */
data class LaserMeasurement(val kind: DimensionKind, val valueMm: Int)

/**
 * Best estimate per dimension. [room] is non-null only when both width and
 * depth are known; a missing height falls back to the 2700 mm default in the
 * room but still leaves HEIGHT in [needsConfirmation].
 */
data class RoomEstimate(
    val widthMm: Int?,
    val depthMm: Int?,
    val heightMm: Int?,
    val confidenceByKind: Map<DimensionKind, Float>,
    val needsConfirmation: Set<DimensionKind>,
    val room: Room?,
)

class ScanSession(private val confidenceThreshold: Float = 0.85f) {

    private val observations = mutableListOf<DimensionObservation>()
    private val laserByKind = linkedMapOf<DimensionKind, LaserMeasurement>()

    /** True once any laser measurement has been recorded. */
    val hasLaser: Boolean get() = laserByKind.isNotEmpty()

    fun addObservation(observation: DimensionObservation) {
        observations += observation
    }

    fun addLaser(measurement: LaserMeasurement) {
        laserByKind[measurement.kind] = measurement
    }

    private data class Winner(val valueMm: Int, val confidence: Float)

    private fun winnerFor(kind: DimensionKind): Winner? {
        laserByKind[kind]?.let { return Winner(it.valueMm, confidence = 1.0f) }
        var best: DimensionObservation? = null
        for (obs in observations) {
            if (obs.kind != kind) continue
            val current = best
            // >= so that on a confidence tie the LATEST observation wins.
            if (current == null || obs.confidence >= current.confidence) best = obs
        }
        return best?.let { Winner(it.valueMm, it.confidence) }
    }

    fun estimate(): RoomEstimate {
        val winners: Map<DimensionKind, Winner> =
            DimensionKind.entries.mapNotNull { kind -> winnerFor(kind)?.let { kind to it } }.toMap()
        val confidenceByKind: Map<DimensionKind, Float> = winners.mapValues { it.value.confidence }
        val needsConfirmation: Set<DimensionKind> = DimensionKind.entries
            .filter { kind ->
                val winner = winners[kind]
                winner == null || winner.confidence < confidenceThreshold
            }
            .toSet()
        val width = winners[DimensionKind.WIDTH]?.valueMm
        val depth = winners[DimensionKind.DEPTH]?.valueMm
        val height = winners[DimensionKind.HEIGHT]?.valueMm
        val room = if (width != null && depth != null) {
            Room(width, depth, height ?: DEFAULT_CEILING_HEIGHT_MM)
        } else {
            null
        }
        return RoomEstimate(width, depth, height, confidenceByKind, needsConfirmation, room)
    }

    /**
     * Converts the current estimate to a [ScanResult], or null when no room
     * can be formed yet (width or depth missing). Source is [ScanSource.LASER]
     * if any laser measurement contributed, else [ScanSource.ARCORE_GUIDED].
     * Overall confidence is the MINIMUM of the width and depth confidences —
     * the room is only as trustworthy as its weakest wall pair. Notes list
     * every dimension that still needs user confirmation.
     */
    fun toScanResult(): ScanResult? {
        val est = estimate()
        val room = est.room ?: return null
        val confidence = minOf(
            est.confidenceByKind.getValue(DimensionKind.WIDTH),
            est.confidenceByKind.getValue(DimensionKind.DEPTH),
        )
        val notes = est.needsConfirmation
            .sortedBy { it.ordinal }
            .map { kind ->
                val conf = est.confidenceByKind[kind]
                if (conf == null) {
                    "${kind.name} not measured — needs confirmation before ordering"
                } else {
                    "${kind.name} confidence $conf below threshold $confidenceThreshold — needs confirmation"
                }
            }
        return ScanResult(
            room = room,
            confidence = confidence,
            source = if (hasLaser) ScanSource.LASER else ScanSource.ARCORE_GUIDED,
            notes = notes,
        )
    }

    companion object {
        const val DEFAULT_CEILING_HEIGHT_MM = 2_700
    }
}
