package com.kitchendesigner

import com.kitchendesigner.editor.OpeningGeometry
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.SwingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningGeometryTest {
    private val room = Room(4_200, 3_600) // width x depth

    @Test fun wallLineFollowsWallConventions() {
        // SOUTH (1): along y=0, west->east.
        val south = OpeningGeometry.wallLine(room, Opening(OpeningKind.DOOR, 1, 300, 900))
        assertEquals(PointMm(300, 0), south.start); assertEquals(PointMm(1_200, 0), south.end)
        // NORTH (0): along y=depth.
        val north = OpeningGeometry.wallLine(room, Opening(OpeningKind.WINDOW, 0, 1_200, 1_200, sillHeightMm = 900))
        assertEquals(PointMm(1_200, 3_600), north.start); assertEquals(PointMm(2_400, 3_600), north.end)
        // EAST (2): along x=width, south->north.
        val east = OpeningGeometry.wallLine(room, Opening(OpeningKind.DOOR, 2, 500, 800))
        assertEquals(PointMm(4_200, 500), east.start); assertEquals(PointMm(4_200, 1_300), east.end)
        // WEST (3): along x=0.
        val west = OpeningGeometry.wallLine(room, Opening(OpeningKind.DOOR, 3, 500, 800))
        assertEquals(PointMm(0, 500), west.start); assertEquals(PointMm(0, 1_300), west.end)
    }

    @Test fun southDoorInwardSwingsIntoTheRoom() {
        val arc = OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 1, 300, 900, SwingDirection.INWARD))!!
        assertEquals(PointMm(300, 0), arc.hinge)
        assertEquals(900, arc.radiusMm)
        assertEquals(0.0, arc.startAngleDeg, 0.001)
        assertEquals(PointMm(1_200, 0), arc.leafClosedEnd)
        assertEquals(90.0, arc.endAngleDeg, 0.001)
        assertEquals(PointMm(300, 900), arc.leafOpenEnd) // leaf points north, into the room
    }

    @Test fun northDoorInwardSwingsSouth() {
        val arc = OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 0, 300, 900, SwingDirection.INWARD))!!
        assertEquals(PointMm(300, 3_600), arc.hinge)
        assertEquals(PointMm(1_200, 3_600), arc.leafClosedEnd)
        assertEquals(270.0, arc.endAngleDeg, 0.001)
        assertEquals(PointMm(300, 2_700), arc.leafOpenEnd)
    }

    @Test fun westDoorInwardSwingsEast() {
        val arc = OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 3, 500, 800, SwingDirection.INWARD))!!
        assertEquals(PointMm(0, 500), arc.hinge)
        assertEquals(90.0, arc.startAngleDeg, 0.001)
        assertEquals(PointMm(0, 1_300), arc.leafClosedEnd)
        assertEquals(0.0, arc.endAngleDeg, 0.001)
        assertEquals(PointMm(800, 500), arc.leafOpenEnd)
    }

    @Test fun eastDoorInwardSwingsWest() {
        val arc = OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 2, 500, 800, SwingDirection.INWARD))!!
        assertEquals(PointMm(4_200, 500), arc.hinge)
        assertEquals(PointMm(4_200, 1_300), arc.leafClosedEnd)
        assertEquals(PointMm(3_400, 500), arc.leafOpenEnd)
    }

    @Test fun southDoorOutwardSwingsOutOfTheRoom() {
        val arc = OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 1, 300, 900, SwingDirection.OUTWARD))!!
        assertEquals(270.0, arc.endAngleDeg, 0.001)
        assertEquals(PointMm(300, -900), arc.leafOpenEnd)
    }

    @Test fun windowsAndSlidingDoorsHaveNoArc() {
        assertNull(OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.WINDOW, 1, 300, 1_200, sillHeightMm = 900)))
        assertNull(OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 1, 300, 900, SwingDirection.SLIDING)))
        assertNull(OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 1, 300, 900, SwingDirection.NONE)))
    }

    @Test fun arcSamplingHitsBothEndsAndStaysOnRadius() {
        val arc = OpeningGeometry.doorSwingArc(room, Opening(OpeningKind.DOOR, 1, 300, 900, SwingDirection.INWARD))!!
        val pts = arc.samplePoints(steps = 4)
        assertEquals(5, pts.size)
        assertEquals(arc.leafClosedEnd, pts.first())
        assertEquals(arc.leafOpenEnd, pts.last())
        // Mid sample at 45 degrees: (300 + 900*cos45, 900*sin45) = (936, 636).
        assertEquals(PointMm(936, 636), pts[2])
        assertTrue(pts.all { it.distanceTo(arc.hinge) in 899..901 })
    }

    @Test fun windowLinesStraddleTheWall() {
        val win = Opening(OpeningKind.WINDOW, 1, 1_200, 1_200, sillHeightMm = 900)
        val (outer, inner) = OpeningGeometry.windowLines(room, win, gapMm = 80)!!
        assertEquals(PointMm(1_200, -40), outer.start); assertEquals(PointMm(2_400, -40), outer.end)
        assertEquals(PointMm(1_200, 40), inner.start); assertEquals(PointMm(2_400, 40), inner.end)
        // North wall: outer is north of the wall line, inner south of it.
        val northWin = Opening(OpeningKind.WINDOW, 0, 600, 900, sillHeightMm = 1_000)
        val (nOuter, nInner) = OpeningGeometry.windowLines(room, northWin, gapMm = 80)!!
        assertEquals(3_640, nOuter.start.yMm); assertEquals(3_560, nInner.start.yMm)
        // Doors have no window lines.
        assertNull(OpeningGeometry.windowLines(room, Opening(OpeningKind.DOOR, 1, 300, 900)))
    }
}
