package com.kitchendesigner

import com.kitchendesigner.engine.KitchenPlanInput
import com.kitchendesigner.engine.NkbaValidator
import com.kitchendesigner.engine.RuleId
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.WorkTriangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NkbaValidatorTest {
    @Test fun goodTrianglePasses() {
        // legs 1800, 1800, ~2545 ; total ~6145 — inside all NKBA ranges
        val t = WorkTriangle(PointMm(0, 0), PointMm(1_800, 0), PointMm(1_800, 1_800))
        val report = NkbaValidator.validate(KitchenPlanInput(triangle = t, workAisleMm = 1_200))
        assertTrue(report.allPassed)
        assertEquals(5, report.results.size)
    }
    @Test fun crampedTriangleFailsLegsAndTotal() {
        val t = WorkTriangle(PointMm(0, 0), PointMm(600, 0), PointMm(600, 600))
        val report = NkbaValidator.validate(KitchenPlanInput(triangle = t))
        assertFalse(report.allPassed)
        assertTrue(report.failures.any { it.ruleId == RuleId.TRIANGLE_TOTAL })
        assertTrue(report.failures.any { it.ruleId == RuleId.TRIANGLE_LEG_SINK_COOKTOP })
    }
    @Test fun aisleRulesRespectCookCount() {
        val one = NkbaValidator.validate(KitchenPlanInput(triangle = null, workAisleMm = 1_100, twoCooks = false))
        assertTrue(one.allPassed)
        val two = NkbaValidator.validate(KitchenPlanInput(triangle = null, workAisleMm = 1_100, twoCooks = true))
        assertFalse(two.allPassed) // 1100 < 1220 for two cooks
        val walk = NkbaValidator.validate(KitchenPlanInput(triangle = null, walkwayMm = 800))
        assertFalse(walk.allPassed) // 800 < 915
    }
    @Test fun oversizedLegFails() {
        val t = WorkTriangle(PointMm(0, 0), PointMm(3_000, 0), PointMm(1_500, 1_500))
        val report = NkbaValidator.validate(KitchenPlanInput(triangle = t))
        assertTrue(report.failures.any { it.ruleId == RuleId.TRIANGLE_LEG_SINK_COOKTOP })
    }
}
