package com.kitchendesigner.export

import com.kitchendesigner.bom.BomCalculator
import com.kitchendesigner.bom.CurrencySpec
import com.kitchendesigner.engine.ValidationReport
import com.kitchendesigner.model.Project
import java.util.Locale

/**
 * Contractor project report export (Workstream D, v0.4.0): a plain
 * Markdown report of a whole [Project] — every room with its dimensions,
 * openings and unit count, the NKBA result for rooms the caller validated,
 * and a per-room + total estimate from [BomCalculator]. Suitable to paste
 * into a message or print for a contractor.
 *
 * NKBA reports are passed in by room id ([validations]) because a Project
 * does not store its work triangle; rooms without a report are marked
 * "not evaluated" rather than silently passed.
 */
object ProjectReportExporter {

    fun toMarkdown(
        project: Project,
        validations: Map<String, ValidationReport> = emptyMap(),
        currency: CurrencySpec = CurrencySpec.USD,
        countertopMaterialId: String = "counter-quartz",
    ): String {
        val sb = StringBuilder()
        sb.append("# Project report: ").append(project.name).append('\n')
        sb.append("Project ID: ").append(project.id).append('\n')
        sb.append("Rooms: ").append(project.rooms.size).append("\n\n")
        var total = 0.0
        project.rooms.forEachIndexed { i, room ->
            val bom = BomCalculator.calculate(room.units, countertopMaterialId, currency)
            total += bom.totalInCurrency
            sb.append("## Room ").append(i + 1).append(": ").append(room.type.name)
            if (room.name.isNotBlank()) sb.append(" — ").append(room.name)
            sb.append('\n')
            sb.append("- Dimensions: ").append(room.room.widthMm).append(" x ").append(room.room.depthMm)
                .append(" mm (ceiling ").append(room.room.ceilingHeightMm).append(" mm)\n")
            sb.append("- Units: ").append(room.units.size).append('\n')
            sb.append("- Openings: ").append(room.openings.size).append('\n')
            for (o in room.openings) {
                sb.append("  - ").append(o.kind.name).append(" on ")
                    .append(o.wallSide?.name ?: "UNKNOWN").append(" wall: offset ")
                    .append(o.offsetMm).append(" mm, width ").append(o.widthMm).append(" mm")
                if (o.kind.name == "WINDOW") sb.append(", sill ").append(o.sillHeightMm).append(" mm")
                sb.append('\n')
            }
            val report = validations[room.id]
            if (report == null) {
                sb.append("- NKBA: not evaluated\n")
            } else {
                val passed = report.results.count { it.passed }
                sb.append("- NKBA: ").append(passed).append('/').append(report.results.size)
                    .append(" checks passed\n")
            }
            sb.append("- Room estimate: ").append(money(bom.totalInCurrency)).append(' ')
                .append(currency.code).append("\n\n")
        }
        sb.append("## Total estimate: ").append(money(total)).append(' ').append(currency.code).append('\n')
        return sb.toString()
    }

    private fun money(amount: Double): String = String.format(Locale.US, "%.2f", amount)
}
