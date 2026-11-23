package com.kitchendesigner

import com.kitchendesigner.assistant.BudgetTier
import com.kitchendesigner.assistant.DesignAssistant
import com.kitchendesigner.assistant.StylePreference
import com.kitchendesigner.catalog.ExpansionSku
import com.kitchendesigner.engine.LayoutType
import com.kitchendesigner.model.RoomType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignAssistantTest {

    private fun kitchen(w: Int, d: Int, style: StylePreference = StylePreference.MODERN, budget: BudgetTier = BudgetTier.STANDARD) =
        DesignAssistant.recommend(
            com.kitchendesigner.assistant.AssistantRequest(w, d, RoomType.KITCHEN, style, budget),
        )

    @Test fun smallKitchenGetsSingleWallAndNoIsland() {
        val rec = kitchen(2_000, 3_000)
        assertEquals(LayoutType.SINGLE_WALL, rec.layoutType)
        assertNotNull(rec.project)
        assertTrue(rec.project!!.units.none { it.isIsland })
        assertTrue(rec.notes.contains("LAYOUT_SINGLE_WALL"))
    }

    @Test fun largeKitchenGetsIsland() {
        val rec = kitchen(5_000, 4_200)
        assertEquals(LayoutType.ISLAND, rec.layoutType)
        assertTrue(rec.project!!.units.any { it.isIsland })
    }

    @Test fun layoutRulesCoverGalleyAndUAndL() {
        assertEquals(LayoutType.GALLEY, DesignAssistant.recommendLayout(4_200, 2_600))
        assertEquals(LayoutType.U_SHAPED, DesignAssistant.recommendLayout(3_400, 3_300))
        assertEquals(LayoutType.L_SHAPED, DesignAssistant.recommendLayout(3_000, 3_600))
        assertEquals(LayoutType.SINGLE_WALL, DesignAssistant.recommendLayout(1_800, 1_800))
    }

    @Test fun kitchenRecommendationHasNkbaAndBom() {
        val rec = kitchen(4_200, 3_600)
        assertNotNull(rec.nkbaReport)
        assertTrue(rec.nkbaReport!!.results.isNotEmpty())
        assertNotNull(rec.bom)
        assertTrue(rec.bom!!.totalInCurrency > 0.0)
        assertTrue(rec.furniturePicks.isEmpty())
        // Palette finish is applied to the generated units.
        assertTrue(rec.project!!.units.all { it.unit.materialId == rec.palette.cabinetFinishId })
    }

    @Test fun stylePalettesDiffer() {
        val modern = DesignAssistant.paletteFor(StylePreference.MODERN, BudgetTier.STANDARD)
        val classic = DesignAssistant.paletteFor(StylePreference.CLASSIC, BudgetTier.STANDARD)
        val scandi = DesignAssistant.paletteFor(StylePreference.SCANDINAVIAN, BudgetTier.STANDARD)
        val mena = DesignAssistant.paletteFor(StylePreference.MENA_TRADITIONAL, BudgetTier.STANDARD)
        val industrial = DesignAssistant.paletteFor(StylePreference.INDUSTRIAL, BudgetTier.STANDARD)
        assertEquals(5, setOf(modern, classic, scandi, mena, industrial).size)
        assertEquals("handleless", modern.doorStyle)
        assertEquals("walnut", classic.cabinetFinishId)
        assertEquals("terracotta", mena.wallFinishId)
        assertEquals("matte-black", industrial.cabinetFinishId)
    }

    @Test fun budgetTierChangesFinishSelection() {
        val economy = DesignAssistant.paletteFor(StylePreference.CLASSIC, BudgetTier.ECONOMY)
        val standard = DesignAssistant.paletteFor(StylePreference.CLASSIC, BudgetTier.STANDARD)
        val premium = DesignAssistant.paletteFor(StylePreference.CLASSIC, BudgetTier.PREMIUM)
        assertEquals("hpl-grey", economy.cabinetFinishId)
        assertEquals("laminate", economy.worktopFinishId)
        assertEquals("walnut", standard.cabinetFinishId)
        assertEquals("granite", standard.worktopFinishId)
        assertEquals("marble-look", premium.worktopFinishId)
        assertEquals("marble-tile", premium.floorFinishId)
        assertNotEquals(economy, standard); assertNotEquals(standard, premium)
        // The recommendation carries the budget-adjusted palette.
        val rec = kitchen(4_200, 3_600, StylePreference.CLASSIC, BudgetTier.ECONOMY)
        assertEquals("hpl-grey", rec.palette.cabinetFinishId)
        assertTrue(rec.project!!.units.all { it.unit.materialId == "hpl-grey" })
    }

    // Non-kitchen sample catalog (mirrors the generated schema).
    private val homeCatalog = listOf(
        ExpansionSku("FRN-SOFA-2100-S3-FABRIC-GREY", "FURNITURE", null, 2_100, 850, 950, null, "fabric-grey", 222_000, "GLOBAL", false, false),
        ExpansionSku("FRN-SOFA-1500-S2-BOUCLE-WHITE", "FURNITURE", null, 1_500, 850, 950, null, "boucle-white", 193_200, "MENA", false, false),
        ExpansionSku("FRN-TVUNIT-1800-H450-D400-OAK", "FURNITURE", null, 1_800, 450, 400, null, "oak", 94_300, "GLOBAL", false, false),
        ExpansionSku("FRN-COFFEE-900-H400-D600-WALNUT", "FURNITURE", null, 900, 400, 600, null, "walnut", 38_000, "GLOBAL", false, false),
        ExpansionSku("FRN-BED-1600X2000-H1100-OAK", "FURNITURE", null, 1_600, 1_100, 2_000, null, "oak", 126_500, "GLOBAL", false, false),
        ExpansionSku("CAB-WARDROBE-1800-H2100-D4-SLAB-WALNUT", "CABINET", "wardrobe", 1_800, 2_100, 600, "slab", "walnut", 91_000, "GLOBAL", false, false),
        ExpansionSku("CAB-BATH-VANITY-600-H860-SLAB-HPL-GREY", "CABINET", "bath-vanity", 600, 860, 520, "slab", "hpl-grey", 24_600, "MENA", true, true),
        ExpansionSku("FIX-MIRROR-600-H700-CHROME", "FIXTURE", null, 600, 700, 30, null, "chrome", 11_900, "MENA", true, true),
    )

    @Test fun livingRoomGetsFurnitureSetAndNoKitchenOutputs() {
        val rec = DesignAssistant.recommend(
            com.kitchendesigner.assistant.AssistantRequest(4_500, 4_000, RoomType.LIVING, StylePreference.SCANDINAVIAN, BudgetTier.STANDARD),
            homeCatalog,
        )
        assertNull(rec.layoutType); assertNull(rec.project); assertNull(rec.bom); assertNull(rec.nkbaReport)
        val subtypes = rec.furniturePicks.map { com.kitchendesigner.catalog.CatalogExpansion.subtypeOf(it) }.toSet()
        assertEquals(setOf("SOFA", "TVUNIT", "COFFEE"), subtypes)
        // Widest fitting sofa in a 4500 mm room is the 2100 one.
        assertTrue(rec.furniturePicks.any { it.sku == "FRN-SOFA-2100-S3-FABRIC-GREY" })
    }

    @Test fun economyLivingRoomPicksCheapestFittingSofa() {
        val rec = DesignAssistant.recommend(
            com.kitchendesigner.assistant.AssistantRequest(4_500, 4_000, RoomType.LIVING, StylePreference.MODERN, BudgetTier.ECONOMY),
            homeCatalog,
        )
        assertTrue(rec.furniturePicks.any { it.sku == "FRN-SOFA-1500-S2-BOUCLE-WHITE" })
    }

    @Test fun bedroomAndBathroomPaths() {
        val bedroom = DesignAssistant.recommend(
            com.kitchendesigner.assistant.AssistantRequest(4_000, 3_600, RoomType.BEDROOM, StylePreference.CLASSIC, BudgetTier.STANDARD),
            homeCatalog,
        )
        assertTrue(bedroom.furniturePicks.any { it.sku == "FRN-BED-1600X2000-H1100-OAK" })
        assertTrue(bedroom.furniturePicks.any { it.cabinetKind == "wardrobe" })

        val bathroom = DesignAssistant.recommend(
            com.kitchendesigner.assistant.AssistantRequest(2_400, 2_000, RoomType.BATHROOM, StylePreference.MODERN, BudgetTier.STANDARD),
            homeCatalog,
        )
        assertTrue(bathroom.furniturePicks.any { it.cabinetKind == "bath-vanity" })
        assertTrue(bathroom.furniturePicks.any { it.sku == "FIX-MIRROR-600-H700-CHROME" })
    }

    @Test fun nonKitchenWithEmptyCatalogPicksNothing() {
        val rec = DesignAssistant.recommend(
            com.kitchendesigner.assistant.AssistantRequest(4_000, 3_600, RoomType.OFFICE, StylePreference.MODERN, BudgetTier.STANDARD),
        )
        assertTrue(rec.furniturePicks.isEmpty())
        assertNull(rec.project)
    }
}
