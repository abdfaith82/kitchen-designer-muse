package com.kitchendesigner

import com.kitchendesigner.editor.WallDimensions
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.WallSide
import org.junit.Assert.assertEquals
import org.junit.Test

class WallDimensionsTest {

    private val room = Room(4_200, 3_600) // walls: N/S = 4200, E/W = 3600

    @Test
    fun wallWithoutOpeningsIsOneSegment() {
        val d = WallDimensions.forWall(room, emptyList(), wallIndex = 1)
        assertEquals(WallSide.SOUTH, d.side)
        assertEquals(0, d.overall.startMm)
        assertEquals(4_200, d.overall.endMm)
        assertEquals("4200 mm", d.overall.label)
        assertEquals(listOf(WallDimensions.DimensionSegment(0, 4_200)), d.segments)
    }

    @Test
    fun openingSplitsWallIntoThreeSegments() {
        val door = Opening(OpeningKind.DOOR, wallIndex = 1, offsetMm = 300, widthMm = 900)
        val d = WallDimensions.forWall(room, listOf(door), wallIndex = 1)
        assertEquals(
            listOf(
                WallDimensions.DimensionSegment(0, 300),
                WallDimensions.DimensionSegment(300, 1_200),
                WallDimensions.DimensionSegment(1_200, 4_200),
            ),
            d.segments,
        )
        assertEquals(listOf("300 mm", "900 mm", "3000 mm"), d.segments.map { it.label })
        assertEquals(4_200, d.segments.sumOf { it.lengthMm })
    }

    @Test
    fun multipleOpeningsSplitAndSortRegardlessOfInputOrder() {
        val window = Opening(OpeningKind.WINDOW, wallIndex = 0, offsetMm = 2_400, widthMm = 1_200)
        val door = Opening(OpeningKind.DOOR, wallIndex = 0, offsetMm = 300, widthMm = 900)
        val d = WallDimensions.forWall(room, listOf(window, door), wallIndex = 0)
        assertEquals(
            listOf(0 to 300, 300 to 1_200, 1_200 to 2_400, 2_400 to 3_600, 3_600 to 4_200),
            d.segments.map { it.startMm to it.endMm },
        )
    }

    @Test
    fun openingsOnOtherWallsAreIgnored() {
        val eastDoor = Opening(OpeningKind.DOOR, wallIndex = 2, offsetMm = 100, widthMm = 800)
        val south = WallDimensions.forWall(room, listOf(eastDoor), wallIndex = 1)
        assertEquals(1, south.segments.size)
        val east = WallDimensions.forWall(room, listOf(eastDoor), wallIndex = 2)
        assertEquals(3_600, east.overall.lengthMm)
        assertEquals(listOf(0 to 100, 100 to 900, 900 to 3_600), east.segments.map { it.startMm to it.endMm })
    }

    @Test
    fun openingAtWallStartProducesNoZeroSegment() {
        val door = Opening(OpeningKind.DOOR, wallIndex = 1, offsetMm = 0, widthMm = 900)
        val d = WallDimensions.forWall(room, listOf(door), wallIndex = 1)
        assertEquals(listOf(0 to 900, 900 to 4_200), d.segments.map { it.startMm to it.endMm })
    }

    @Test
    fun forRoomCoversAllFourWalls() {
        val all = WallDimensions.forRoom(room, emptyList())
        assertEquals(4, all.size)
        assertEquals(listOf(WallSide.NORTH, WallSide.SOUTH, WallSide.EAST, WallSide.WEST), all.map { it.side })
        assertEquals(listOf(4_200, 4_200, 3_600, 3_600), all.map { it.overall.lengthMm })
    }
}
