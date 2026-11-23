package com.kitchendesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitchendesigner.R
import com.kitchendesigner.ar.DimensionKind
import com.kitchendesigner.ar.RoomEstimate
import com.kitchendesigner.model.Room
import java.util.Locale

/**
 * Scan confirmation screen (Step 4 UI) — the accuracy-correction path.
 *
 * ARCore on Android has no LiDAR, so every scanned dimension is shown with
 * its confidence and is EDITABLE before it becomes a [Room]. Dimensions in
 * [RoomEstimate.needsConfirmation] (low confidence or missing) are flagged;
 * laser-measured dimensions arrive at confidence 1.0 and are shown as such.
 *
 * NOTE: written carefully against Compose APIs but not Gradle-compiled in
 * this environment (no Android SDK) — see top-level README status table.
 */
@Composable
fun ScanConfirmScreen(
    estimate: RoomEstimate,
    onConfirm: (Room) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthText by remember(estimate.widthMm) { mutableStateOf((estimate.widthMm ?: 0).toString()) }
    var depthText by remember(estimate.depthMm) { mutableStateOf((estimate.depthMm ?: 0).toString()) }
    var heightText by remember(estimate.heightMm) { mutableStateOf((estimate.heightMm ?: 2_700).toString()) }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.scan_confirm_title), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.scan_confirm_hint))
        DimensionField(
            labelRes = R.string.room_width,
            text = widthText, onText = { widthText = it.filter(Char::isDigit) },
            confidence = estimate.confidenceByKind[DimensionKind.WIDTH],
            needsConfirmation = DimensionKind.WIDTH in estimate.needsConfirmation,
        )
        DimensionField(
            labelRes = R.string.room_depth,
            text = depthText, onText = { depthText = it.filter(Char::isDigit) },
            confidence = estimate.confidenceByKind[DimensionKind.DEPTH],
            needsConfirmation = DimensionKind.DEPTH in estimate.needsConfirmation,
        )
        DimensionField(
            labelRes = R.string.room_height,
            text = heightText, onText = { heightText = it.filter(Char::isDigit) },
            confidence = estimate.confidenceByKind[DimensionKind.HEIGHT],
            needsConfirmation = DimensionKind.HEIGHT in estimate.needsConfirmation,
        )
        val width = widthText.toIntOrNull() ?: 0
        val depth = depthText.toIntOrNull() ?: 0
        val height = heightText.toIntOrNull() ?: 2_700
        Button(
            onClick = { onConfirm(Room(width, depth, height)) },
            enabled = width > 0 && depth > 0 && height > 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.scan_confirm_action)) }
    }
}

@Composable
private fun DimensionField(
    labelRes: Int,
    text: String,
    onText: (String) -> Unit,
    confidence: Float?,
    needsConfirmation: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = onText,
                label = { Text(stringResource(labelRes)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (confidence != null) {
            Text(stringResource(R.string.scan_confidence, String.format(Locale.US, "%.0f%%", confidence * 100)))
        }
        if (needsConfirmation) {
            Text(stringResource(R.string.scan_needs_confirmation), color = Color(0xFFB3261E))
        }
    }
}
