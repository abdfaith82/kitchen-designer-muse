package com.kitchendesigner.engine

import com.kitchendesigner.editor.footprint
import com.kitchendesigner.editor.overlaps
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.SwingDirection
import com.kitchendesigner.model.WallSide

/**
 * Pure-Kotlin validation of wall [Opening]s (doors/windows) for one room.
 *
 * Rules:
 *  1. WITHIN_WALL (ERROR) — wall index valid, width > 0, and the opening's
 *     range [offsetMm, offsetMm + widthMm) lies fully inside the wall length.
 *  2. NO_OVERLAP (ERROR) — two openings on the SAME wall must not overlap;
 *     touching edges (one ends where the next starts) are allowed.
 *  3. CABINET_CLEARANCE (WARNING) — a placed unit whose floor footprint
 *     intersects the opening's floor zone (see [floorZone]) is flagged:
 *     you should not run cabinets through a door swing or under a low window.
 *
 * Floor zone (the area of floor the opening "claims", as an AABB):
 *  - Door swinging INWARD: a square of depth = door width along the wall
 *    (the leaf's swing area, approximated axis-aligned).
 *  - Door OUTWARD / SLIDING / NONE: no floor zone inside this room.
 *  - Window with sill below counter height ([COUNTER_HEIGHT_MM]): a strip
 *    of depth [WINDOW_ZONE_DEPTH_MM] — base cabinets under it would block
 *    the window. Windows at/above counter height claim no floor zone, so
 *    a normal kitchen counter run under a high window is NOT flagged.
 */
object OpeningValidator {

    const val COUNTER_HEIGHT_MM = 900
    const val WINDOW_ZONE_DEPTH_MM = 600

    enum class Rule { WITHIN_WALL, NO_OVERLAP, CABINET_CLEARANCE }
    enum class Severity { ERROR, WARNING }

    data class Issue(
        val openingIndex: Int,
        val rule: Rule,
        val severity: Severity,
        val message: String,
        /** Index of the colliding placed unit (CABINET_CLEARANCE only). */
        val unitIndex: Int? = null,
        /** Index of the other opening (NO_OVERLAP only). */
        val otherOpeningIndex: Int? = null,
    )

    /** Axis-aligned floor rectangle: south-west corner + size. */
    data class RectMm(val origin: PointMm, val widthMm: Int, val depthMm: Int)

    /** The floor zone [opening] claims in [room], or null when it claims none. */
    fun floorZone(room: Room, opening: Opening): RectMm? {
        val side = opening.wallSide ?: return null
        val depth = when (opening.kind) {
            OpeningKind.DOOR ->
                if (opening.swing == SwingDirection.INWARD) opening.widthMm else return null
            OpeningKind.WINDOW ->
                if (opening.sillHeightMm < COUNTER_HEIGHT_MM) WINDOW_ZONE_DEPTH_MM else return null
        }
        return when (side) {
            // Offset runs west->east along NORTH/SOUTH, south->north along EAST/WEST.
            WallSide.SOUTH -> RectMm(PointMm(opening.offsetMm, 0), opening.widthMm, depth)
            WallSide.NORTH -> RectMm(PointMm(opening.offsetMm, room.depthMm - depth), opening.widthMm, depth)
            WallSide.WEST -> RectMm(PointMm(0, opening.offsetMm), depth, opening.widthMm)
            WallSide.EAST -> RectMm(PointMm(room.widthMm - depth, opening.offsetMm), depth, opening.widthMm)
        }
    }

    fun validate(room: Room, openings: List<Opening>, units: List<PlacedUnit> = emptyList()): List<Issue> {
        val issues = mutableListOf<Issue>()

        // Rule 1: each opening fits within its wall.
        openings.forEachIndexed { i, o ->
            val wallLen = o.wallLengthMm(room)
            when {
                wallLen == null -> issues += Issue(i, Rule.WITHIN_WALL, Severity.ERROR,
                    "Opening #$i has invalid wall index ${o.wallIndex}")
                o.widthMm <= 0 -> issues += Issue(i, Rule.WITHIN_WALL, Severity.ERROR,
                    "Opening #$i has non-positive width ${o.widthMm} mm")
                o.offsetMm < 0 || o.endMm > wallLen -> issues += Issue(i, Rule.WITHIN_WALL, Severity.ERROR,
                    "Opening #$i spans ${o.offsetMm}..${o.endMm} mm but wall ${o.wallSide} is $wallLen mm long")
            }
        }

        // Rule 2: openings on the same wall must not overlap (touching is fine).
        for (i in openings.indices) {
            for (j in i + 1 until openings.size) {
                val a = openings[i]; val b = openings[j]
                if (a.wallIndex != b.wallIndex) continue
                if (a.offsetMm < b.endMm && b.offsetMm < a.endMm) {
                    issues += Issue(i, Rule.NO_OVERLAP, Severity.ERROR,
                        "Openings #$i and #$j overlap on wall ${a.wallSide}", otherOpeningIndex = j)
                }
            }
        }

        // Rule 3 (warning): cabinet run colliding with a door/window floor zone.
        openings.forEachIndexed { i, o ->
            val zone = floorZone(room, o) ?: return@forEachIndexed
            units.forEachIndexed { u, placed ->
                val foot = footprint(placed.unit, placed.rotationDeg)
                if (overlaps(zone.origin, zone.widthMm to zone.depthMm, placed.position, foot)) {
                    issues += Issue(i, Rule.CABINET_CLEARANCE, Severity.WARNING,
                        "Unit #${u} (${placed.unit.catalogId}) collides with the ${o.kind.name.lowercase()} zone on wall ${o.wallSide}",
                        unitIndex = u)
                }
            }
        }
        return issues
    }

    /** Convenience: true when there are no ERROR-severity issues. */
    fun hasErrors(room: Room, openings: List<Opening>, units: List<PlacedUnit> = emptyList()): Boolean =
        validate(room, openings, units).any { it.severity == Severity.ERROR }
}
