package com.kitchendesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
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
import com.kitchendesigner.assistant.ReferenceStyle
import com.kitchendesigner.assistant.ReferenceStyles
import com.kitchendesigner.bom.BomCalculator
import com.kitchendesigner.bom.CurrencySpec
import com.kitchendesigner.engine.KitchenPlanInput
import com.kitchendesigner.engine.LayoutGenerator
import com.kitchendesigner.engine.LayoutType
import com.kitchendesigner.engine.NkbaValidator
import com.kitchendesigner.engine.RuleId
import com.kitchendesigner.model.DoorStyle
import com.kitchendesigner.model.HandleStyle
import com.kitchendesigner.model.KitchenFinishes
import com.kitchendesigner.model.Materials
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
    // v0.5.0 customization state: null = the untouched generated layout.
    // Any edit (reference style, per-unit change) replaces the unit list;
    // changing room size or layout above resets it via remember(generated).
    var customUnits by remember(generated) { mutableStateOf<List<PlacedUnit>?>(null) }
    var finishes by remember(generated) { mutableStateOf(KitchenFinishes()) }
    var selectedUnit by remember(generated) { mutableStateOf<Int?>(null) }
    val units = customUnits ?: generated.units
    val bom = remember(units, currency) { BomCalculator.calculate(units, currency = currency) }

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
        Text(stringResource(R.string.units_generated, units.size))
        CustomizeSection(
            units = units,
            finishes = finishes,
            selectedUnit = selectedUnit,
            onSelectUnit = { selectedUnit = it },
            onUnitsChange = { customUnits = it },
            onFinishesChange = { finishes = it },
        )
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
        MultiRoomSection(kitchenUnits = units, kitchenRoom = Room(width, depth))
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

/**
 * Full customization (v0.5.0): one-tap reference-photo styles, a per-unit
 * editor (dimensions, door style, handle, finish, interior LED) for the
 * selected unit, and design-wide surfaces + LED lighting. All edits are
 * pure model copies — geometry, NKBA rules and the BOM follow the model.
 */
