package com.kitchendesigner

import com.kitchendesigner.assistant.ReferenceStyle
import com.kitchendesigner.assistant.ReferenceStyles
import com.kitchendesigner.model.ApplianceType
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.DoorStyle
import com.kitchendesigner.model.HandleStyle
import com.kitchendesigner.model.KitchenFinishes
import com.kitchendesigner.model.Materials
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.UnitCustomization
import com.kitchendesigner.persistence.ProjectJson
import com.kitchendesigner.render.HttpCloudRenderClient
import com.kitchendesigner.render.RenderPreset
import com.kitchendesigner.render.RenderQuality
import com.kitchendesigner.render.RenderRequest
import com.kitchendesigner.unity.StubUnityBridge
import com.kitchendesigner.unity.UnityCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.5.0: full per-unit customization, reference-photo styles, finishes,
 *  and their persistence + render-payload wiring. */
class CustomizationTest {

    private fun unit(
        id: String, type: CabinetType, width: Int = 600,
        appliance: ApplianceType? = null,
    ) = CabinetUnit(id, type, width, 860, 560, appliance = appliance)

    private fun placed(u: CabinetUnit, x: Int = 0) = PlacedUnit(u, PointMm(x, 0))

    private fun sampleUnits(): List<PlacedUnit> = listOf(
        placed(unit("base-600", CabinetType.BASE), 0),
        placed(unit("base-sink-600", CabinetType.BASE, appliance = ApplianceType.SINK), 600),
        placed(unit("wall-600", CabinetType.WALL), 0),
        placed(unit("wall-900", CabinetType.WALL, width = 900), 600),
        placed(unit("wall-600", CabinetType.WALL), 1_500),
        placed(unit("tall-fridge-700", CabinetType.TALL, width = 700, appliance = ApplianceType.FRIDGE), 2_100),
        placed(unit("tall-600", CabinetType.TALL), 2_800),
    )

    // ------------------------------------------------------------ model

    @Test fun cabinetUnitDefaultsToPlainSlabCustomization() {
        val u = unit("base-600", CabinetType.BASE)
        assertEquals(UnitCustomization(), u.custom)
        assertEquals(DoorStyle.SLAB, u.custom.doorStyle)
        assertEquals(HandleStyle.BAR, u.custom.handleStyle)
        assertFalse(u.custom.interiorLed)
    }

    @Test fun everyDimensionAndCustomFieldIsEditableByCopy() {
        val u = unit("base-600", CabinetType.BASE).copy(
            widthMm = 750, heightMm = 900, depthMm = 600,
            materialId = "walnut",
            custom = UnitCustomization(DoorStyle.SHAKER, HandleStyle.KNOB, "handle-brass", interiorLed = true),
        )
        assertEquals(750, u.widthMm)
        assertEquals(900, u.heightMm)
        assertEquals(600, u.depthMm)
        assertEquals("walnut", u.materialId)
        assertEquals(DoorStyle.SHAKER, u.custom.doorStyle)
        assertEquals(HandleStyle.KNOB, u.custom.handleStyle)
        assertEquals("handle-brass", u.custom.handleFinishId)
        assertTrue(u.custom.interiorLed)
    }

    // ------------------------------------------------------- materials

    @Test fun materialPreviewColorsResolveForLibraryAndLegacyIds() {
        assertEquals(0xFFEDE4D3, Materials.previewColorArgb("cabinet-cream"))
        assertEquals(0xFF6E4B2A, Materials.previewColorArgb("walnut"))
        assertEquals(0xFFC8A165, Materials.previewColorArgb("front-oak")) // legacy keyword fallback
        assertNotEquals(
            Materials.previewColorArgb("cabinet-cream"),
            Materials.previewColorArgb("cabinet-charcoal"),
        )
        // Unknown ids get the stable neutral fallback, never a crash.
        assertEquals(0xFF8D8D93, Materials.previewColorArgb("no-such-finish"))
    }

