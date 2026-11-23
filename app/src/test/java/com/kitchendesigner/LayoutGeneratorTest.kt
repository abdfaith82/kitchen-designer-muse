package com.kitchendesigner

import com.kitchendesigner.engine.LayoutGenerator
import com.kitchendesigner.engine.LayoutType
import com.kitchendesigner.model.ApplianceType
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutGeneratorTest {
    private val room = Room(4_200, 3_600)

    @Test fun everyLayoutGeneratesUnitsAndTriangle() {
        LayoutType.entries.forEach { type ->
            val g = LayoutGenerator.generate(room, type)
            assertTrue("$type produced no units", g.units.isNotEmpty())
            assertNotNull(g.triangle)
            assertTrue(g.units.any { it.unit.appliance == ApplianceType.SINK })
            assertTrue(g.units.any { it.unit.appliance == ApplianceType.COOKTOP })
            assertTrue(g.units.any { it.unit.appliance == ApplianceType.FRIDGE })
        }
    }
    @Test fun singleWallModuleCountMatchesWidth() {
        val g = LayoutGenerator.generate(room, LayoutType.SINGLE_WALL)
        val runUnits = g.units.count { it.unit.type != CabinetType.WALL }
        assertEquals(4_200 / 600, runUnits) // 7 modules along the north wall (incl. tall fridge unit)
    }
    @Test fun galleyAisleIsDepthMinusTwoRuns() {
        val g = LayoutGenerator.generate(room, LayoutType.GALLEY)
        assertEquals(3_600 - 560 * 2, g.workAisleMm)
    }
    @Test fun islandLayoutHasIslandUnitsWhenRoomIsDeepEnough() {
        val g = LayoutGenerator.generate(Room(4_800, 4_200), LayoutType.ISLAND)
        assertTrue(g.units.any { it.isIsland })
        assertEquals(1_070, g.workAisleMm)
    }
    @Test fun unitsStayInsideRoom() {
        LayoutType.entries.forEach { type ->
            val g = LayoutGenerator.generate(room, type)
            g.units.forEach { u ->
                assertTrue("x out of room in $type", u.position.xMm in 0..room.widthMm)
                assertTrue("y out of room in $type", u.position.yMm in 0..room.depthMm)
            }
        }
    }
}
