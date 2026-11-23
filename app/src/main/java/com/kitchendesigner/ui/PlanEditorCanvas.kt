package com.kitchendesigner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchendesigner.R
import com.kitchendesigner.editor.EditorState
import com.kitchendesigner.editor.MoveResult
import com.kitchendesigner.editor.OpeningGeometry
import com.kitchendesigner.editor.footprint
import com.kitchendesigner.editor.SnapMode
import com.kitchendesigner.editor.WallDimensions
import com.kitchendesigner.model.Opening
import com.kitchendesigner.model.OpeningKind
import com.kitchendesigner.model.PointMm

/**
 * 2D plan editor (Step 2 UI): draws the room and placed units on a Canvas and
 * lets the user drag the selected unit. ALL movement rules (snapping, wall
 * snap, collision, bounds, undo history) live in the pure-Kotlin
 * [PlacementEngine] / [EditorState] — this file only converts pixels <-> mm
 * and renders state.
 *
 * Plan orientation: model origin is the room's south-west floor corner,
 * y grows north; the canvas y axis grows south, so drawing flips y.
 *
 * NOTE: written carefully against Compose APIs but not Gradle-compiled in
 * this environment (no Android SDK) — see top-level README status table.
 */
@Composable
fun PlanEditorScreen(
    initialState: EditorState,
    modifier: Modifier = Modifier,
    openings: List<Opening> = emptyList(),
) {
    var state by remember(initialState.room) { mutableStateOf(initialState) }
    var snapMode by remember { mutableStateOf(SnapMode.GRID_50) }
    var lastRejected by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.plan_editor_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        PlanEditorCanvas(
            state = state,
            snapMode = snapMode,
            onStateChange = { state = it },
            onRejected = { lastRejected = it },
            openings = openings,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                when (val r = state.rotateSelected()) {
                    is MoveResult.Accepted -> { state = r.state; lastRejected = false }
                    is MoveResult.Rejected -> lastRejected = true
                }
            }) { Text(stringResource(R.string.action_rotate)) }
            OutlinedButton(onClick = { state = state.undo() }, enabled = state.canUndo) { Text(stringResource(R.string.action_undo)) }
            OutlinedButton(onClick = { state = state.redo() }, enabled = state.canRedo) { Text(stringResource(R.string.action_redo)) }
            if (snapMode == SnapMode.GRID_50) {
                Button(onClick = { snapMode = SnapMode.FINE_10 }) { Text(stringResource(R.string.snap_grid)) }
            } else {
                OutlinedButton(onClick = { snapMode = SnapMode.GRID_50 }) { Text(stringResource(R.string.snap_fine)) }
            }
        }
        if (lastRejected) Text(stringResource(R.string.move_rejected), color = Color(0xFFB3261E))
        state.selectedIndex?.let { idx ->
            if (idx in state.units.indices) Text(stringResource(R.string.unit_selected, idx, state.units[idx].unit.catalogId))
        }
    }
}

