package com.kitchendesigner.engine

import com.kitchendesigner.model.WorkTriangle

/** Language-neutral rule ids: the UI maps each id to Arabic/English text and
 *  formats the measured/min/max numbers, so the engine itself has no strings. */
enum class RuleId { TRIANGLE_LEG_SINK_COOKTOP, TRIANGLE_LEG_COOKTOP_FRIDGE, TRIANGLE_LEG_FRIDGE_SINK, TRIANGLE_TOTAL, WORK_AISLE, WALKWAY }

enum class RuleStatus { PASS, FAIL }

data class RuleResult(
    val ruleId: RuleId, val status: RuleStatus, val measuredMm: Int,
    val minMm: Int? = null, val maxMm: Int? = null,
) { val passed: Boolean get() = status == RuleStatus.PASS }

data class ValidationReport(val results: List<RuleResult>) {
    val allPassed: Boolean get() = results.all { it.passed }
    val failures: List<RuleResult> get() = results.filter { !it.passed }
}

data class KitchenPlanInput(
    val triangle: WorkTriangle?,
    val workAisleMm: Int? = null,   // clear space between facing work runs
    val walkwayMm: Int? = null,     // clear non-work passage
    val twoCooks: Boolean = false,
)

/** NKBA kitchen planning rules (metric). Sources: NKBA guidelines summarised in
 *  the research report — triangle leg 4–9 ft (1.2–2.7 m), total 13–26 ft,
 *  work aisle 42 in (107 cm) one cook / 48 in (122 cm) two cooks, walkway 36 in. */
object NkbaValidator {
    const val TRIANGLE_LEG_MIN_MM = 1_200
    const val TRIANGLE_LEG_MAX_MM = 2_700
    const val TRIANGLE_TOTAL_MIN_MM = 3_960
    const val TRIANGLE_TOTAL_MAX_MM = 7_930
    const val WORK_AISLE_ONE_COOK_MM = 1_070
    const val WORK_AISLE_TWO_COOKS_MM = 1_220
    const val WALKWAY_MIN_MM = 915

    fun validate(input: KitchenPlanInput): ValidationReport {
        val results = mutableListOf<RuleResult>()
        input.triangle?.let { t ->
            fun leg(id: RuleId, v: Int) {
                results += RuleResult(
                    id, if (v in TRIANGLE_LEG_MIN_MM..TRIANGLE_LEG_MAX_MM) RuleStatus.PASS else RuleStatus.FAIL,
                    v, TRIANGLE_LEG_MIN_MM, TRIANGLE_LEG_MAX_MM)
            }
            leg(RuleId.TRIANGLE_LEG_SINK_COOKTOP, t.legSinkCooktopMm)
            leg(RuleId.TRIANGLE_LEG_COOKTOP_FRIDGE, t.legCooktopFridgeMm)
            leg(RuleId.TRIANGLE_LEG_FRIDGE_SINK, t.legFridgeSinkMm)
            results += RuleResult(RuleId.TRIANGLE_TOTAL,
                if (t.totalMm in TRIANGLE_TOTAL_MIN_MM..TRIANGLE_TOTAL_MAX_MM) RuleStatus.PASS else RuleStatus.FAIL,
                t.totalMm, TRIANGLE_TOTAL_MIN_MM, TRIANGLE_TOTAL_MAX_MM)
        }
        input.workAisleMm?.let { aisle ->
            val min = if (input.twoCooks) WORK_AISLE_TWO_COOKS_MM else WORK_AISLE_ONE_COOK_MM
            results += RuleResult(RuleId.WORK_AISLE, if (aisle >= min) RuleStatus.PASS else RuleStatus.FAIL, aisle, min, null)
        }
        input.walkwayMm?.let { w ->
            results += RuleResult(RuleId.WALKWAY, if (w >= WALKWAY_MIN_MM) RuleStatus.PASS else RuleStatus.FAIL, w, WALKWAY_MIN_MM, null)
        }
        return ValidationReport(results)
    }
}
