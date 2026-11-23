package com.kitchendesigner.editor

import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.SwingDirection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-Kotlin drawing geometry for wall [Opening]s (Workstream B, v0.4.0) —
 * the math behind PlanEditorCanvas's opening rendering, kept Android-free
 * so it is unit-verified even though the Compose canvas itself is not
 * Gradle-compiled in this environment.
 *
 * Coordinates are PLAN coordinates (model space): origin at the room's
 * south-west floor corner, x east, y north, all mm. The canvas flips y
 * when it draws; nothing here knows about pixels.
 *
 * Wall conventions (same as model/Openings.kt): wallIndex 0=NORTH,
 * 1=SOUTH, 2=EAST, 3=WEST; offset runs west->east on NORTH/SOUTH walls and
 * south->north on EAST/WEST walls. Angles are mathematical: degrees,
 * 0 = east, counter-clockwise positive (y north), normalised to 0..359.
 */

/** A straight segment between two plan points. */
data class LineSegMm(val start: PointMm, val end: PointMm)

/**
 * A door leaf's swing arc: the leaf is hinged at [hinge], has length
 * [radiusMm] (= door width), starts closed at [startAngleDeg] (leaf lying
 * along the wall) and sweeps to [endAngleDeg] (leaf fully open, 90° away).
 */
data class SwingArc(
    val hinge: PointMm,
    val radiusMm: Int,
    val startAngleDeg: Double,
    val endAngleDeg: Double,
) {
    /** Point on the arc at [angleDeg] (the leaf's free end when the leaf
     *  points that way). */
    fun pointAt(angleDeg: Double): PointMm {
        val rad = Math.toRadians(angleDeg)
        return PointMm(
            hinge.xMm + (radiusMm * cos(rad)).roundToInt(),
            hinge.yMm + (radiusMm * sin(rad)).roundToInt(),
        )
    }

    val leafClosedEnd: PointMm get() = pointAt(startAngleDeg)
    val leafOpenEnd: PointMm get() = pointAt(endAngleDeg)

    /** [steps]+1 points along the arc from closed to open, taking the
     *  short way round (the signed delta is normalised to -180..180) —
     *  exactly what a canvas needs to stroke the swing path. */
    fun samplePoints(steps: Int = 12): List<PointMm> {
        require(steps >= 1) { "steps must be >= 1" }
        var delta = (endAngleDeg - startAngleDeg) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return (0..steps).map { i -> pointAt(startAngleDeg + delta * i / steps) }
    }
}

object OpeningGeometry {

    /** Unit vector pointing from the wall into the room, per wall index. */
    private fun inwardNormal(wallIndex: Int): PointMm = when (wallIndex) {
        0 -> PointMm(0, -1)  // NORTH wall: interior is south
        1 -> PointMm(0, 1)   // SOUTH wall: interior is north
        2 -> PointMm(-1, 0)  // EAST wall: interior is west
        3 -> PointMm(1, 0)   // WEST wall: interior is east
        else -> PointMm(0, 0)
    }

    /** The opening's segment along its wall, in plan coordinates: the
     *  hinge/start point is the offset end, the other end is offset+width. */
    fun wallLine(room: Room, opening: Opening): LineSegMm {
        val o = opening.offsetMm; val e = opening.endMm
        return when (opening.wallIndex) {
            0 -> LineSegMm(PointMm(o, room.depthMm), PointMm(e, room.depthMm)) // NORTH
            1 -> LineSegMm(PointMm(o, 0), PointMm(e, 0))                       // SOUTH
            2 -> LineSegMm(PointMm(room.widthMm, o), PointMm(room.widthMm, e)) // EAST
            3 -> LineSegMm(PointMm(0, o), PointMm(0, e))                       // WEST
            else -> LineSegMm(PointMm(o, 0), PointMm(e, 0))
        }
    }

    /**
     * The swing arc for a hinged door, or null for windows, sliding doors
     * and swing=NONE. The hinge is the opening's start (offset) end; the
     * leaf sweeps 90° — towards the room interior for INWARD doors, away
     * from it for OUTWARD doors.
     */
    fun doorSwingArc(room: Room, opening: Opening): SwingArc? {
        if (opening.kind != OpeningKind.DOOR) return null
        if (opening.swing != SwingDirection.INWARD && opening.swing != SwingDirection.OUTWARD) return null
        val line = wallLine(room, opening)
        val hinge = line.start
        val closed = line.end
        val dx = (closed.xMm - hinge.xMm).toDouble()
        val dy = (closed.yMm - hinge.yMm).toDouble()
        val startAngle = normDeg(Math.toDegrees(atan2(dy, dx)))
        // Rotating the closed direction by +90° gives (-dy, dx); by -90°
        // gives (dy, -dx). Pick the rotation whose result points the way
        // the leaf should swing (inward normal, or its opposite).
        val normal = inwardNormal(opening.wallIndex)
        val sign = if (opening.swing == SwingDirection.INWARD) 1 else -1
        val targetX = normal.xMm * sign; val targetY = normal.yMm * sign
        val ccwDot = (-dy) * targetX + dx * targetY
        val endAngle = normDeg(startAngle + if (ccwDot >= 0) 90.0 else -90.0)
        return SwingArc(hinge, opening.widthMm, startAngle, endAngle)
    }

    /**
     * The two parallel lines a window is drawn with on a plan, straddling
     * the wall line by [gapMm]/2 on each side (outer line first, then the
     * inner line). Null for doors.
     */
    fun windowLines(room: Room, opening: Opening, gapMm: Int = 80): Pair<LineSegMm, LineSegMm>? {
        if (opening.kind != OpeningKind.WINDOW) return null
        val line = wallLine(room, opening)
        val n = inwardNormal(opening.wallIndex)
        val half = gapMm / 2
        fun offset(seg: LineSegMm, k: Int) = LineSegMm(
            PointMm(seg.start.xMm + n.xMm * k, seg.start.yMm + n.yMm * k),
            PointMm(seg.end.xMm + n.xMm * k, seg.end.yMm + n.yMm * k),
        )
        return offset(line, -half) to offset(line, half)
    }

    private fun normDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
