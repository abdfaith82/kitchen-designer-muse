package com.kitchendesigner

import com.kitchendesigner.model.ApplianceType
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.ProjectRoom
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.RoomType
import com.kitchendesigner.model.SwingDirection
import com.kitchendesigner.persistence.ProjectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonTest {

    private fun sampleProject(): Project {
        val kitchen = ProjectRoom(
            id = "k1", type = RoomType.KITCHEN, room = Room(4_200, 3_600, 2_700), name = "Main kitchen",
            units = listOf(
                PlacedUnit(
                    CabinetUnit("base-600", CabinetType.BASE, 600, 900, 560, "front-oak", ApplianceType.SINK),
                    PointMm(0, 3_040), rotationDeg = 180,
                ),
                PlacedUnit(
                    CabinetUnit("island-1200", CabinetType.BASE, 1_200, 900, 800, "front-acrylic-white"),
                    PointMm(1_500, 1_400), isIsland = true,
                ),
                PlacedUnit(
                    CabinetUnit("wall-600", CabinetType.WALL, 600, 720, 350, "front-walnut"),
                    PointMm(600, 3_250), rotationDeg = 180,
                ),
            ),
            openings = listOf(
                Opening(OpeningKind.DOOR, wallIndex = 1, offsetMm = 300, widthMm = 900, swing = SwingDirection.INWARD),
                Opening(OpeningKind.WINDOW, wallIndex = 0, offsetMm = 1_500, widthMm = 1_200, sillHeightMm = 1_100, heightMm = 1_000),
            ),
        )
        val bath = ProjectRoom(
            id = "b1", type = RoomType.BATHROOM, room = Room(2_400, 2_000, 2_500), name = "Ensuite",
            units = listOf(
                PlacedUnit(CabinetUnit("vanity-800", CabinetType.BASE, 800, 850, 460, "front-lacquer-grey"), PointMm(0, 0)),
            ),
            openings = listOf(
                Opening(OpeningKind.DOOR, wallIndex = 3, offsetMm = 100, widthMm = 700, swing = SwingDirection.OUTWARD, heightMm = 2_000),
            ),
        )
        return Project(id = "proj-1", name = "Adam's \"dream\" home\n2026", rooms = listOf(kitchen, bath), activeRoomIndex = 1)
    }

    @Test
    fun roundTripPreservesEverything() {
        val original = sampleProject()
        val restored = ProjectJson.fromJson(ProjectJson.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun roundTripPreservesMaterialsAndAppliances() {
        val restored = ProjectJson.fromJson(ProjectJson.toJson(sampleProject()))
        val sink = restored.rooms[0].units[0]
        assertEquals("front-oak", sink.unit.materialId)
        assertEquals(ApplianceType.SINK, sink.unit.appliance)
        assertEquals("front-acrylic-white", restored.rooms[0].units[1].unit.materialId)
        assertEquals(null, restored.rooms[0].units[1].unit.appliance)
        assertTrue(restored.rooms[0].units[1].isIsland)
        assertEquals(1, restored.activeRoomIndex)
        assertEquals(RoomType.BATHROOM, restored.activeRoom.type)
    }

    @Test
    fun roundTripEscapesSpecialCharacters() {
        val restored = ProjectJson.fromJson(ProjectJson.toJson(sampleProject()))
        assertEquals("Adam's \"dream\" home\n2026", restored.name)
    }

    @Test
    fun singleRoomLegacyShapeLoadsAsKitchenRoom() {
        val legacy = """{"v":1,"id":"old-1","name":"Legacy","schemaVersion":1,""" +
            """"room":{"widthMm":3000,"depthMm":3000,"ceilingHeightMm":2600},""" +
            """"units":[{"catalogId":"base-600","type":"BASE","widthMm":600,"heightMm":900,"depthMm":560,""" +
            """"materialId":"front-oak","xMm":0,"yMm":0,"rotationDeg":0,"isIsland":false}]}"""
        val p = ProjectJson.fromJson(legacy)
        assertEquals(1, p.rooms.size)
        assertEquals(RoomType.KITCHEN, p.activeRoom.type)
        assertEquals(3_000, p.room.widthMm)
        assertEquals(2_600, p.room.ceilingHeightMm)
        assertEquals(1, p.units.size)
        assertEquals("base-600", p.units[0].unit.catalogId)
    }

    @Test
    fun emptyProjectRoundTrips() {
        val p = Project(id = "e1", name = "Empty", room = Room(1_800, 1_800))
        assertEquals(p.copy(schemaVersion = 1), ProjectJson.fromJson(ProjectJson.toJson(p)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedJsonThrows() {
        ProjectJson.fromJson("{not json")
    }
}
