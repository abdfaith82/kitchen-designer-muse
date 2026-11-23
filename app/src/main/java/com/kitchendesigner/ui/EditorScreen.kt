package com.kitchendesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitchendesigner.R
import com.kitchendesigner.bom.BomCalculator
import com.kitchendesigner.bom.CurrencySpec
import com.kitchendesigner.engine.KitchenPlanInput
import com.kitchendesigner.engine.LayoutGenerator
import com.kitchendesigner.engine.LayoutType
import com.kitchendesigner.engine.NkbaValidator
import com.kitchendesigner.engine.RuleId
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PlacedUnit
import com.kitchendesigner.model.Project
import com.kitchendesigner.model.ProjectRoom
import com.kitchendesigner.model.Room
import com.kitchendesigner.model.RoomType
import com.kitchendesigner.model.SwingDirection
import com.kitchendesigner.model.WallSide
import java.util.Locale

/** Simple editor: pick a layout, type room size, and the real core logic
 *  (LayoutGenerator + NkbaValidator + BomCalculator) updates live. */
@Composable
fun EditorScreen() {
    var widthText by remember { mutableStateOf("4200") }
    var depthText by remember { mutableStateOf("3600") }
    var layoutType by remember { mutableStateOf(LayoutType.L_SHAPED) }
    var currency by remember { mutableStateOf(CurrencySpec.SAR) }

    val width = widthText.toIntOrNull()?.coerceIn(1_800, 12_000) ?: 4_200
    val depth = depthText.toIntOrNull()?.coerceIn(1_800, 12_000) ?: 3_600

    val generated = remember(width, depth, layoutType) { LayoutGenerator.generate(Room(width, depth), layoutType) }
    val report = remember(generated) {
        NkbaValidator.validate(KitchenPlanInput(triangle = generated.triangle, workAisleMm = generated.workAisleMm))
    }
    val bom = remember(generated, currency) { BomCalculator.calculate(generated.units, currency = currency) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.editor_title), style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(widthText, { widthText = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.room_width)) }, modifier = Modifier.weight(1f))
            OutlinedTextField(depthText, { depthText = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.room_depth)) }, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.layout_label))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            LayoutType.entries.forEach { t ->
                val label = stringResource(when (t) {
                    LayoutType.SINGLE_WALL -> R.string.layout_single_wall
                    LayoutType.GALLEY -> R.string.layout_galley
                    LayoutType.L_SHAPED -> R.string.layout_l_shaped
                    LayoutType.U_SHAPED -> R.string.layout_u_shaped
                    LayoutType.ISLAND -> R.string.layout_island
                })
                if (t == layoutType) Button(onClick = { layoutType = t }) { Text(label) }
                else OutlinedButton(onClick = { layoutType = t }) { Text(label) }
            }
        }
        Text(stringResource(R.string.units_generated, generated.units.size))
        Text(stringResource(R.string.nkba_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        report.results.forEach { r ->
            val text = when (r.ruleId) {
                RuleId.TRIANGLE_LEG_SINK_COOKTOP, RuleId.TRIANGLE_LEG_COOKTOP_FRIDGE, RuleId.TRIANGLE_LEG_FRIDGE_SINK ->
                    stringResource(R.string.rule_triangle_leg, r.ruleId.name, r.measuredMm, r.minMm ?: 0, r.maxMm ?: 0)
                RuleId.TRIANGLE_TOTAL -> stringResource(R.string.rule_triangle_total, r.measuredMm, r.minMm ?: 0, r.maxMm ?: 0)
                RuleId.WORK_AISLE -> stringResource(R.string.rule_work_aisle, r.measuredMm, r.minMm ?: 0)
                RuleId.WALKWAY -> stringResource(R.string.rule_walkway, r.measuredMm, r.minMm ?: 0)
            }
            Text((if (r.passed) "✓ " else "⚠ ") + text)
        }
        Text(stringResource(R.string.bom_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        bom.items.forEach { item -> Text("${item.catalogId} × ${item.quantity}") }
        Text(stringResource(R.string.bom_countertop, bom.countertopLinearMetres))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CurrencySpec.ALL.forEach { c ->
                if (c == currency) Button(onClick = { currency = c }) { Text(c.code) }
                else OutlinedButton(onClick = { currency = c }) { Text(c.code) }
            }
        }
        Text(stringResource(R.string.bom_total, String.format(Locale.US, "%,.2f %s", bom.totalInCurrency, bom.currency.code)))
        MultiRoomSection(kitchenUnits = generated.units, kitchenRoom = Room(width, depth))
    }
}

/**
 * Multi-room wiring (Workstream A): a small demo [Project] whose kitchen room
 * mirrors the generator above, plus a bathroom and a living room. The room
 * tabs switch the project's active room; below them the active room's units
 * count and its openings (doors/windows, per wall) are listed. All state is
 * the pure model — switching rooms never mixes units or openings between rooms.
 */
@Composable
private fun MultiRoomSection(kitchenUnits: List<PlacedUnit>, kitchenRoom: Room) {
    var activeIndex by remember { mutableStateOf(0) }
    val project = remember(kitchenUnits, kitchenRoom, activeIndex) {
        Project(
            id = "demo-home",
            name = "Demo home",
            rooms = listOf(
                ProjectRoom(
                    id = "kitchen", type = RoomType.KITCHEN, room = kitchenRoom, units = kitchenUnits,
                    openings = listOf(
                        Opening(OpeningKind.DOOR, wallIndex = 1, offsetMm = 300, widthMm = 900, swing = SwingDirection.INWARD),
                        Opening(OpeningKind.WINDOW, wallIndex = 0, offsetMm = 1_500, widthMm = 1_200, sillHeightMm = 1_100),
                    ),
                ),
                ProjectRoom(
                    id = "bathroom", type = RoomType.BATHROOM, room = Room(2_400, 2_000),
                    openings = listOf(
                        Opening(OpeningKind.DOOR, wallIndex = 3, offsetMm = 100, widthMm = 700, swing = SwingDirection.OUTWARD),
                    ),
                ),
                ProjectRoom(id = "living", type = RoomType.LIVING, room = Room(5_000, 4_000)),
            ),
            activeRoomIndex = activeIndex,
        )
    }
    val active = project.activeRoom

    Text(stringResource(R.string.rooms_label), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        project.rooms.forEachIndexed { index, room ->
            val label = stringResource(when (room.type) {
                RoomType.KITCHEN -> R.string.room_type_kitchen
                RoomType.BATHROOM -> R.string.room_type_bathroom
                RoomType.LIVING -> R.string.room_type_living
                RoomType.BEDROOM -> R.string.room_type_bedroom
                RoomType.OFFICE -> R.string.room_type_office
                RoomType.OTHER -> R.string.room_type_other
            })
            if (index == project.activeRoomIndex) Button(onClick = { activeIndex = index }) { Text(label) }
            else OutlinedButton(onClick = { activeIndex = index }) { Text(label) }
        }
    }
    Text(stringResource(R.string.units_generated, active.units.size))
    Text(stringResource(R.string.openings_label), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    if (active.openings.isEmpty()) {
        Text(stringResource(R.string.no_openings))
    } else {
        active.openings.forEach { opening ->
            val kindLabel = stringResource(if (opening.kind == OpeningKind.DOOR) R.string.opening_door else R.string.opening_window)
            val wallLabel = stringResource(when (opening.wallSide) {
                WallSide.NORTH -> R.string.wall_north
                WallSide.SOUTH -> R.string.wall_south
                WallSide.EAST -> R.string.wall_east
                WallSide.WEST -> R.string.wall_west
                null -> R.string.wall_unknown
            })
            Text(stringResource(R.string.opening_details, kindLabel, wallLabel, opening.offsetMm, opening.widthMm))
        }
    }
}
