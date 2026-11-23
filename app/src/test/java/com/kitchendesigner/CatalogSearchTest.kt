package com.kitchendesigner

import com.kitchendesigner.catalog.CatalogExpansion
import com.kitchendesigner.catalog.CatalogQuery
import com.kitchendesigner.catalog.CatalogSearch
import com.kitchendesigner.catalog.ExpansionSku
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchTest {
    private val sample = listOf(
        ExpansionSku("CAB-BASE-600-H860-SLAB-OAK", "CABINET", "base", 600, 860, 560, "slab", "oak", 18_400, "GLOBAL", false, false),
        ExpansionSku("CAB-BASE-600-H860-SHAKER-HPL-GREY", "CABINET", "base", 600, 860, 560, "shaker", "hpl-grey", 18_500, "MENA", true, true),
        ExpansionSku("CAB-WARDROBE-1800-H2100-D4-SLAB-WALNUT", "CABINET", "wardrobe", 1_800, 2_100, 600, "slab", "walnut", 91_000, "GLOBAL", false, false),
        ExpansionSku("FRN-SOFA-2100-S3-FABRIC-GREY", "FURNITURE", null, 2_100, 850, 950, null, "fabric-grey", 222_000, "GLOBAL", false, false),
        ExpansionSku("FRN-SOFA-1500-S2-BOUCLE-WHITE", "FURNITURE", null, 1_500, 850, 950, null, "boucle-white", 193_200, "MENA", false, false),
        ExpansionSku("APP-OVEN-600-STAINLESS", "APPLIANCE", null, 600, 595, 567, null, "stainless", 69_000, "MENA", true, true),
        ExpansionSku("FLR-OAK-LAMINATE-190X1200", "FLOOR", null, 190, 8, 1_200, null, "oak-laminate", 2_500, "GLOBAL", false, false),
    )

    @Test fun blankQueryReturnsEverythingSortedBySku() {
        val all = CatalogSearch.search(sample, CatalogQuery())
        assertEquals(sample.size, all.size)
        assertEquals(all.map { it.sku }.sorted(), all.map { it.sku })
    }

    @Test fun freeTextMatchesSubtypeFinishAndCabinetKind() {
        assertEquals(2, CatalogSearch.search(sample, CatalogQuery(text = "sofa")).size)
        assertEquals(2, CatalogSearch.search(sample, CatalogQuery(text = "SOFA")).size) // case-insensitive
        assertEquals(
            listOf("CAB-BASE-600-H860-SLAB-OAK", "FLR-OAK-LAMINATE-190X1200"),
            CatalogSearch.search(sample, CatalogQuery(text = "oak")).map { it.sku },
        )
        assertEquals(1, CatalogSearch.search(sample, CatalogQuery(text = "wardrobe")).size)
        assertTrue(CatalogSearch.search(sample, CatalogQuery(text = "pool")).isEmpty())
    }

    @Test fun roomTypeFilterIncludesUntaggedFlooring() {
        val living = CatalogSearch.search(sample, CatalogQuery(roomType = CatalogExpansion.ROOM_LIVING))
        // 2 sofas + the untagged (ALL_ROOMS) floor, which suits any room.
        assertEquals(3, living.size)
        assertTrue(living.any { it.sku == "FLR-OAK-LAMINATE-190X1200" })
        val bedroom = CatalogSearch.search(sample, CatalogQuery(roomType = CatalogExpansion.ROOM_BEDROOM))
        assertEquals(listOf("CAB-WARDROBE-1800-H2100-D4-SLAB-WALNUT", "FLR-OAK-LAMINATE-190X1200"), bedroom.map { it.sku })
    }

    @Test fun categoryGroupAndKindFilters() {
        assertEquals(
            listOf("FRN-SOFA-1500-S2-BOUCLE-WHITE", "FRN-SOFA-2100-S3-FABRIC-GREY"),
            CatalogSearch.search(sample, CatalogQuery(categoryGroup = CatalogExpansion.GROUP_LIVING_FURNITURE)).map { it.sku },
        )
        assertEquals(3, CatalogSearch.search(sample, CatalogQuery(kind = CatalogExpansion.KIND_CABINET)).size)
    }

    @Test fun priceCapIsInclusive() {
        val cheap = CatalogSearch.search(sample, CatalogQuery(maxPriceUsdCents = 18_500))
        assertEquals(
            listOf("CAB-BASE-600-H860-SHAKER-HPL-GREY", "CAB-BASE-600-H860-SLAB-OAK", "FLR-OAK-LAMINATE-190X1200"),
            cheap.map { it.sku },
        )
    }

    @Test fun menaAndResistanceFlagsCombine() {
        assertEquals(
            listOf("APP-OVEN-600-STAINLESS", "CAB-BASE-600-H860-SHAKER-HPL-GREY"),
            CatalogSearch.search(sample, CatalogQuery(menaOnly = true, heatResistantOnly = true)).map { it.sku },
        )
        assertEquals(
            listOf("APP-OVEN-600-STAINLESS", "CAB-BASE-600-H860-SHAKER-HPL-GREY"),
            CatalogSearch.search(sample, CatalogQuery(humidityResistantOnly = true)).map { it.sku },
        )
        assertEquals(
            listOf("FRN-SOFA-1500-S2-BOUCLE-WHITE"),
            CatalogSearch.search(sample, CatalogQuery(text = "sofa", menaOnly = true)).map { it.sku },
        )
    }
}
