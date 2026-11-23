package com.kitchendesigner.editor

import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.WallSide

/**
 * Dimension annotations for the 2D plan: for each wall, an overall dimension
 * plus the segments the wall is split into by the openings on it.
 *
 * Pure logic, no drawing: the UI renders [DimensionSegment]s as it likes.
 * Segment boundaries are the wall start (0), every opening start/end on that
 * wall (clamped into the wall, so even an invalid opening cannot produce
 * out-of-range segments), and the wall end. Zero-length segments are dropped.
 * Labels are plain millimetre strings, e.g. "1200 mm".
 */
object WallDimensions {

    data class DimensionSegment(val startMm: Int, val endMm: Int) {
        val lengthMm: Int get() = endMm - startMm
        val label: String get() = "$lengthMm mm"
    }

    data class WallDimension(
        val wallIndex: Int,
        val side: WallSide,
        val overall: DimensionSegment,
        val segments: List<DimensionSegment>,
    )

    fun forWall(room: Room, openings: List<Opening>, wallIndex: Int): WallDimension {
        val wall = room.walls[wallIndex]
        val cuts = sortedSetOf(0, wall.lengthMm)
        openings.filter { it.wallIndex == wallIndex }.forEach { o ->
            cuts += o.offsetMm.coerceIn(0, wall.lengthMm)
            cuts += o.endMm.coerceIn(0, wall.lengthMm)
        }
        val points = cuts.toList()
        val segments = points.zipWithNext { a, b -> DimensionSegment(a, b) }.filter { it.lengthMm > 0 }
        return WallDimension(
            wallIndex = wallIndex,
            side = wall.side,
            overall = DimensionSegment(0, wall.lengthMm),
            segments = segments,
        )
    }

    /** Dimensions for all four walls, in [Room.walls] order (N, S, E, W). */
    fun forRoom(room: Room, openings: List<Opening>): List<WallDimension> =
        room.walls.indices.map { forWall(room, openings, it) }
}
