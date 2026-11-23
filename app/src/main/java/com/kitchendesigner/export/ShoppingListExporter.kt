package com.kitchendesigner.export

import com.kitchendesigner.bom.Bom
import java.util.Locale

/**
 * Shopping-list export (Workstream D, v0.4.0): turns a [Bom] into CSV text
 * a user can open in any spreadsheet or send to a supplier.
 *
 * Format (amounts in the BOM's currency, converted with the BOM's own rate;
 * numbers always Locale.US "%.2f" so the file is machine-stable):
 * ```
 * item,qty,unit,unit_price,line_total,currency
 * base-600,2,pc,180.00,360.00,USD
 * counter-quartz,1.2,m,320.00,384.00,USD
 * TOTAL,,,,864.00,USD
 * ```
 * The countertop is a single row priced per linear metre (qty in metres,
 * one decimal). Catalog ids contain no commas, so no CSV quoting is
 * needed; the TOTAL row's amount is the BOM total in its currency.
 */
object ShoppingListExporter {

    fun toCsv(bom: Bom, countertopMaterialId: String = "counter-quartz"): String {
        val rate = bom.currency.rateFromUsd
        val sb = StringBuilder()
        sb.append("item,qty,unit,unit_price,line_total,currency\n")
        for (item in bom.items) {
            sb.append(item.catalogId).append(',')
                .append(item.quantity).append(",pc,")
                .append(money(item.unitPriceCents * rate)).append(',')
                .append(money(item.lineTotalCents * rate)).append(',')
                .append(bom.currency.code).append('\n')
        }
        if (bom.countertopLinearMetres > 0.0) {
            val perMetreCents = bom.countertopCostCents / bom.countertopLinearMetres
            sb.append(countertopMaterialId).append(',')
                .append(String.format(Locale.US, "%.1f", bom.countertopLinearMetres)).append(",m,")
                .append(money(perMetreCents * rate)).append(',')
                .append(money(bom.countertopCostCents * rate)).append(',')
                .append(bom.currency.code).append('\n')
        }
        sb.append("TOTAL,,,,")
            .append(String.format(Locale.US, "%.2f", bom.totalInCurrency)).append(',')
            .append(bom.currency.code).append('\n')
        return sb.toString()
    }

    /** USD cents (already rate-multiplied by the caller) -> "123.45". */
    private fun money(centsTimesRate: Double): String =
        String.format(Locale.US, "%.2f", centsTimesRate / 100.0)
}
