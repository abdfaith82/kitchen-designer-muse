package com.kitchendesigner

import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.ProjectRoom
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.RoomType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRoomProjectTest {

    private fun unit(id: String) = PlacedUnit(
        CabinetUnit(id, CabinetType.BASE, 600, 900, 560), PointMm(0, 0),
    )

    @Test
    fun legacyConstructorWrapsSingleKitchenRoom() {
        val room = Room(4_200, 3_600)
        val units = listOf(unit("base-600"))
        val p = Project(id = "p1", name = "Old", room = room, units = units)
        assertEquals(1, p.rooms.size)
        assertEquals(RoomType.KITCHEN, p.activeRoom.type)
        assertEquals(room, p.room)          // back-compat view
        assertEquals(units, p.units)        // back-compat view
        assertEquals(1, p.schemaVersion)
    }

    @Test
    fun activeRoomViewsFollowSelection() {
        val kitchen = ProjectRoom("k", RoomType.KITCHEN, Room(4_200, 3_600), units = listOf(unit("base-600")))
        val bath = ProjectRoom("b", RoomType.BATHROOM, Room(2_400, 2_000), units = listOf(unit("vanity-800")))
        val p = Project(id = "p2", name = "Home", rooms = listOf(kitchen, bath))
        assertEquals("base-600", p.units[0].unit.catalogId)
        val switched = p.withActiveRoom(1)
        assertEquals(RoomType.BATHROOM, switched.activeRoom.type)
        assertEquals(2_400, switched.room.widthMm)
        assertEquals("vanity-800", switched.units[0].unit.catalogId)
        // Original is immutable and unchanged.
        assertEquals(0, p.activeRoomIndex)
    }

    @Test
    fun roomsKeepTheirOwnUnitsIsolated() {
        val kitchen = ProjectRoom("k", RoomType.KITCHEN, Room(4_200, 3_600), units = listOf(unit("base-600")))
        val living = ProjectRoom("l", RoomType.LIVING, Room(5_000, 4_000))
        var p = Project(id = "p3", name = "Home", rooms = listOf(kitchen, living))
        val newKitchen = kitchen.copy(units = kitchen.units + unit("wall-600"))
        p = p.withRoom(newKitchen)
        assertEquals(2, p.roomById("k")!!.units.size)
        assertTrue(p.roomById("l")!!.units.isEmpty())
    }

    @Test
    fun withActiveRoomOutOfRangeIsUnchanged() {
        val p = Project(id = "p4", name = "Home", room = Room(3_000, 3_000))
        assertSame(p, p.withActiveRoom(5))
        assertSame(p, p.withActiveRoom(-1))
    }

    @Test
    fun withAddedRoomAppendsAndReplacesDuplicateId() {
        var p = Project(id = "p5", name = "Home", room = Room(3_000, 3_000))
        p = p.withAddedRoom(ProjectRoom("office", RoomType.OFFICE, Room(3_000, 2_500)))
        assertEquals(2, p.rooms.size)
        p = p.withAddedRoom(ProjectRoom("office", RoomType.OFFICE, Room(3_200, 2_500)))
        assertEquals(2, p.rooms.size)
        assertEquals(3_200, p.roomById("office")!!.room.widthMm)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRoomListRejected() {
        Project(id = "p6", name = "Bad", rooms = emptyList())
    }
}
