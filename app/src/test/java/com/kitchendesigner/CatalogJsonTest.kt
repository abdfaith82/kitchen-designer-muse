package com.kitchendesigner

import com.kitchendesigner.catalog.CatalogJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogJsonTest {

    // A small document in the exact generator schema (schemaVersion 1),
    // including JSON nulls for cabinetKind/doorStyle/supplier.
    private val doc = """
        {"schemaVersion":1,"generatedBy":"scripts/generate_catalog.py","priceDisclaimer":"ESTIMATE",
         "skus":[
          {"sku":"CAB-BASE-600-H860-SLAB-OAK","kind":"CABINET","cabinetKind":"base","widthMm":600,
           "heightMm":860,"depthMm":560,"doorStyle":"slab","finishId":"oak",
           "priceUsdCentsEstimate":18400,"region":"GLOBAL","heatResistant":false,
           "humidityResistant":false,"supplier":null},
          {"sku":"APP-OVEN-600-STAINLESS","kind":"APPLIANCE","cabinetKind":null,"widthMm":600,
           "heightMm":595,"depthMm":567,"doorStyle":null,"finishId":"stainless",
           "priceUsdCentsEstimate":69000,"region":"MENA","heatResistant":true,
           "humidityResistant":true,"supplier":null}
         ]}
    """.trimIndent()

    @Test fun parsesAllFieldsIncludingNulls() {
        val skus = CatalogJson.parse(doc)
        assertEquals(2, skus.size)
        val cab = skus[0]
        assertEquals("CAB-BASE-600-H860-SLAB-OAK", cab.sku)
        assertEquals("CABINET", cab.kind)
        assertEquals("base", cab.cabinetKind)
        assertEquals(600, cab.widthMm); assertEquals(860, cab.heightMm); assertEquals(560, cab.depthMm)
        assertEquals("slab", cab.doorStyle)
        assertEquals("oak", cab.finishId)
        assertEquals(18_400L, cab.priceUsdCentsEstimate)
        assertEquals("GLOBAL", cab.region)
        assertFalse(cab.heatResistant); assertFalse(cab.humidityResistant)
        val app = skus[1]
        assertNull(app.cabinetKind); assertNull(app.doorStyle)
        assertEquals(69_000L, app.priceUsdCentsEstimate)
        assertTrue(app.heatResistant); assertTrue(app.humidityResistant)
    }

    @Test fun preservesFileOrder() {
        val skus = CatalogJson.parse(doc)
        assertEquals(listOf("CAB-BASE-600-H860-SLAB-OAK", "APP-OVEN-600-STAINLESS"), skus.map { it.sku })
    }

    @Test fun emptySkuArrayParses() {
        assertTrue(CatalogJson.parse("""{"schemaVersion":1,"skus":[]}""").isEmpty())
    }

    @Test fun missingSkusArrayThrows() {
        try {
            CatalogJson.parse("""{"schemaVersion":1}""")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("skus"))
        }
    }

    @Test fun malformedJsonThrows() {
        try {
            CatalogJson.parse("""{"skus":[{"sku":"X",""")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // strict parser rejects truncated input
        }
    }

    @Test fun missingRequiredFieldThrows() {
        val bad = """{"skus":[{"sku":"X-1","kind":"FLOOR","widthMm":100,"heightMm":8,"depthMm":100,
            "finishId":"oak","priceUsdCentsEstimate":100,"region":"GLOBAL"}]}"""
        // finishId present, but region IS present here; remove "kind" instead:
        val bad2 = bad.replace("\"kind\":\"FLOOR\",", "")
        try {
            CatalogJson.parse(bad2)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("kind"))
        }
    }
}
