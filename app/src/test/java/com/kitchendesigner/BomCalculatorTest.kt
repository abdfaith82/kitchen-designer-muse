package com.kitchendesigner

import com.kitchendesigner.bom.BomCalculator
import com.kitchendesigner.bom.CurrencySpec
import com.kitchendesigner.engine.LayoutGenerator
import com.kitchendesigner.engine.LayoutType
import com.kitchendesigner.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BomCalculatorTest {
    @Test fun bomGroupsUnitsAndCountsCountertop() {
        val g = LayoutGenerator.generate(Room(4_200, 3_600), LayoutType.SINGLE_WALL)
        val bom = BomCalculator.calculate(g.units)
        val totalQty = bom.items.sumOf { it.quantity }
        assertEquals(g.units.size, totalQty)
        // 6 base modules x 600 mm = 3.6 linear metres (the 7th module is the tall fridge housing, no countertop)
        assertEquals(3.6, bom.countertopLinearMetres, 0.001)
        assertTrue(bom.unitsCostCents > 0)
        assertTrue(bom.countertopCostCents > 0)
    }
    @Test fun currencyConversionAppliesRate() {
        val g = LayoutGenerator.generate(Room(3_600, 3_000), LayoutType.L_SHAPED)
        val usd = BomCalculator.calculate(g.units, currency = CurrencySpec.USD)
        val sar = BomCalculator.calculate(g.units, currency = CurrencySpec.SAR)
        assertEquals(usd.totalInCurrency * 3.75, sar.totalInCurrency, 0.01)
    }
    @Test fun emptyKitchenCostsNothing() {
        val bom = BomCalculator.calculate(emptyList())
        assertEquals(0.0, bom.totalInCurrency, 0.001)
        assertTrue(bom.items.isEmpty())
    }
}
