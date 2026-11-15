package org.gks.android.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import org.gks.android.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Backend enum değerlerinin Türkçe karşılıkları; bilinmeyen değer olduğu gibi gösterilir. */
@Composable
fun operationStateLabel(state: String): String = when (state) {
	"PLANNED" -> stringResource(R.string.operation_state_planned)
	"ACTIVE" -> stringResource(R.string.operation_state_active)
	"SUSPENDED" -> stringResource(R.string.operation_state_suspended)
	"CLOSED" -> stringResource(R.string.operation_state_closed)
	else -> state
}

@Composable
fun roleLabel(role: String): String = when (role) {
	"GON" -> stringResource(R.string.operation_role_gon)
	"KOR" -> stringResource(R.string.operation_role_kor)
	"ADM" -> stringResource(R.string.role_adm)
	"TEK" -> stringResource(R.string.role_tek)
	"UYM" -> stringResource(R.string.role_uym)
	else -> role
}

@Composable
fun groupRoleLabel(role: String?): String =
	stringResource(if (role == "LEADER") R.string.group_role_leader else R.string.group_role_member)

@Composable
fun taskStateLabel(state: String): String = when (state) {
	"DRAFT" -> stringResource(R.string.task_state_draft)
	"PUBLISHED" -> stringResource(R.string.task_state_published)
	"IN_PROGRESS" -> stringResource(R.string.task_state_in_progress)
	"DONE" -> stringResource(R.string.task_state_done)
	"CANCELLED" -> stringResource(R.string.task_state_cancelled)
	else -> state
}

@Composable
fun assignmentStatusLabel(status: String): String = when (status) {
	"PENDING" -> stringResource(R.string.assignment_pending)
	"ACCEPTED" -> stringResource(R.string.assignment_accepted)
	"REJECTED" -> stringResource(R.string.assignment_rejected)
	"CAPACITY_FULL" -> stringResource(R.string.assignment_capacity_full)
	"REMOVED" -> stringResource(R.string.assignment_removed)
	else -> status
}

@Composable
fun priorityLabel(priority: String): String = when (priority) {
	"LOW" -> stringResource(R.string.priority_low)
	"NORMAL" -> stringResource(R.string.priority_normal)
	"HIGH" -> stringResource(R.string.priority_high)
	"URGENT" -> stringResource(R.string.priority_urgent)
	else -> priority
}

/** Öncelik rozeti: acil ve yüksek öncelik vurgulanır (afet bağlamında ilk bakışta ayırt edilsin). */
@Composable
fun PriorityBadge(priority: String) {
	val scheme = MaterialTheme.colorScheme
	val (container, content) = when (priority) {
		"URGENT" -> scheme.error to scheme.onError
		"HIGH" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
		else -> scheme.surfaceVariant to scheme.onSurfaceVariant
	}
	Badge(priorityLabel(priority), container, content)
}

@Composable
fun Badge(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
	Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
		Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
	}
}

/** Başlık + sağda rozet satırı. */
@Composable
fun TitleRow(title: String, badge: @Composable () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
		Spacer(Modifier.width(8.dp))
		badge()
	}
}

private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/** ISO-8601 anı cihaz saat diliminde gösterir. */
fun formatDateTime(iso: String?): String {
	if (iso == null) return "—"
	return try {
		DATE_TIME.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
	} catch (e: DateTimeParseException) {
		iso
	}
}
