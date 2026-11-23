package com.kitchendesigner.editor

import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure 2D placement logic for the editor: snapping, footprints, collision and
 * bounds checks, and an immutable [EditorState] with undo/redo.
 *
 * Everything here is deterministic and free of Android/Compose dependencies so
 * it can be unit-tested on the JVM. All dimensions are millimetres, origin at
 * the room's south-west floor corner (see model/Models.kt).
 */

/** How coarsely a dragged position is quantised. */
enum class SnapMode(val stepMm: Int) {
    /** Free-ish dragging: snap to the nearest 10 mm. */
    FINE_10(10),

    /** Module-grid dragging: snap to the nearest 50 mm. */
    GRID_50(50),
}

/**
 * Normalises an arbitrary rotation to the nearest quarter turn in `0..359`.
 *
 * The angle is first wrapped into `0..359` (`((deg % 360) + 360) % 360`), then
 * snapped to the nearest multiple of 90 (ties round up, e.g. 45 -> 90,
 * 100 -> 90, -90 -> 270). Placement only supports quarter turns because
 * kitchen units are rectangular boxes aligned to walls.
 */
fun normaliseRotation(rotationDeg: Int): Int {
    val wrapped = ((rotationDeg % 360) + 360) % 360
    val snapped = (wrapped / 90.0).roundToInt() * 90
    return snapped % 360
}

/**
 * The floor footprint `(widthMm, depthMm)` of [unit] at [rotationDeg].
 * At 90/270 degrees width and depth are swapped; at 0/180 they are unchanged.
 * Non-quarter-turn input is coerced via [normaliseRotation].
 */
fun footprint(unit: CabinetUnit, rotationDeg: Int): Pair<Int, Int> =
    when (normaliseRotation(rotationDeg)) {
        90, 270 -> unit.depthMm to unit.widthMm
        else -> unit.widthMm to unit.depthMm
    }

/**
 * Rounds [valueMm] to the nearest multiple of [mode]'s step.
 *
 * Rounding is `Math.round` semantics (half rounds up / towards positive
 * infinity): e.g. at [SnapMode.GRID_50], 123 -> 100, 125 -> 150, 127 -> 150;
 * at [SnapMode.FINE_10], 123 -> 120, 127 -> 130.
 */
fun snapValue(valueMm: Int, mode: SnapMode): Int =
    (valueMm.toDouble() / mode.stepMm).roundToInt() * mode.stepMm

/**
 * Snaps a candidate south-west corner ([x], [y]) for a unit with footprint
 * [footW] x [footD] onto a wall when it is within [thresholdMm] of it.
 *
 * X snaps to `0` (west wall) or `room.widthMm - footW` (east wall);
 * Y snaps to `0` (south wall) or `room.depthMm - footD` (north wall).
 * Positions further than the threshold from every wall are returned unchanged.
 */
fun snapToWalls(
    x: Int,
    y: Int,
    footW: Int,
    footD: Int,
    room: Room,
    thresholdMm: Int = 25,
): PointMm {
    fun snapAxis(value: Int, maxPos: Int): Int = when {
        abs(value) <= thresholdMm -> 0
        abs(value - maxPos) <= thresholdMm -> maxPos
        else -> value
    }
    return PointMm(
        snapAxis(x, room.widthMm - footW),
        snapAxis(y, room.depthMm - footD),
    )
}

/**
 * Axis-aligned bounding-box overlap test. Touching edges do NOT count as
 * overlap: two units may sit flush against each other.
 */
fun overlaps(aPos: PointMm, aFoot: Pair<Int, Int>, bPos: PointMm, bFoot: Pair<Int, Int>): Boolean =
    aPos.xMm < bPos.xMm + bFoot.first &&
        bPos.xMm < aPos.xMm + aFoot.first &&
        aPos.yMm < bPos.yMm + bFoot.second &&
        bPos.yMm < aPos.yMm + aFoot.second

/** True when the footprint at [pos] lies fully inside [room]. */
fun inBounds(pos: PointMm, foot: Pair<Int, Int>, room: Room): Boolean =
    pos.xMm >= 0 && pos.yMm >= 0 &&
        pos.xMm + foot.first <= room.widthMm &&
        pos.yMm + foot.second <= room.depthMm

/**
 * True when the unit at [index], if placed at [pos] with [rotationDeg],
 * would overlap any OTHER unit in [units]. The unit's own current footprint
 * is ignored. [room] is part of the signature for symmetry with [inBounds]
 * (callers validate bounds separately); collision here is purely unit-vs-unit.
 */
