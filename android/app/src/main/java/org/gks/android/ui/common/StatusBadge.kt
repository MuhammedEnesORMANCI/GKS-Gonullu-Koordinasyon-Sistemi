package org.gks.android.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.gks.android.ui.theme.GksPalette

enum class StatusKind { OPERATION, TASK }

enum class StatusTone { SUCCESS, INFO, WARNING, DANGER, NEUTRAL }

/** Durum → ton; web `components/panel/StatusChip.tsx` ile aynı eşleme (ADR-023). */
fun statusTone(kind: StatusKind, state: String): StatusTone = when (kind) {
	StatusKind.OPERATION -> when (state) {
		"ACTIVE" -> StatusTone.SUCCESS
		"PLANNED" -> StatusTone.INFO
		"SUSPENDED" -> StatusTone.WARNING
		"CANCELLED" -> StatusTone.DANGER
		else -> StatusTone.NEUTRAL
	}
	StatusKind.TASK -> when (state) {
		"PUBLISHED" -> StatusTone.INFO
		"IN_PROGRESS" -> StatusTone.WARNING
		"DONE" -> StatusTone.SUCCESS
		else -> StatusTone.NEUTRAL
	}
}

/** Tek durum rozeti: aynı durum her ekranda aynı renk ve etiketle. */
@Composable
fun StatusBadge(kind: StatusKind, state: String) {
	val label = when (kind) {
		StatusKind.OPERATION -> operationStateLabel(state)
		StatusKind.TASK -> taskStateLabel(state)
	}
	val tone = statusTone(kind, state)
	val (container, content) = when (tone) {
		StatusTone.SUCCESS -> GksPalette.Success to Color.White
		StatusTone.INFO -> GksPalette.NavyLight to Color.White
		StatusTone.WARNING -> GksPalette.Warning to Color.White
		StatusTone.DANGER -> GksPalette.Danger to Color.White
		StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
	}
	Surface(
		color = container,
		contentColor = content,
		shape = MaterialTheme.shapes.small,
		border = if (tone == StatusTone.NEUTRAL) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
	) {
		Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
	}
}
