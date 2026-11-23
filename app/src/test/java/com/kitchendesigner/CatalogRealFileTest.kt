package com.kitchendesigner

import com.kitchendesigner.catalog.CatalogExpansion
import com.kitchendesigner.catalog.CatalogJson
import com.kitchendesigner.catalog.CatalogQuery
import com.kitchendesigner.catalog.CatalogSearch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification against the REAL generated asset (Workstream A, v0.4.0):
 * loads `catalog_expansion.json` from disk and proves the runtime parser
 * reproduces the generator's catalog exactly (3,169 SKUs).
 *
 * File location: the `catalog.json` system property wins; otherwise the
 * Gradle module-relative path (unit tests run with the module dir as the
 * working directory) and the repo-root-relative path are tried.
 */
class CatalogRealFileTest {

    private val skus by lazy {
        val candidates = listOfNotNull(
            System.getProperty("catalog.json"),
            "src/main/assets/catalog_expansion.json",
            "app/src/main/assets/catalog_expansion.json",
            "../app/src/main/assets/catalog_expansion.json",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "catalog_expansion.json not found; tried $candidates " +
                    "(set -Dcatalog.json=<path> in the standalone kotlinc run)",
            )
        CatalogJson.parse(file.readText())
    }

    @Test fun parsesTheWholeGeneratedCatalog() {
        assertEquals(3_169, skus.size)
        assertEquals(3_169, skus.map { it.sku }.toSet().size) // unique ids
    }

    @Test fun kindCountsMatchTheGenerator() {
        val counts = CatalogExpansion.countByKind(skus)
        assertEquals(2_316, counts[CatalogExpansion.KIND_CABINET])
        assertEquals(412, counts[CatalogExpansion.KIND_FURNITURE])
        assertEquals(132, counts[CatalogExpansion.KIND_FLOOR])
        assertEquals(120, counts[CatalogExpansion.KIND_WALL])
        assertEquals(114, counts[CatalogExpansion.KIND_FIXTURE])
        assertEquals(57, counts[CatalogExpansion.KIND_APPLIANCE])
        assertEquals(18, counts[CatalogExpansion.KIND_WORKTOP])
    }

    @Test fun regionCountsMatchTheGenerator() {
        assertEquals(1_647, CatalogExpansion.filterByRegion(skus, CatalogExpansion.REGION_MENA).size)
        assertEquals(1_522, CatalogExpansion.filterByRegion(skus, CatalogExpansion.REGION_GLOBAL).size)
    }

    @Test fun roomCountsMatchTheGenerator() {
        val counts = CatalogExpansion.countByRoomType(skus)
        assertEquals(1_737, counts[CatalogExpansion.ROOM_KITCHEN])
        assertEquals(534, counts[CatalogExpansion.ROOM_BEDROOM])
        assertEquals(486, counts[CatalogExpansion.ROOM_BATHROOM])
        assertEquals(240, counts[CatalogExpansion.ROOM_LIVING])
        assertEquals(160, counts[CatalogExpansion.ROOM_OFFICE])
        assertEquals(12, counts[CatalogExpansion.ROOM_ALL])
    }

    @Test fun spotCheckSpecificSkus() {
        val worktop = CatalogExpansion.lookup(skus, "WT-GRANITE-T20")
        assertNotNull(worktop)
        assertEquals("granite", worktop!!.finishId)
        assertEquals(28_000L, worktop.priceUsdCentsEstimate)
        assertEquals("MENA", worktop.region)
        assertTrue(worktop.heatResistant); assertTrue(worktop.humidityResistant)
        assertEquals(1_000, worktop.widthMm); assertEquals(20, worktop.heightMm)

        val dishwasher = CatalogExpansion.lookup(skus, "APP-DISHWASHER-450-BLACK")
        assertNotNull(dishwasher)
        assertEquals(45_700L, dishwasher!!.priceUsdCentsEstimate)
        assertEquals("MENA", dishwasher.region)
        assertTrue(dishwasher.heatResistant); assertFalse(dishwasher.humidityResistant)

        val base = CatalogExpansion.lookup(skus, "CAB-BASE-1000-H860-HANDLELESS-ACRYLIC-WHITE")
        assertNotNull(base)
        assertEquals("base", base!!.cabinetKind)
        assertEquals("handleless", base.doorStyle)
        assertEquals(28_800L, base.priceUsdCentsEstimate)

        assertEquals(378, CatalogExpansion.filterByCabinetKind(skus, "wardrobe").size)
    }

    @Test fun searchWorksOnTheRealCatalog() {
        // Substring search: "sofa" finds the 24 SOFA SKUs plus the 18
        // SOFACORNER SKUs (their ids contain "SOFA") — 42 in total.
        val sofas = CatalogSearch.search(skus, CatalogQuery(text = "sofa"))
        assertEquals(42, sofas.size)
        assertEquals(24, sofas.count { CatalogExpansion.subtypeOf(it) == "SOFA" })
        assertEquals(18, sofas.count { CatalogExpansion.subtypeOf(it) == "SOFACORNER" })
        assertTrue(sofas.all { CatalogExpansion.roomTypeOf(it) == CatalogExpansion.ROOM_LIVING })

        val menaHeat = CatalogSearch.search(skus, CatalogQuery(menaOnly = true, heatResistantOnly = true))
        assertTrue(menaHeat.isNotEmpty())
        assertTrue(menaHeat.all { it.region == "MENA" && it.heatResistant })

        val cheapKitchen = CatalogSearch.search(
            skus, CatalogQuery(roomType = CatalogExpansion.ROOM_KITCHEN, maxPriceUsdCents = 5_000),
        )
        assertTrue(cheapKitchen.isNotEmpty())
        assertTrue(cheapKitchen.all { it.priceUsdCentsEstimate <= 5_000 })
    }
}