fun collides(
    units: List<PlacedUnit>,
    index: Int,
    pos: PointMm,
    rotationDeg: Int,
    room: Room,
): Boolean {
    if (index !in units.indices) return false
    val foot = footprint(units[index].unit, rotationDeg)
    return units.indices.any { other ->
        other != index && overlaps(
            pos, foot,
            units[other].position, footprint(units[other].unit, units[other].rotationDeg),
        )
    }
}

/** Why a move/rotate was refused. */
enum class RejectReason { COLLISION, OUT_OF_BOUNDS, NO_SELECTION }

/** Outcome of an attempted edit: either the new state, or the reason plus the unchanged state. */
sealed class MoveResult {
    data class Accepted(val state: EditorState) : MoveResult()
    data class Rejected(val reason: RejectReason, val state: EditorState) : MoveResult()
}

/**
 * Immutable editor state. Every successful mutation returns a NEW state with
 * the previous unit list pushed onto [undoStack] and [redoStack] cleared;
 * rejected mutations return the receiver unchanged.
 */
data class EditorState(
    val room: Room,
    val units: List<PlacedUnit>,
    val selectedIndex: Int? = null,
    val undoStack: List<List<PlacedUnit>> = emptyList(),
    val redoStack: List<List<PlacedUnit>> = emptyList(),
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Selects the unit at [index]; null (or an out-of-range index) clears the selection. */
    fun select(index: Int?): EditorState =
        copy(selectedIndex = if (index != null && index in units.indices) index else null)

    /**
     * Moves the selected unit so its south-west corner lands at ([xMm], [yMm]):
     * the target is snapped to [mode]'s grid, then wall-snapped, then validated.
     * Out-of-bounds is reported before collision. On rejection the state is
     * returned unchanged inside [MoveResult.Rejected].
     */
    fun moveSelectedTo(xMm: Int, yMm: Int, mode: SnapMode): MoveResult {
        val idx = selectedIndex
        if (idx == null || idx !in units.indices) {
            return MoveResult.Rejected(RejectReason.NO_SELECTION, this)
        }
        val placed = units[idx]
        val foot = footprint(placed.unit, placed.rotationDeg)
        val target = snapToWalls(snapValue(xMm, mode), snapValue(yMm, mode), foot.first, foot.second, room)
        if (!inBounds(target, foot, room)) {
            return MoveResult.Rejected(RejectReason.OUT_OF_BOUNDS, this)
        }
        if (collides(units, idx, target, placed.rotationDeg, room)) {
            return MoveResult.Rejected(RejectReason.COLLISION, this)
        }
        val newUnits = units.toMutableList().also { it[idx] = placed.copy(position = target) }
        return MoveResult.Accepted(
            copy(units = newUnits, undoStack = undoStack + listOf(units), redoStack = emptyList()),
        )
    }

    /**
     * Rotates the selected unit by +90 degrees (normalised quarter turns).
     * The rotated footprint must stay in bounds and clear of other units,
     * otherwise the state is returned unchanged inside [MoveResult.Rejected].
     */
    fun rotateSelected(): MoveResult {
        val idx = selectedIndex
        if (idx == null || idx !in units.indices) {
            return MoveResult.Rejected(RejectReason.NO_SELECTION, this)
        }
        val placed = units[idx]
        val newRotation = (normaliseRotation(placed.rotationDeg) + 90) % 360
        val foot = footprint(placed.unit, newRotation)
        if (!inBounds(placed.position, foot, room)) {
            return MoveResult.Rejected(RejectReason.OUT_OF_BOUNDS, this)
        }
        if (collides(units, idx, placed.position, newRotation, room)) {
            return MoveResult.Rejected(RejectReason.COLLISION, this)
        }
        val newUnits = units.toMutableList().also { it[idx] = placed.copy(rotationDeg = newRotation) }
        return MoveResult.Accepted(
            copy(units = newUnits, undoStack = undoStack + listOf(units), redoStack = emptyList()),
        )
    }

    /** Restores the most recent pre-mutation unit list; no-op when there is nothing to undo. */
    fun undo(): EditorState {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(
            units = previous,
            undoStack = undoStack.dropLast(1),
            redoStack = redoStack + listOf(units),
            selectedIndex = selectedIndex?.takeIf { it in previous.indices },
        )
    }

    /** Reapplies the most recently undone unit list; no-op when there is nothing to redo. */
    fun redo(): EditorState {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            units = next,
            redoStack = redoStack.dropLast(1),
            undoStack = undoStack + listOf(units),
            selectedIndex = selectedIndex?.takeIf { it in next.indices },
        )
    }
}