@Composable
private fun CustomizeSection(
    units: List<PlacedUnit>,
    finishes: KitchenFinishes,
    selectedUnit: Int?,
    onSelectUnit: (Int?) -> Unit,
    onUnitsChange: (List<PlacedUnit>) -> Unit,
    onFinishesChange: (KitchenFinishes) -> Unit,
) {
    Text(stringResource(R.string.customize_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

    // Reference-photo styles: restyle the whole kitchen in one tap.
    ChipRow {
        ReferenceStyle.entries.forEach { style ->
            val label = stringResource(when (style) {
                ReferenceStyle.CLASSIC_CREAM_SHAKER -> R.string.ref_style_classic
                ReferenceStyle.LUXE_WALNUT_CHARCOAL -> R.string.ref_style_luxe
                ReferenceStyle.MINIMAL_CREAM -> R.string.ref_style_minimal
            })
            OutlinedButton(onClick = {
                val styled = ReferenceStyles.apply(units, style)
                onUnitsChange(styled.units)
                onFinishesChange(styled.finishes)
            }) { Text(label) }
        }
    }

    // Unit picker.
    ChipRow {
        units.forEachIndexed { index, placed ->
            val label = "#$index ${placed.unit.catalogId}"
            if (index == selectedUnit) Button(onClick = { onSelectUnit(index) }) { Text(label) }
            else OutlinedButton(onClick = { onSelectUnit(index) }) { Text(label) }
        }
    }

    val sel = selectedUnit?.takeIf { it in units.indices } ?: return
    val placed = units[sel]
    val unit = placed.unit
    fun replace(newUnit: com.kitchendesigner.model.CabinetUnit) {
        onUnitsChange(units.mapIndexed { i, p -> if (i == sel) p.copy(unit = newUnit) else p })
    }

    Text(stringResource(R.string.unit_edit_title, sel, unit.catalogId), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
    DimRow(stringResource(R.string.unit_width), unit.widthMm, step = 50) { replace(unit.copy(widthMm = it.coerceIn(150, 2_400))) }
    DimRow(stringResource(R.string.unit_height), unit.heightMm, step = 10) { replace(unit.copy(heightMm = it.coerceIn(100, 2_800))) }
    DimRow(stringResource(R.string.unit_depth), unit.depthMm, step = 10) { replace(unit.copy(depthMm = it.coerceIn(100, 1_200))) }

    Text(stringResource(R.string.door_style_label))
    ChipRow {
        DoorStyle.entries.forEach { door ->
            val label = stringResource(when (door) {
                DoorStyle.SHAKER -> R.string.door_shaker
                DoorStyle.SLAB -> R.string.door_slab
                DoorStyle.GLASS -> R.string.door_glass
                DoorStyle.HANDLELESS -> R.string.door_handleless
            })
            val onClick = { replace(unit.copy(custom = unit.custom.copy(doorStyle = door))) }
            if (door == unit.custom.doorStyle) Button(onClick = onClick) { Text(label) }
            else OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
    Text(stringResource(R.string.handle_style_label))
    ChipRow {
        HandleStyle.entries.forEach { handle ->
            val label = stringResource(when (handle) {
                HandleStyle.BAR -> R.string.handle_bar
                HandleStyle.KNOB -> R.string.handle_knob
                HandleStyle.INTEGRATED -> R.string.handle_integrated
                HandleStyle.NONE -> R.string.handle_none
            })
            val onClick = { replace(unit.copy(custom = unit.custom.copy(handleStyle = handle))) }
            if (handle == unit.custom.handleStyle) Button(onClick = onClick) { Text(label) }
            else OutlinedButton(onClick = onClick) { Text(label) }
        }
    }
    Text(stringResource(R.string.finish_label))
    ChipRow {
        Materials.CABINET_FINISHES.forEach { finish ->
            val onClick = { replace(unit.copy(materialId = finish.id)) }
            if (finish.id == unit.materialId) Button(onClick = onClick) { Text(finish.id) }
            else OutlinedButton(onClick = onClick) { Text(finish.id) }
        }
    }
    ChipRow {
        val toggle = { replace(unit.copy(custom = unit.custom.copy(interiorLed = !unit.custom.interiorLed))) }
        if (unit.custom.interiorLed) Button(onClick = toggle) { Text(stringResource(R.string.led_interior)) }
        else OutlinedButton(onClick = toggle) { Text(stringResource(R.string.led_interior)) }
        OutlinedButton(onClick = {
            onUnitsChange(units.map { p ->
                if (p.unit.type == unit.type) p.copy(unit = p.unit.copy(materialId = unit.materialId, custom = unit.custom)) else p
            })
        }) { Text(stringResource(R.string.apply_to_type, unit.type.name)) }
    }

    Text(stringResource(R.string.finishes_title), style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.countertop_label))
    ChipRow {
        Materials.COUNTERTOPS.forEach { top ->
            val onClick = { onFinishesChange(finishes.copy(countertopMaterialId = top.id)) }
            if (top.id == finishes.countertopMaterialId) Button(onClick = onClick) { Text(top.id) }
            else OutlinedButton(onClick = onClick) { Text(top.id) }
        }
    }
    Text(stringResource(R.string.backsplash_label))
    ChipRow {
        Materials.BACKSPLASHES.forEach { back ->
            val onClick = { onFinishesChange(finishes.copy(backsplashId = back.id)) }
            if (back.id == finishes.backsplashId) Button(onClick = onClick) { Text(back.id) }
            else OutlinedButton(onClick = onClick) { Text(back.id) }
        }
    }
    ChipRow {
        val toggleUnder = { onFinishesChange(finishes.copy(underCabinetLed = !finishes.underCabinetLed)) }
        if (finishes.underCabinetLed) Button(onClick = toggleUnder) { Text(stringResource(R.string.led_under_cabinet)) }
        else OutlinedButton(onClick = toggleUnder) { Text(stringResource(R.string.led_under_cabinet)) }
        val toggleCove = { onFinishesChange(finishes.copy(coveLed = !finishes.coveLed)) }
        if (finishes.coveLed) Button(onClick = toggleCove) { Text(stringResource(R.string.led_cove)) }
        else OutlinedButton(onClick = toggleCove) { Text(stringResource(R.string.led_cove)) }
    }
}

/** A horizontally scrollable row of chips/buttons. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) { content() }
}

/** Label + value + step buttons for one dimension in millimetres. */
@Composable
private fun DimRow(label: String, valueMm: Int, step: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(valueMm - step) }) { Text("−") }
        Text("$valueMm")
        OutlinedButton(onClick = { onChange(valueMm + step) }) { Text("+") }
    }
}