@Composable
fun PlanEditorCanvas(
    state: EditorState,
    snapMode: SnapMode,
    onStateChange: (EditorState) -> Unit,
    onRejected: (Boolean) -> Unit = {},
    openings: List<Opening> = emptyList(),
) {
    val currentState by rememberUpdatedState(state)
    val currentSnap by rememberUpdatedState(snapMode)
    var dragStartPos by remember { mutableStateOf<PointMm?>(null) }
    var dragBaseState by remember { mutableStateOf<EditorState?>(null) }
    var dragAccumMm by remember { mutableStateOf(PointMm(0, 0)) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(state.room.widthMm.toFloat() / state.room.depthMm.toFloat())
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val s = currentState
                        val scale = size.width.toFloat() / s.room.widthMm
                        val mmX = (offset.x / scale).toInt()
                        val mmY = s.room.depthMm - (offset.y / scale).toInt()
                        val hit = s.units.indexOfLast { u ->
                            val (fw, fd) = footprint(u.unit, u.rotationDeg)
                            mmX in u.position.xMm..(u.position.xMm + fw) && mmY in u.position.yMm..(u.position.yMm + fd)
                        }
                        if (hit >= 0) {
                            // Selection itself is not an undoable mutation.
                            val selected = s.select(hit)
                            onStateChange(selected)
                            dragBaseState = selected
                            dragStartPos = s.units[hit].position
                            dragAccumMm = PointMm(0, 0)
                        } else {
                            dragStartPos = null; dragBaseState = null
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val start = dragStartPos ?: return@detectDragGestures
                        val base = dragBaseState ?: return@detectDragGestures
                        val scale = size.width.toFloat() / base.room.widthMm
                        dragAccumMm = PointMm(
                            dragAccumMm.xMm + (dragAmount.x / scale).toInt(),
                            dragAccumMm.yMm - (dragAmount.y / scale).toInt(), // canvas y is south, model y is north
                        )
                        // Always move from the drag-start state so a rejected
                        // intermediate move never accumulates drift; the final
                        // accepted position is what lands in the undo history
                        // when the gesture ends (intermediate accepts replace
                        // the working state but share the same base).
                        when (val r = base.moveSelectedTo(
                            start.xMm + dragAccumMm.xMm, start.yMm + dragAccumMm.yMm, currentSnap,
                        )) {
                            is MoveResult.Accepted -> { onStateChange(r.state); onRejected(false) }
                            is MoveResult.Rejected -> onRejected(true)
                        }
                    },
                    onDragEnd = { dragStartPos = null; dragBaseState = null },
                    onDragCancel = { dragStartPos = null; dragBaseState = null },
                )
            },
    ) {
        val scale = size.width / state.room.widthMm
        drawRect(color = Color(0xFF5D4037), style = Stroke(width = 3f), size = Size(size.width, size.height))
        var gx = 500
        while (gx < state.room.widthMm) {
            drawLine(Color(0x22000000), Offset(gx * scale, 0f), Offset(gx * scale, size.height)); gx += 500
        }
        var gy = 500
        while (gy < state.room.depthMm) {
            val yPx = size.height - gy * scale
            drawLine(Color(0x22000000), Offset(0f, yPx), Offset(size.width, yPx)); gy += 500
        }
        state.units.forEachIndexed { i, placed ->
            val (fw, fd) = footprint(placed.unit, placed.rotationDeg)
            val left = placed.position.xMm * scale
            val top = size.height - (placed.position.yMm + fd) * scale
            val selected = i == state.selectedIndex
            // v0.5.0: unselected units are filled with their actual cabinet
            // finish colour, so the plan previews the designed kitchen;
            // the selected unit keeps the blue highlight.
            drawRect(
                color = if (selected) Color(0xFF1565C0)
                        else Color(com.kitchendesigner.model.Materials.previewColorArgb(placed.unit.materialId)),
                topLeft = Offset(left, top),
                size = Size(fw * scale, fd * scale),
            )
            drawRect(
                color = Color.White, style = Stroke(width = if (selected) 3f else 1f),
                topLeft = Offset(left, top), size = Size(fw * scale, fd * scale),
            )
        }
        // Openings (v0.4.0): all geometry comes from the pure-Kotlin
        // OpeningGeometry helpers (unit-tested); here we only map mm -> px
        // with the same y-flip as the units (plan y north, canvas y south).
        fun px(p: PointMm) = Offset(p.xMm * scale, size.height - p.yMm * scale)
        openings.forEach { opening ->
            when (opening.kind) {
                OpeningKind.WINDOW -> {
                    // Windows: two parallel lines straddling the wall line.
                    OpeningGeometry.windowLines(state.room, opening)?.let { (outer, inner) ->
                        drawLine(Color(0xFF1565C0), px(outer.start), px(outer.end), strokeWidth = 2f)
                        drawLine(Color(0xFF1565C0), px(inner.start), px(inner.end), strokeWidth = 2f)
                    }
                }
                OpeningKind.DOOR -> {
                    // Doors: the wall gap, the leaf in its open position and
                    // the swing arc between closed and open.
                    val wall = OpeningGeometry.wallLine(state.room, opening)
                    drawLine(Color.White, px(wall.start), px(wall.end), strokeWidth = 4f)
                    OpeningGeometry.doorSwingArc(state.room, opening)?.let { arc ->
                        val pts = arc.samplePoints(steps = 16)
                        for (i in 0 until pts.size - 1) {
                            drawLine(Color(0xFF8D6E63), px(pts[i]), px(pts[i + 1]), strokeWidth = 1.5f)
                        }
                        drawLine(Color(0xFF5D4037), px(arc.hinge), px(arc.leafOpenEnd), strokeWidth = 2.5f)
                    }
                }
            }
        }
        // Segmented wall dimensions (v0.4.0): labels from WallDimensions,
        // placed just inside each wall at each segment's midpoint.
        val dimStyle = TextStyle(color = Color(0xFF37474F), fontSize = 9.sp)
        WallDimensions.forRoom(state.room, openings).forEach { wallDim ->
            wallDim.segments.forEach { seg ->
                if (seg.lengthMm < 300) return@forEach // too small to label legibly
                val midMm = (seg.startMm + seg.endMm) / 2
                val labelPos = when (wallDim.wallIndex) {
                    0 -> Offset(midMm * scale, size.height - state.room.depthMm * scale + 4f) // NORTH: inside = below top edge
                    1 -> Offset(midMm * scale, size.height - 14f) // SOUTH: inside = above bottom edge
                    2 -> Offset(size.width - 34f, size.height - midMm * scale) // EAST
                    else -> Offset(4f, size.height - midMm * scale) // WEST
                }
                val measured = textMeasurer.measure(seg.label, dimStyle)
                drawText(measured, topLeft = labelPos)
            }
        }
    }
}