    @Test fun finishLibrariesHaveUniqueIds() {
        val all = Materials.CABINET_FINISHES + Materials.COUNTERTOPS + Materials.BACKSPLASHES +
            Materials.FLOORS + Materials.HANDLE_FINISHES + Materials.PENDANTS
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    // -------------------------------------------------- reference styles

    @Test fun classicCreamMakesShakerCreamWithBlackGraniteAndGreekKey() {
        val styled = ReferenceStyles.apply(sampleUnits(), ReferenceStyle.CLASSIC_CREAM_SHAKER)
        assertTrue(styled.units.all { it.unit.materialId == "cabinet-cream" })
        assertEquals("counter-granite-black", styled.finishes.countertopMaterialId)
        assertEquals("backsplash-greek-key", styled.finishes.backsplashId)
        assertTrue(styled.finishes.underCabinetLed)
        assertTrue(styled.finishes.coveLed)
        // Non-glass units are shaker with black bar handles.
        val plain = styled.units.filter { it.unit.custom.doorStyle != DoorStyle.GLASS }
        assertTrue(plain.all { it.unit.custom.doorStyle == DoorStyle.SHAKER })
        assertTrue(plain.all { it.unit.custom.handleStyle == HandleStyle.BAR })
        assertTrue(plain.all { it.unit.custom.handleFinishId == "handle-black" })
    }

    @Test fun classicCreamTurnsLastApplianceFreeTallUnitIntoLitGlassDisplay() {
        val styled = ReferenceStyles.apply(sampleUnits(), ReferenceStyle.CLASSIC_CREAM_SHAKER)
        val glass = styled.units.filter { it.unit.custom.doorStyle == DoorStyle.GLASS }
        assertEquals(1, glass.size)
        assertEquals("tall-600", glass.single().unit.catalogId)
        assertTrue(glass.single().unit.custom.interiorLed)
    }

    @Test fun luxeSplitsWalnutBaseAndCharcoalUppersWithAlternatingGlass() {
        val styled = ReferenceStyles.apply(sampleUnits(), ReferenceStyle.LUXE_WALNUT_CHARCOAL)
        val bases = styled.units.filter { it.unit.type == CabinetType.BASE }
        assertTrue(bases.all { it.unit.materialId == "walnut" })
        assertTrue(bases.all { it.unit.custom.handleStyle == HandleStyle.INTEGRATED })
        val walls = styled.units.filter { it.unit.type == CabinetType.WALL }
        assertTrue(walls.all { it.unit.materialId == "cabinet-charcoal" })
        // Three wide wall units -> positions 0 and 2 become glass + LED.
        val glassWalls = walls.filter { it.unit.custom.doorStyle == DoorStyle.GLASS }
        assertEquals(2, glassWalls.size)
        assertTrue(glassWalls.all { it.unit.custom.interiorLed })
        assertEquals("counter-marble-black-gold", styled.finishes.countertopMaterialId)
        assertEquals("backsplash-marble-black", styled.finishes.backsplashId)
        assertEquals("pendant-copper-globe", styled.finishes.pendantId)
    }

    @Test fun minimalCreamIsAllSlabIntegratedWithWoodBacksplash() {
        val styled = ReferenceStyles.apply(sampleUnits(), ReferenceStyle.MINIMAL_CREAM)
        assertTrue(styled.units.all { it.unit.materialId == "cabinet-cream" })
        assertTrue(styled.units.all { it.unit.custom.doorStyle == DoorStyle.SLAB })
        assertTrue(styled.units.all { it.unit.custom.handleStyle == HandleStyle.INTEGRATED })
        assertTrue(styled.units.none { it.unit.custom.interiorLed })
        assertEquals("backsplash-wood-oak", styled.finishes.backsplashId)
        assertEquals("counter-quartz", styled.finishes.countertopMaterialId)
    }

    @Test fun stylingNeverMovesOrResizesUnits() {
        val original = sampleUnits()
        val styled = ReferenceStyles.apply(original, ReferenceStyle.LUXE_WALNUT_CHARCOAL)
        assertEquals(original.size, styled.units.size)
        original.zip(styled.units).forEach { (before, after) ->
            assertEquals(before.position, after.position)
            assertEquals(before.unit.catalogId, after.unit.catalogId)
            assertEquals(before.unit.widthMm, after.unit.widthMm)
            assertEquals(before.unit.heightMm, after.unit.heightMm)
            assertEquals(before.unit.appliance, after.unit.appliance)
        }
    }

    // ------------------------------------------------------- persistence

    @Test fun projectJsonRoundTripsCustomizationAndFinishes() {
        val styled = ReferenceStyles.apply(sampleUnits(), ReferenceStyle.CLASSIC_CREAM_SHAKER)
        val project = Project(
            id = "p1", name = "Styled",
            rooms = listOf(
                com.kitchendesigner.model.ProjectRoom(
                    id = "room-1", type = com.kitchendesigner.model.RoomType.KITCHEN,
                    room = Room(4_200, 3_600), units = styled.units, finishes = styled.finishes,
                ),
            ),
            schemaVersion = 2,
        )
        val loaded = ProjectJson.fromJson(ProjectJson.toJson(project))
        assertEquals(project.rooms[0].units, loaded.rooms[0].units)
        assertEquals(styled.finishes, loaded.rooms[0].finishes)
        assertEquals(DoorStyle.GLASS, loaded.units.first { it.unit.catalogId == "tall-600" }.unit.custom.doorStyle)
    }

    @Test fun projectJsonDefaultsCustomizationForPreV050Files() {
        val legacy = """
            {"v":2,"id":"old","name":"Old","schemaVersion":2,"activeRoomIndex":0,
             "rooms":[{"id":"room-1","name":"","type":"KITCHEN",
               "room":{"widthMm":4200,"depthMm":3600,"ceilingHeightMm":2700},
               "units":[{"catalogId":"base-600","type":"BASE","widthMm":600,"heightMm":860,
                         "depthMm":560,"materialId":"front-oak","xMm":0,"yMm":0,"rotationDeg":0,"isIsland":false}],
               "openings":[]}]}
        """.trimIndent()
        val loaded = ProjectJson.fromJson(legacy)
        assertEquals(UnitCustomization(), loaded.units.single().unit.custom)
        assertEquals(KitchenFinishes(), loaded.rooms[0].finishes)
    }

    // ----------------------------------------------------- render wiring

    private fun styledProject(): Project {
        val styled = ReferenceStyles.apply(sampleUnits(), ReferenceStyle.LUXE_WALNUT_CHARCOAL)
        return Project(
            id = "p1", name = "Styled",
            rooms = listOf(
                com.kitchendesigner.model.ProjectRoom(
                    id = "room-1", type = com.kitchendesigner.model.RoomType.KITCHEN,
                    room = Room(4_200, 3_600), units = styled.units, finishes = styled.finishes,
                ),
            ),
            schemaVersion = 2,
        )
    }

    @Test fun unityBridgePayloadCarriesCustomizationAndFinishes() {
        val json = StubUnityBridge().toJson(UnityCommand.LoadProject(styledProject()))
        assertTrue(json.contains("\"doorStyle\":\"GLASS\""))
        assertTrue(json.contains("\"interiorLed\":true"))
        assertTrue(json.contains("\"countertopMaterialId\":\"counter-marble-black-gold\""))
        assertTrue(json.contains("\"backsplashId\":\"backsplash-marble-black\""))
        assertTrue(json.contains("\"underCabinetLed\":true"))
        assertTrue(json.contains("\"pendantId\":\"pendant-copper-globe\""))
    }

    @Test fun cloudRenderPayloadCarriesCustomizationAndFinishes() {
        val request = RenderRequest(
            project = styledProject(),
            quality = RenderQuality.FINAL_4K,
            preset = RenderPreset.STANDARD,
        )
        val json = HttpCloudRenderClient("http://localhost:0").buildSubmitJson(request)
        assertTrue(json.contains("\"doorStyle\":\"GLASS\""))
        assertTrue(json.contains("\"handleStyle\":\"INTEGRATED\""))
        assertTrue(json.contains("\"countertopMaterialId\":\"counter-marble-black-gold\""))
        assertTrue(json.contains("\"coveLed\":false"))
    }
}
