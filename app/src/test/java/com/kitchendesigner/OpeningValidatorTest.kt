package com.kitchendesigner

import com.kitchendesigner.engine.OpeningValidator
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.SwingDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningValidatorTest {

    // Room.walls order: 0=NORTH(4200), 1=SOUTH(4200), 2=EAST(3600), 3=WEST(3600)
    private val room = Room(4_200, 3_600)

    private fun door(wall: Int, offset: Int, width: Int = 900, swing: SwingDirection = SwingDirection.INWARD) =
        Opening(OpeningKind.DOOR, wall, offset, width, swing = swing)

    private fun window(wall: Int, offset: Int, width: Int = 1_200, sill: Int = 900) =
        Opening(OpeningKind.WINDOW, wall, offset, width, sillHeightMm = sill)

    private fun baseUnitAt(x: Int, y: Int, w: Int = 600) = PlacedUnit(
        CabinetUnit("base-$w", CabinetType.BASE, w, 900, 560), PointMm(x, y),
    )

    @Test
    fun validOpeningsProduceNoIssues() {
        val openings = listOf(door(1, 300), window(0, 1_500), door(2, 100, 800))
        assertTrue(OpeningValidator.validate(room, openings).isEmpty())
        assertFalse(OpeningValidator.hasErrors(room, openings))
    }

    @Test
    fun openingPastWallEndIsError() {
        val issues = OpeningValidator.validate(room, listOf(door(1, 3_600, 900))) // ends 4500 > 4200
        assertEquals(1, issues.size)
        assertEquals(OpeningValidator.Rule.WITHIN_WALL, issues[0].rule)
        assertEquals(OpeningValidator.Severity.ERROR, issues[0].severity)
    }

    @Test
    fun negativeOffsetAndBadWallIndexAreErrors() {
        val issues = OpeningValidator.validate(room, listOf(door(1, -100), door(7, 0)))
        assertEquals(2, issues.count { it.rule == OpeningValidator.Rule.WITHIN_WALL })
    }

    @Test
    fun overlappingOpeningsOnSameWallAreError() {
        val issues = OpeningValidator.validate(room, listOf(door(1, 300), window(1, 1_000, 600, sill = 1_100)))
        val overlap = issues.filter { it.rule == OpeningValidator.Rule.NO_OVERLAP }
        assertEquals(1, overlap.size)
        assertEquals(0, overlap[0].openingIndex)
        assertEquals(1, overlap[0].otherOpeningIndex)
    }

    @Test
    fun touchingOpeningsAndOpeningsOnDifferentWallsDoNotOverlap() {
        // Door ends at 1200, window starts at 1200: touching, not overlapping.
        val touching = listOf(door(1, 300), window(1, 1_200, 600, sill = 1_100))
        assertTrue(OpeningValidator.validate(room, touching).none { it.rule == OpeningValidator.Rule.NO_OVERLAP })
        // Same range but different walls: fine.
        val differentWalls = listOf(door(1, 300), door(0, 300))
        assertTrue(OpeningValidator.validate(room, differentWalls).isEmpty())
    }

    @Test
    fun cabinetInDoorSwingZoneWarns() {
        // South-wall door at 300..1200 swinging inward: zone y 0..900, x 300..1200.
        val units = listOf(baseUnitAt(300, 0))
        val issues = OpeningValidator.validate(room, listOf(door(1, 300)), units)
        val warnings = issues.filter { it.rule == OpeningValidator.Rule.CABINET_CLEARANCE }
        assertEquals(1, warnings.size)
        assertEquals(OpeningValidator.Severity.WARNING, warnings[0].severity)
        assertEquals(0, warnings[0].unitIndex)
        assertFalse(OpeningValidator.hasErrors(room, listOf(door(1, 300)), units)) // warning only
    }

    @Test
    fun cabinetClearOfDoorSwingDoesNotWarn() {
        val units = listOf(baseUnitAt(2_000, 0)) // east of the swing zone
        assertTrue(OpeningValidator.validate(room, listOf(door(1, 300)), units).isEmpty())
    }

    @Test
    fun outwardDoorClaimsNoFloorZone() {
        val units = listOf(baseUnitAt(300, 0))
        val outward = door(1, 300, swing = SwingDirection.OUTWARD)
        assertTrue(OpeningValidator.validate(room, listOf(outward), units).isEmpty())
    }

    @Test
    fun lowWindowOverCabinetRunWarns() {
        // North-wall window (sill 300) at x 600..1800; cabinets run along north wall (y = 3600-560).
        val units = listOf(baseUnitAt(600, 3_040))
        val issues = OpeningValidator.validate(room, listOf(window(0, 600, sill = 300)), units)
        assertEquals(1, issues.count { it.rule == OpeningValidator.Rule.CABINET_CLEARANCE })
    }

    @Test
    fun highWindowAboveCounterDoesNotWarn() {
        // Standard kitchen case: sill at 1100 (above the 900 counter) — counter run is fine.
        val units = listOf(baseUnitAt(600, 3_040))
        assertTrue(OpeningValidator.validate(room, listOf(window(0, 600, sill = 1_100)), units).isEmpty())
    }
}
