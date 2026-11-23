package com.kitchendesigner

import com.kitchendesigner.editor.EditorState
import com.kitchendesigner.editor.MoveResult
import com.kitchendesigner.editor.RejectReason
import com.kitchendesigner.editor.SnapMode
import com.kitchendesigner.editor.footprint
import com.kitchendesigner.editor.overlaps
import com.kitchendesigner.editor.snapToWalls
import com.kitchendesigner.editor.snapValue
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementEngineTest {
    private val room = Room(4_000, 3_000)
    private val base600 = CabinetUnit("base-600", CabinetType.BASE, widthMm = 600, heightMm = 860, depthMm = 560)

    private fun placed(x: Int, y: Int, rotation: Int = 0, unit: CabinetUnit = base600) =
        PlacedUnit(unit, PointMm(x, y), rotation)

    // --- Snapping ---

    @Test fun snapFineRoundsToNearest10() {
        assertEquals(120, snapValue(123, SnapMode.FINE_10))
        assertEquals(130, snapValue(127, SnapMode.FINE_10))
        assertEquals(130, snapValue(125, SnapMode.FINE_10)) // half rounds up (Math.round semantics)
        assertEquals(0, snapValue(4, SnapMode.FINE_10))
    }

    @Test fun snapGridRoundsToNearest50() {
        assertEquals(100, snapValue(123, SnapMode.GRID_50)) // 123/50 = 2.46 -> 2 steps
        assertEquals(150, snapValue(127, SnapMode.GRID_50)) // 127/50 = 2.54 -> 3 steps
        assertEquals(150, snapValue(125, SnapMode.GRID_50)) // exactly half -> rounds up to 150
        assertEquals(50, snapValue(74, SnapMode.GRID_50))
    }

    @Test fun snapModeSteps() {
        assertEquals(10, SnapMode.FINE_10.stepMm)
        assertEquals(50, SnapMode.GRID_50.stepMm)
    }

    // --- Footprint ---

    @Test fun footprintSwapsAt90And270Only() {
        assertEquals(600 to 560, footprint(base600, 0))
        assertEquals(560 to 600, footprint(base600, 90))
        assertEquals(600 to 560, footprint(base600, 180))
        assertEquals(560 to 600, footprint(base600, 270))
        assertEquals(600 to 560, footprint(base600, 360))
    }

    @Test fun footprintNormalisesRotation() {
        assertEquals(560 to 600, footprint(base600, -90)) // -90 -> 270
        assertEquals(560 to 600, footprint(base600, 100)) // 100 snaps to nearest 90
        assertEquals(600 to 560, footprint(base600, 44)) // 44 snaps to 0
    }

    // --- Overlap ---

    @Test fun overlapsDetectsRealOverlap() {
        val foot = 600 to 560
        assertTrue(overlaps(PointMm(0, 0), foot, PointMm(300, 0), foot))
        assertTrue(overlaps(PointMm(0, 0), foot, PointMm(0, 300), foot))
        assertTrue(overlaps(PointMm(0, 0), foot, PointMm(0, 0), foot))
    }

    @Test fun touchingEdgesDoNotOverlap() {
        val foot = 600 to 560
        assertFalse(overlaps(PointMm(0, 0), foot, PointMm(600, 0), foot)) // flush east
        assertFalse(overlaps(PointMm(0, 0), foot, PointMm(0, 560), foot)) // flush north
        assertFalse(overlaps(PointMm(0, 0), foot, PointMm(2_000, 2_000), foot)) // far away
    }

    // --- Wall snap ---

    @Test fun wallSnapWithinThreshold() {
        // Near south-west corner.
        assertEquals(PointMm(0, 0), snapToWalls(20, 10, 600, 560, room))
        // Near east wall: max x = 4000 - 600 = 3400.
        assertEquals(PointMm(3_400, 1_000), snapToWalls(3_380, 1_000, 600, 560, room))
        // Near north wall: max y = 3000 - 560 = 2440.
        assertEquals(PointMm(1_000, 2_440), snapToWalls(1_000, 2_425, 600, 560, room))
    }

    @Test fun wallSnapLeavesMiddlePositionsAlone() {
        assertEquals(PointMm(1_000, 1_000), snapToWalls(1_000, 1_000, 600, 560, room))
        assertEquals(PointMm(30, 1_000), snapToWalls(30, 1_000, 600, 560, room)) // 30 > 25 threshold
    }

    // --- Moving ---

    @Test fun moveAcceptedUpdatesPositionAndPushesUndo() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        val result = state.moveSelectedTo(1_003, 504, SnapMode.FINE_10)
        assertTrue(result is MoveResult.Accepted)
        val newState = (result as MoveResult.Accepted).state
        assertEquals(PointMm(1_000, 500), newState.units[0].position) // snapped to 10 mm grid
        assertTrue(newState.canUndo)
        assertFalse(newState.canRedo)
        // Original state is untouched (immutability).
        assertEquals(PointMm(0, 0), state.units[0].position)
    }

    @Test fun moveSnapsToWallWithinThreshold() {
        val state = EditorState(room, listOf(placed(600, 600)), selectedIndex = 0)
        val result = state.moveSelectedTo(12, 8, SnapMode.FINE_10) // snaps to 10,10 then wall-snaps to 0,0
        assertTrue(result is MoveResult.Accepted)
        assertEquals(PointMm(0, 0), (result as MoveResult.Accepted).state.units[0].position)
    }

    @Test fun moveRejectedOnCollisionLeavesPositionUnchanged() {
        val state = EditorState(room, listOf(placed(0, 0), placed(1_200, 0)), selectedIndex = 0)
        val result = state.moveSelectedTo(1_000, 0, SnapMode.FINE_10) // 1000..1600 overlaps 1200..1800
        assertTrue(result is MoveResult.Rejected)
        val rejected = result as MoveResult.Rejected
        assertEquals(RejectReason.COLLISION, rejected.reason)
        assertEquals(PointMm(0, 0), rejected.state.units[0].position)
        assertFalse(rejected.state.canUndo)
    }

    @Test fun moveRejectedOutOfBounds() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        val result = state.moveSelectedTo(3_900, 0, SnapMode.FINE_10) // 3900 + 600 > 4000
        assertTrue(result is MoveResult.Rejected)
        val rejected = result as MoveResult.Rejected
        assertEquals(RejectReason.OUT_OF_BOUNDS, rejected.reason)
        assertEquals(PointMm(0, 0), rejected.state.units[0].position)
    }

    @Test fun moveRejectedWithNoSelection() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = null)
        val result = state.moveSelectedTo(100, 100, SnapMode.FINE_10)
        assertTrue(result is MoveResult.Rejected)
        assertEquals(RejectReason.NO_SELECTION, (result as MoveResult.Rejected).reason)
    }

    @Test fun moveFlushAgainstAnotherUnitIsAccepted() {
        val state = EditorState(room, listOf(placed(0, 0), placed(1_200, 0)), selectedIndex = 0)
        val result = state.moveSelectedTo(600, 0, SnapMode.FINE_10) // 600..1200 touches 1200 — no overlap
        assertTrue(result is MoveResult.Accepted)
    }

    // --- Rotating ---

    @Test fun rotateSwapsFootprintAndAdvances90() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        val result = state.rotateSelected()
        assertTrue(result is MoveResult.Accepted)
        val newState = (result as MoveResult.Accepted).state
        assertEquals(90, newState.units[0].rotationDeg)
        assertEquals(560 to 600, footprint(newState.units[0].unit, newState.units[0].rotationDeg))
        assertTrue(newState.canUndo)
    }

    @Test fun rotateRejectedWhenItWouldGoOutOfBounds() {
        // At y=2440 the 560-deep unit ends flush at 3000; rotated it is 600 deep -> 3040 > 3000.
        val state = EditorState(room, listOf(placed(0, 2_440)), selectedIndex = 0)
        val result = state.rotateSelected()
        assertTrue(result is MoveResult.Rejected)
        val rejected = result as MoveResult.Rejected
        assertEquals(RejectReason.OUT_OF_BOUNDS, rejected.reason)
        assertEquals(0, rejected.state.units[0].rotationDeg)
    }

    @Test fun rotateRejectedWhenItWouldCollide() {
        // B sits at y=580: clear of A unrotated (A ends at 560), hit once A rotates to 600 deep.
        val state = EditorState(room, listOf(placed(0, 0), placed(0, 580)), selectedIndex = 0)
        val result = state.rotateSelected()
        assertTrue(result is MoveResult.Rejected)
        assertEquals(RejectReason.COLLISION, (result as MoveResult.Rejected).reason)
        assertEquals(0, (result as MoveResult.Rejected).state.units[0].rotationDeg)
    }

    // --- Undo / redo ---

    @Test fun undoRestoresPreviousPosition() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        val moved = (state.moveSelectedTo(1_000, 500, SnapMode.FINE_10) as MoveResult.Accepted).state
        val undone = moved.undo()
        assertEquals(PointMm(0, 0), undone.units[0].position)
        assertFalse(undone.canUndo)
        assertTrue(undone.canRedo)
    }

    @Test fun redoReappliesUndoneMove() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        val moved = (state.moveSelectedTo(1_000, 500, SnapMode.FINE_10) as MoveResult.Accepted).state
        val redone = moved.undo().redo()
        assertEquals(PointMm(1_000, 500), redone.units[0].position)
        assertTrue(redone.canUndo)
        assertFalse(redone.canRedo)
    }

    @Test fun newMoveClearsRedoStack() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        val moved = (state.moveSelectedTo(1_000, 500, SnapMode.FINE_10) as MoveResult.Accepted).state
        val undone = moved.undo()
        assertTrue(undone.canRedo)
        val movedAgain = (undone.moveSelectedTo(300, 300, SnapMode.FINE_10) as MoveResult.Accepted).state
        assertFalse(movedAgain.canRedo)
        assertEquals(PointMm(300, 300), movedAgain.units[0].position)
    }

    @Test fun undoWithEmptyStackIsNoOp() {
        val state = EditorState(room, listOf(placed(0, 0)), selectedIndex = 0)
        assertEquals(state, state.undo())
        assertEquals(state, state.redo())
    }

    @Test fun selectClearsOnNullOrInvalidIndex() {
        val state = EditorState(room, listOf(placed(0, 0)))
        assertEquals(0, state.select(0).selectedIndex)
        assertEquals(null, state.select(0).select(null).selectedIndex)
        assertEquals(null, state.select(5).selectedIndex)
    }
}
