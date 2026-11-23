package com.kitchendesigner.bom

import com.kitchendesigner.catalog.Catalog
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.PlacedUnit
import kotlin.math.roundToLong

data class CurrencySpec(val code: String, val rateFromUsd: Double) {
    companion object {
        val USD = CurrencySpec("USD", 1.0)
        val SAR = CurrencySpec("SAR", 3.75)
        val AED = CurrencySpec("AED", 3.6725)
        val EGP = CurrencySpec("EGP", 48.5) // indicative only — refresh at pricing time
        val EUR = CurrencySpec("EUR", 0.92)
        val ALL = listOf(USD, SAR, AED, EGP, EUR)
    }
}

data class BomItem(val catalogId: String, val quantity: Int, val unitPriceCents: Long, val lineTotalCents: Long)
data class Bom(
    val items: List<BomItem>,
    val countertopLinearMetres: Double,
    val countertopCostCents: Long,
    val unitsCostCents: Long,
    /** Total converted into [currency], in that currency's major unit, 2dp rounding applied by caller display. */
    val totalInCurrency: Double,
    val currency: CurrencySpec,
)

/** Bill of materials + estimate. Countertop runs over BASE units (incl. island),
 *  priced per linear metre from the chosen countertop material. */
object BomCalculator {
    fun calculate(
        units: List<PlacedUnit>,
        countertopMaterialId: String = "counter-quartz",
        currency: CurrencySpec = CurrencySpec.USD,
    ): Bom {
        val grouped = units.groupBy { it.unit.catalogId }
        val items = grouped.map { (id, list) ->
            val price = Catalog.cabinet(id).priceUsdCents
            BomItem(id, list.size, price, price * list.size)
        }.sortedBy { it.catalogId }
        val unitsCost = items.sumOf { it.lineTotalCents }
        val countertopMm = units.filter { it.unit.type == CabinetType.BASE }.sumOf { it.unit.widthMm }
        val metres = countertopMm / 1_000.0
        val counterCost = (metres * Catalog.material(countertopMaterialId).pricePerLinearMetreUsdCents).roundToLong()
        val totalUsdCents = unitsCost + counterCost
        return Bom(items, metres, counterCost, unitsCost, totalUsdCents / 100.0 * currency.rateFromUsd, currency)
    }
}
