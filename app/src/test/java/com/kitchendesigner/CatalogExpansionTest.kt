package com.kitchendesigner

import com.kitchendesigner.catalog.CatalogExpansion
import com.kitchendesigner.catalog.ExpansionSku
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogExpansionTest {
    // Small in-code sample mirroring the generated catalog_expansion.json
    // schema: 8 SKUs across all four kinds and both regions.
    private val sample = listOf(
        ExpansionSku("CAB-BASE-600-H860-SLAB-OAK", "CABINET", "base", 600, 860, 560, "slab", "oak", 18_400, "GLOBAL", false, false),
        ExpansionSku("CAB-BASE-600-H860-SHAKER-HPL-GREY", "CABINET", "base", 600, 860, 560, "shaker", "hpl-grey", 18_500, "MENA", true, true),
        ExpansionSku("CAB-WALL-600-H720-GLASS-ACRYLIC-WHITE", "CABINET", "wall", 600, 720, 320, "glass", "acrylic-white", 16_900, "MENA", false, true),
        ExpansionSku("CAB-TALL-FRIDGE-700-H2100-HANDLELESS-WALNUT", "CABINET", "tall-fridge", 700, 2_100, 650, "handleless", "walnut", 48_100, "GLOBAL", false, false),
        ExpansionSku("APP-OVEN-600-STAINLESS", "APPLIANCE", null, 600, 595, 567, null, "stainless", 69_000, "MENA", true, true),
        ExpansionSku("APP-FRIDGE-700-WHITE", "APPLIANCE", null, 700, 1_900, 650, null, "white", 104_000, "GLOBAL", false, false),
        ExpansionSku("WT-QUARTZ-T20", "WORKTOP", null, 1_000, 20, 600, null, "quartz", 32_000, "MENA", true, true),
        ExpansionSku("FLR-OAK-LAMINATE-190X1200", "FLOOR", null, 190, 8, 1_200, null, "oak-laminate", 2_500, "GLOBAL", false, false),
    )

    @Test fun countByKindGroupsSample() {
        val counts = CatalogExpansion.countByKind(sample)
        assertEquals(4, counts[CatalogExpansion.KIND_CABINET])
        assertEquals(2, counts[CatalogExpansion.KIND_APPLIANCE])
        assertEquals(1, counts[CatalogExpansion.KIND_WORKTOP])
        assertEquals(1, counts[CatalogExpansion.KIND_FLOOR])
        assertEquals(sample.size, counts.values.sum())
    }

    @Test fun countByCabinetKindExcludesNonCabinets() {
        val counts = CatalogExpansion.countByCabinetKind(sample)
        assertEquals(2, counts["base"])
        assertEquals(1, counts["wall"])
        assertEquals(1, counts["tall-fridge"])
        assertEquals(3, counts.size)
    }

    @Test fun lookupFindsExactSkuAndMissesUnknown() {
        val found = CatalogExpansion.lookup(sample, "WT-QUARTZ-T20")
        assertEquals("quartz", found?.finishId)
        assertEquals(32_000L, found?.priceUsdCentsEstimate)
        assertNull(CatalogExpansion.lookup(sample, "CAB-BASE-123-NOPE"))
    }

    @Test fun filterByRegionSplitsGlobalAndMena() {
        val mena = CatalogExpansion.filterByRegion(sample, CatalogExpansion.REGION_MENA)
        val global = CatalogExpansion.filterByRegion(sample, CatalogExpansion.REGION_GLOBAL)
        assertEquals(4, mena.size)
        assertEquals(4, global.size)
        assertTrue(mena.all { it.region == "MENA" })
        assertTrue(global.all { it.region == "GLOBAL" })
    }

    @Test fun menaHeatSafeRequiresMenaAndHeatResistance() {
        val safe = CatalogExpansion.menaHeatSafe(sample)
        assertEquals(
            setOf("CAB-BASE-600-H860-SHAKER-HPL-GREY", "APP-OVEN-600-STAINLESS", "WT-QUARTZ-T20"),
            safe.map { it.sku }.toSet(),
        )
        assertTrue(safe.all { it.region == "MENA" && it.heatResistant })
    }

    @Test fun menaHumiditySafeAndHeatAndHumiditySafe() {
        assertEquals(4, CatalogExpansion.menaHumiditySafe(sample).size)
        // hpl-grey cabinet, stainless oven, quartz worktop resist both.
        assertEquals(3, CatalogExpansion.heatAndHumiditySafe(sample).size)
    }

    @Test fun filterByCabinetKindWidthAndFinish() {
        assertEquals(2, CatalogExpansion.filterByCabinetKind(sample, "base").size)
        assertEquals(2, CatalogExpansion.filterByWidth(sample, "base", 600).size)
        assertTrue(CatalogExpansion.filterByWidth(sample, "base", 300).isEmpty())
        assertEquals(
            listOf("CAB-TALL-FRIDGE-700-H2100-HANDLELESS-WALNUT"),
            CatalogExpansion.filterByFinish(sample, "walnut").map { it.sku },
        )
        assertEquals(2, CatalogExpansion.filterByKind(sample, CatalogExpansion.KIND_APPLIANCE).size)
    }

    // Whole-home sample (Workstream C): 12 SKUs mirroring the new generated
    // categories — bathroom cabinets/fixtures, wardrobe, living/bedroom/
    // office furniture, room-tagged flooring and a wall finish.
    private val homeSample = listOf(
        ExpansionSku("CAB-BATH-VANITY-600-H860-SLAB-HPL-GREY", "CABINET", "bath-vanity", 600, 860, 520, "slab", "hpl-grey", 24_600, "MENA", true, true),
        ExpansionSku("CAB-BATH-TALL-400-H2100-SHAKER-OAK", "CABINET", "bath-tall", 400, 2_100, 350, "shaker", "oak", 27_600, "GLOBAL", false, false),
        ExpansionSku("CAB-WARDROBE-1800-H2100-D4-SLAB-WALNUT", "CABINET", "wardrobe", 1_800, 2_100, 600, "slab", "walnut", 91_000, "GLOBAL", false, false),
        ExpansionSku("FIX-MIRROR-600-H700-CHROME", "FIXTURE", null, 600, 700, 30, null, "chrome", 11_900, "MENA", true, true),
        ExpansionSku("FIX-SHOWER-900-H1950-CHROME", "FIXTURE", null, 900, 1_950, 800, null, "chrome", 69_300, "MENA", true, true),
        ExpansionSku("FRN-SOFA-2100-S3-FABRIC-GREY", "FURNITURE", null, 2_100, 850, 950, null, "fabric-grey", 222_000, "GLOBAL", false, false),
        ExpansionSku("FRN-TVUNIT-1800-H450-D400-OAK", "FURNITURE", null, 1_800, 450, 400, null, "oak", 94_300, "GLOBAL", false, false),
        ExpansionSku("FRN-BED-1600X2000-H1100-OAK", "FURNITURE", null, 1_600, 1_100, 2_000, null, "oak", 126_500, "GLOBAL", false, false),
        ExpansionSku("FRN-DESK-1400-H750-D700-WALNUT", "FURNITURE", null, 1_400, 750, 700, null, "walnut", 71_500, "GLOBAL", false, false),
        ExpansionSku("FRN-BOOKCASE-800-H1800-D300-OAK", "FURNITURE", null, 800, 1_800, 300, null, "oak", 29_900, "GLOBAL", false, false),
        ExpansionSku("FLR-PORCELAIN-600X600-BATHROOM", "FLOOR", null, 600, 10, 600, null, "porcelain", 6_000, "MENA", true, true),
        ExpansionSku("WAL-PAINT-BEDROOM-IVORY", "WALL", null, 1_000, 1, 1_000, null, "ivory", 2_200, "MENA", false, true),
    )

    @Test fun roomTypeOfDerivesWholeHomeRooms() {
        val bySku = homeSample.associateBy { it.sku }
        assertEquals(CatalogExpansion.ROOM_BATHROOM, CatalogExpansion.roomTypeOf(bySku.getValue("CAB-BATH-VANITY-600-H860-SLAB-HPL-GREY")))
        assertEquals(CatalogExpansion.ROOM_BATHROOM, CatalogExpansion.roomTypeOf(bySku.getValue("CAB-BATH-TALL-400-H2100-SHAKER-OAK")))
        assertEquals(CatalogExpansion.ROOM_BEDROOM, CatalogExpansion.roomTypeOf(bySku.getValue("CAB-WARDROBE-1800-H2100-D4-SLAB-WALNUT")))
        assertEquals(CatalogExpansion.ROOM_BATHROOM, CatalogExpansion.roomTypeOf(bySku.getValue("FIX-MIRROR-600-H700-CHROME")))
        assertEquals(CatalogExpansion.ROOM_BATHROOM, CatalogExpansion.roomTypeOf(bySku.getValue("FIX-SHOWER-900-H1950-CHROME")))
        assertEquals(CatalogExpansion.ROOM_LIVING, CatalogExpansion.roomTypeOf(bySku.getValue("FRN-SOFA-2100-S3-FABRIC-GREY")))
        assertEquals(CatalogExpansion.ROOM_LIVING, CatalogExpansion.roomTypeOf(bySku.getValue("FRN-TVUNIT-1800-H450-D400-OAK")))
        assertEquals(CatalogExpansion.ROOM_BEDROOM, CatalogExpansion.roomTypeOf(bySku.getValue("FRN-BED-1600X2000-H1100-OAK")))
        assertEquals(CatalogExpansion.ROOM_OFFICE, CatalogExpansion.roomTypeOf(bySku.getValue("FRN-DESK-1400-H750-D700-WALNUT")))
        assertEquals(CatalogExpansion.ROOM_OFFICE, CatalogExpansion.roomTypeOf(bySku.getValue("FRN-BOOKCASE-800-H1800-D300-OAK")))
        assertEquals(CatalogExpansion.ROOM_BATHROOM, CatalogExpansion.roomTypeOf(bySku.getValue("FLR-PORCELAIN-600X600-BATHROOM")))
        assertEquals(CatalogExpansion.ROOM_BEDROOM, CatalogExpansion.roomTypeOf(bySku.getValue("WAL-PAINT-BEDROOM-IVORY")))
        // Original kitchen sample still derives as before; untagged floor is ALL_ROOMS.
        assertEquals(CatalogExpansion.ROOM_KITCHEN, CatalogExpansion.roomTypeOf(sample[0]))
        assertEquals(CatalogExpansion.ROOM_KITCHEN, CatalogExpansion.roomTypeOf(sample[4])) // oven
        assertEquals(CatalogExpansion.ROOM_KITCHEN, CatalogExpansion.roomTypeOf(sample[6])) // worktop
        assertEquals(CatalogExpansion.ROOM_ALL, CatalogExpansion.roomTypeOf(sample[7])) // untagged floor
    }

    @Test fun countByRoomTypeGroupsHomeSample() {
        val counts = CatalogExpansion.countByRoomType(homeSample)
        assertEquals(5, counts[CatalogExpansion.ROOM_BATHROOM])
        assertEquals(2, counts[CatalogExpansion.ROOM_LIVING])
        assertEquals(3, counts[CatalogExpansion.ROOM_BEDROOM])
        assertEquals(2, counts[CatalogExpansion.ROOM_OFFICE])
        assertEquals(homeSample.size, counts.values.sum())
    }

    @Test fun filterByRoomTypeIncludesAllRoomsItems() {
        assertEquals(5, CatalogExpansion.filterByRoomType(homeSample, CatalogExpansion.ROOM_BATHROOM).size)
        val combined = sample + homeSample
        // Kitchen: 4 cabinets + 2 appliances + 1 worktop from the original
        // sample, plus the untagged (ALL_ROOMS) floor surfaced per room.
        val kitchen = CatalogExpansion.filterByRoomType(combined, CatalogExpansion.ROOM_KITCHEN)
        assertEquals(8, kitchen.size)
        assertTrue(kitchen.any { it.sku == "FLR-OAK-LAMINATE-190X1200" })
        // Querying ALL_ROOMS returns only the untagged items.
        assertEquals(
            listOf("FLR-OAK-LAMINATE-190X1200"),
            CatalogExpansion.filterByRoomType(combined, CatalogExpansion.ROOM_ALL).map { it.sku },
        )
    }

    @Test fun categoryGroupsClassifyHomeSample() {
        val counts = CatalogExpansion.countByCategoryGroup(homeSample)
        assertEquals(2, counts[CatalogExpansion.GROUP_BATHROOM_CABINETS])
        assertEquals(2, counts[CatalogExpansion.GROUP_BATHROOM_FIXTURES])
        assertEquals(1, counts[CatalogExpansion.GROUP_BEDROOM_WARDROBES])
        assertEquals(2, counts[CatalogExpansion.GROUP_LIVING_FURNITURE])
        assertEquals(1, counts[CatalogExpansion.GROUP_BEDROOM_FURNITURE])
        assertEquals(2, counts[CatalogExpansion.GROUP_OFFICE_FURNITURE])
        assertEquals(1, counts[CatalogExpansion.GROUP_FLOORING])
        assertEquals(1, counts[CatalogExpansion.GROUP_WALL_FINISHES])
        assertEquals(homeSample.size, counts.values.sum())
        assertEquals(
            setOf("FIX-MIRROR-600-H700-CHROME", "FIX-SHOWER-900-H1950-CHROME"),
            CatalogExpansion.filterByCategoryGroup(homeSample, CatalogExpansion.GROUP_BATHROOM_FIXTURES).map { it.sku }.toSet(),
        )
        // Original sample groups: cabinets/appliances/worktop/floor.
        val kitchenCounts = CatalogExpansion.countByCategoryGroup(sample)
        assertEquals(4, kitchenCounts[CatalogExpansion.GROUP_KITCHEN_CABINETS])
        assertEquals(2, kitchenCounts[CatalogExpansion.GROUP_KITCHEN_APPLIANCES])
        assertEquals(1, kitchenCounts[CatalogExpansion.GROUP_KITCHEN_WORKTOPS])
        assertEquals(1, kitchenCounts[CatalogExpansion.GROUP_FLOORING])
    }

    @Test fun filterBySubtypeFindsFurnitureAndFixtures() {
        assertEquals(listOf("FRN-SOFA-2100-S3-FABRIC-GREY"),
            CatalogExpansion.filterBySubtype(homeSample, "SOFA").map { it.sku })
        assertEquals(listOf("FIX-MIRROR-600-H700-CHROME"),
            CatalogExpansion.filterBySubtype(homeSample, "MIRROR").map { it.sku })
        assertEquals("DESK", CatalogExpansion.subtypeOf(homeSample[8]))
        assertTrue(CatalogExpansion.filterBySubtype(homeSample, "POOL").isEmpty())
    }

    @Test fun emptyListBehaves() {
        assertTrue(CatalogExpansion.countByKind(emptyList()).isEmpty())
        assertNull(CatalogExpansion.lookup(emptyList(), "WT-QUARTZ-T20"))
        assertTrue(CatalogExpansion.menaHeatSafe(emptyList()).isEmpty())
        assertTrue(CatalogExpansion.countByRoomType(emptyList()).isEmpty())
        assertTrue(CatalogExpansion.countByCategoryGroup(emptyList()).isEmpty())
        assertTrue(CatalogExpansion.filterByRoomType(emptyList(), CatalogExpansion.ROOM_BATHROOM).isEmpty())
    }
}
