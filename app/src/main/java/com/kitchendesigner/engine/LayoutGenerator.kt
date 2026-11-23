package com.kitchendesigner.engine

import com.kitchendesigner.catalog.Catalog
import com.kitchendesigner.model.ApplianceType
import com.kitchendesigner.model.CabinetType
import com.kitchendesigner.model.CabinetUnit
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.PointMm
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.WorkTriangle

enum class LayoutType { SINGLE_WALL, GALLEY, L_SHAPED, U_SHAPED, ISLAND }

data class GeneratedLayout(
    val type: LayoutType,
    val units: List<PlacedUnit>,
    val triangle: WorkTriangle,
    /** Clear aisle between facing runs, or null when the layout has no facing runs. */
    val workAisleMm: Int?,
)

/** Generates a kitchen in 600 mm standard modules.
 *  Appliance order along the main run is fixed: fridge (tall, at the start),
 *  sink, then cooktop — so the work triangle is deterministic and testable. */
object LayoutGenerator {
    private const val M = Catalog.MODULE_MM // 600
    private const val BASE_DEPTH = 560
    private const val RUN_Y_NORTH_OFFSET = BASE_DEPTH // run sits against the north wall

    fun generate(room: Room, type: LayoutType): GeneratedLayout {
        val units = mutableListOf<PlacedUnit>()
        var sink = PointMm(0, 0); var cooktop = PointMm(0, 0); var fridge = PointMm(0, 0)

        // Main run along the NORTH wall, running west -> east at y = depth - depth(560).
        val northY = room.depthMm - BASE_DEPTH
        val main = buildRun(lengthMm = room.widthMm, start = PointMm(0, northY), dx = 1, dy = 0, rotation = 180)
        units += main.units; sink = main.sink; cooktop = main.cooktop; fridge = main.fridge
        // Matching wall cabinets above the main run (skip above sink/cooktop positions is a v2 detail).
        units += wallUnitsAbove(main.units)

        var aisle: Int? = null
        when (type) {
            LayoutType.SINGLE_WALL -> Unit
            LayoutType.GALLEY -> {
                val south = plainRun(lengthMm = room.widthMm, start = PointMm(0, 0), dx = 1, dy = 0, rotation = 0)
                units += south; units += wallUnitsAbove(south, yOverride = 320)
                aisle = room.depthMm - BASE_DEPTH * 2
            }
            LayoutType.L_SHAPED -> {
                // West run, south -> north, stopping before the north run.
                units += plainRun(lengthMm = room.depthMm - BASE_DEPTH, start = PointMm(0, 0), dx = 0, dy = 1, rotation = 90)
            }
            LayoutType.U_SHAPED -> {
                units += plainRun(lengthMm = room.depthMm - BASE_DEPTH, start = PointMm(0, 0), dx = 0, dy = 1, rotation = 90)
                val eastX = room.widthMm - BASE_DEPTH
                units += plainRun(lengthMm = room.depthMm - BASE_DEPTH, start = PointMm(eastX, 0), dx = 0, dy = 1, rotation = 270)
                aisle = room.widthMm - BASE_DEPTH * 2
            }
            LayoutType.ISLAND -> {
                // Island parallel to the main run, centred, if the room is deep enough for a 1070 aisle.
                val islandY = northY - NkbaValidator.WORK_AISLE_ONE_COOK_MM - M
                if (islandY >= 0) {
                    val islandLength = minOf(room.widthMm - 1_200, M * 3).coerceAtLeast(M)
                    val islandX = (room.widthMm - islandLength) / 2
                    var x = islandX
                    repeat(islandLength / M) {
                        units += PlacedUnit(unit("base-600"), PointMm(x, islandY), 0, isIsland = true); x += M
                    }
                    aisle = northY - (islandY + M)
                }
            }
        }
        return GeneratedLayout(type, units, WorkTriangle(sink, cooktop, fridge), aisle)
    }

    private data class RunResult(val units: List<PlacedUnit>, val sink: PointMm, val cooktop: PointMm, val fridge: PointMm)

    /** Main run with fridge/sink/cooktop placed by module index. */
    private fun buildRun(lengthMm: Int, start: PointMm, dx: Int, dy: Int, rotation: Int): RunResult {
        val modules = (lengthMm / M).coerceAtLeast(3)
        val out = mutableListOf<PlacedUnit>()
        var sink = PointMm(0, 0); var cooktop = PointMm(0, 0); var fridge = PointMm(0, 0)
        val sinkIdx = modules / 3; val cookIdx = (modules * 2) / 3
        for (i in 0 until modules) {
            val pos = PointMm(start.xMm + dx * i * M, start.yMm + dy * i * M)
            val centre = PointMm(pos.xMm + M / 2, pos.yMm + BASE_DEPTH / 2)
            when (i) {
                0 -> { out += PlacedUnit(unit("tall-fridge-700", ApplianceType.FRIDGE), pos, rotation); fridge = centre }
                sinkIdx -> { out += PlacedUnit(unit("base-sink-600", ApplianceType.SINK), pos, rotation); sink = centre }
                cookIdx -> { out += PlacedUnit(unit("base-cooktop-600", ApplianceType.COOKTOP), pos, rotation); cooktop = centre }
                else -> out += PlacedUnit(unit("base-600"), pos, rotation)
            }
        }
        return RunResult(out, sink, cooktop, fridge)
    }

    private fun plainRun(lengthMm: Int, start: PointMm, dx: Int, dy: Int, rotation: Int): List<PlacedUnit> {
        val modules = (lengthMm / M).coerceAtLeast(0)
        return (0 until modules).map { i ->
            PlacedUnit(unit("base-600"), PointMm(start.xMm + dx * i * M, start.yMm + dy * i * M), rotation)
        }
    }

    private fun wallUnitsAbove(run: List<PlacedUnit>, yOverride: Int? = null): List<PlacedUnit> =
        run.filter { it.unit.type == CabinetType.BASE && it.unit.appliance == null }.map {
            PlacedUnit(unit("wall-600"), PointMm(it.position.xMm, yOverride ?: it.position.yMm), it.rotationDeg)
        }

    private fun unit(catalogId: String, appliance: ApplianceType? = null): CabinetUnit {
        val c = Catalog.cabinet(catalogId)
        return CabinetUnit(c.id, c.type, c.widthMm, c.heightMm, c.depthMm, appliance = appliance)
    }
}
