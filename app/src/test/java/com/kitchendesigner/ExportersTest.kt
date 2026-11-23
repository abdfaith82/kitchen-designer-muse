package com.kitchendesigner

import com.kitchendesigner.bom.BomCalculator
import com.kitchendesigner.catalog.Catalog
import com.kitchendesigner.engine.NkbaValidator
import com.kitchendesigner.engine.KitchenPlanInput
import com.kitchendesigner.engine.RuleId
import com.kitchendesigner.engine.RuleResult
import com.kitchendesigner.engine.RuleStatus
import com.kitchendesigner.engine.ValidationReport
import com.kitchendesigner.export.ProjectReportExporter
import com.kitchendesigner.export.ShoppingListExporter
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.ProjectRoom
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.RoomType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportersTest {

    private fun placed(catalogId: String, x: Int = 0, y: Int = 0): PlacedUnit {
        val c = Catalog.cabinet(catalogId)
        return PlacedUnit(CabinetUnit(c.id, c.type, c.widthMm, c.heightMm, c.depthMm), PointMm(x, y))
    }

    // 2x base-600 (180.00 each) + 1x wall-600 (120.00); countertop runs over
    // BASE units only: 1.2 m of quartz at 320.00/m = 384.00. Total 864.00 USD.
    private val bomUnits = listOf(placed("base-600"), placed("base-600", x = 600), placed("wall-600"))

    @Test fun shoppingListCsvHasExactHeaderRowsAndTotal() {
        val bom = BomCalculator.calculate(bomUnits)
        val csv = ShoppingListExporter.toCsv(bom)
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals("item,qty,unit,unit_price,line_total,currency", lines[0])
        assertTrue(lines.contains("base-600,2,pc,180.00,360.00,USD"))
        assertTrue(lines.contains("wall-600,1,pc,120.00,120.00,USD"))
        assertTrue(lines.contains("counter-quartz,1.2,m,320.00,384.00,USD"))
        assertEquals("TOTAL,,,,864.00,USD", lines.last())
        assertEquals(5, lines.size)
    }

    @Test fun shoppingListCsvConvertsCurrency() {
        val bom = BomCalculator.calculate(bomUnits, currency = com.kitchendesigner.bom.CurrencySpec.SAR)
        val csv = ShoppingListExporter.toCsv(bom)
        // 864 USD * 3.75 = 3240.00 SAR; base unit 180 USD -> 675.00 SAR.
        assertTrue(csv.contains("base-600,2,pc,675.00,1350.00,SAR"))
        assertTrue(csv.trimEnd('\n').endsWith("TOTAL,,,,3240.00,SAR"))
    }

    @Test fun shoppingListCsvWithoutBaseUnitsHasNoCountertopRow() {
        val bom = BomCalculator.calculate(listOf(placed("wall-600")))
        val csv = ShoppingListExporter.toCsv(bom)
        assertFalse(csv.contains("counter-quartz"))
        assertTrue(csv.trimEnd('\n').endsWith("TOTAL,,,,120.00,USD"))
    }

    private fun fixtureProject(): Project {
        val kitchen = ProjectRoom(
            id = "room-1", type = RoomType.KITCHEN, room = Room(4_200, 3_600), name = "Main kitchen",
            units = listOf(placed("base-600")),
            openings = listOf(
                Opening(OpeningKind.DOOR, wallIndex = 1, offsetMm = 300, widthMm = 900),
                Opening(OpeningKind.WINDOW, wallIndex = 0, offsetMm = 1_200, widthMm = 1_200, sillHeightMm = 900),
            ),
        )
        val bedroom = ProjectRoom(id = "room-2", type = RoomType.BEDROOM, room = Room(3_600, 3_200))
        return Project(id = "p1", name = "Home", rooms = listOf(kitchen, bedroom), activeRoomIndex = 0, schemaVersion = 2)
    }

    @Test fun projectReportHasExactKeyLinesAndTotals() {
        // Kitchen room estimate: base-600 180.00 + countertop 0.6 m * 320.00 = 372.00.
        // Bedroom is empty: 0.00. Total 372.00 USD.
        val report = ValidationReport(
            listOf(
                RuleResult(RuleId.WORK_AISLE, RuleStatus.PASS, 1_200, 1_070),
                RuleResult(RuleId.TRIANGLE_TOTAL, RuleStatus.FAIL, 8_500, 3_960, 7_930),
            ),
        )
        val md = ProjectReportExporter.toMarkdown(fixtureProject(), validations = mapOf("room-1" to report))
        val lines = md.split('\n')
        assertEquals("# Project report: Home", lines[0])
        assertTrue(lines.contains("Rooms: 2"))
        assertTrue(lines.contains("## Room 1: KITCHEN — Main kitchen"))
        assertTrue(lines.contains("- Dimensions: 4200 x 3600 mm (ceiling 2700 mm)"))
        assertTrue(lines.contains("- Units: 1"))
        assertTrue(lines.contains("- Openings: 2"))
        assertTrue(lines.contains("  - DOOR on SOUTH wall: offset 300 mm, width 900 mm"))
        assertTrue(lines.contains("  - WINDOW on NORTH wall: offset 1200 mm, width 1200 mm, sill 900 mm"))
        assertTrue(lines.contains("- NKBA: 1/2 checks passed"))
        assertTrue(lines.contains("- Room estimate: 372.00 USD"))
        assertTrue(lines.contains("## Room 2: BEDROOM"))
        assertTrue(lines.contains("- NKBA: not evaluated"))
        assertEquals("## Total estimate: 372.00 USD", lines.last { it.isNotBlank() })
    }

    @Test fun projectReportWithoutValidationsMarksRoomsNotEvaluated() {
        val md = ProjectReportExporter.toMarkdown(fixtureProject())
        assertFalse(md.contains("checks passed"))
        assertEquals(2, md.split("- NKBA: not evaluated").size - 1)
        // Sanity: the validator-produced report shape also renders.
        val real = NkbaValidator.validate(KitchenPlanInput(triangle = null, workAisleMm = 1_200))
        val md2 = ProjectReportExporter.toMarkdown(fixtureProject(), mapOf("room-2" to real))
        assertTrue(md2.contains("- NKBA: 1/1 checks passed"))
    }
}
