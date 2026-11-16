package org.gks.android.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.remote.MyAssignmentRef
import org.gks.android.data.remote.MyAssignmentView
import org.gks.android.data.remote.TaskSummary
import org.gks.android.data.remote.TaskView
import org.gks.android.data.remote.TimeWindow
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.StatusKind
import org.gks.android.ui.common.StatusBadge
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.PriorityBadge
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.assignmentStatusLabel
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.taskStateLabel

// ---------------------------------------------------------------- liste

@Composable
fun TasksScreen(viewModel: TasksViewModel, onOpenTask: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { sections ->
		if (sections.isEmpty) {
			item { EmptyView(R.string.tasks_empty_title, R.string.tasks_empty_body) }
			return@LoadContent
		}
		assignmentSection(R.string.tasks_waiting, sections.waiting, onOpenTask)
		assignmentSection(R.string.tasks_accepted, sections.accepted, onOpenTask)
		assignmentSection(R.string.tasks_past, sections.past, onOpenTask)
	}
}

private fun LazyListScope.assignmentSection(title: Int, items: List<MyAssignmentView>, onOpenTask: (String) -> Unit) {
	if (items.isEmpty()) return
	item(key = "title-$title") { SectionTitle(title) }
	items(items, key = { it.id }) { assignment ->
		TaskCard(
			task = assignment.task,
			assignment = MyAssignmentRef(assignment.id, assignment.status, assignment.respondBy, assignment.overdue),
			showOperation = true,
			onClick = { onOpenTask(assignment.task.id) },
		)
	}
}

/** Görev kartı: Görevler sekmesi ve operasyon ayrıntısı ortak kullanır. */
@Composable
fun TaskCard(task: TaskSummary, assignment: MyAssignmentRef?, showOperation: Boolean, onClick: () -> Unit) {
	ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			TitleRow(task.title) { PriorityBadge(task.priority) }
			if (showOperation && task.operationName != null) {
				Text(task.operationName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				StatusBadge(StatusKind.TASK, task.state)
				assignment?.let { AssignmentBadge(it) }
			}
			timeWindowText(task.timeWindow)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			if (assignment?.status == "PENDING" && isOpenTask(task.state) && assignment.respondBy != null) {
				RespondBy(assignment)
			}
		}
	}
}

@Composable
private fun AssignmentBadge(assignment: MyAssignmentRef) {
	val scheme = MaterialTheme.colorScheme
	when (assignment.status) {
		"ACCEPTED" -> Badge(assignmentStatusLabel(assignment.status), scheme.primaryContainer, scheme.onPrimaryContainer)
		"PENDING" -> Badge(assignmentStatusLabel(assignment.status), scheme.tertiaryContainer, scheme.onTertiaryContainer)
		else -> Badge(assignmentStatusLabel(assignment.status), scheme.surfaceVariant, scheme.onSurfaceVariant)
	}
}

@Composable
private fun RespondBy(assignment: MyAssignmentRef) {
	Text(
		stringResource(if (assignment.overdue) R.string.task_respond_overdue else R.string.task_respond_by, formatDateTime(assignment.respondBy)),
		style = MaterialTheme.typography.bodySmall,
		color = if (assignment.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun timeWindowText(window: TimeWindow?): String? = when {
	window?.start == null -> null
	window.end == null -> stringResource(R.string.task_time_from, formatDateTime(window.start))
	else -> stringResource(R.string.task_time_range, formatDateTime(window.start), formatDateTime(window.end))
}

// ---------------------------------------------------------------- ayrıntı

@Composable
fun TaskDetailScreen(viewModel: TaskDetailViewModel) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	var confirmReject by remember { mutableStateOf<String?>(null) }

	LoadContent(state, onRetry = viewModel::load) { task ->
		item { TaskHeader(task) }
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }
		item { AssignmentPanel(task, action.busy, onAccept = { viewModel.respond(it, accept = true) }, onReject = { confirmReject = it }) }
		item { TaskFacts(task) }
	}

	confirmReject?.let { assignmentId ->
		AlertDialog(
			onDismissRequest = { confirmReject = null },
			title = { Text(stringResource(R.string.task_reject_title)) },
			text = { Text(stringResource(R.string.task_reject_body)) },
			confirmButton = {
				TextButton(onClick = { confirmReject = null; viewModel.respond(assignmentId, accept = false) }) {
					Text(stringResource(R.string.task_reject))
				}
			},
			dismissButton = { TextButton(onClick = { confirmReject = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

@Composable
private fun TaskHeader(task: TaskView) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(task.title, style = MaterialTheme.typography.headlineSmall)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			PriorityBadge(task.priority)
			StatusBadge(StatusKind.TASK, task.state)
		}
	}
}

@Composable
private fun AssignmentPanel(task: TaskView, busy: Boolean, onAccept: (String) -> Unit, onReject: (String) -> Unit) {
	val assignment = task.myAssignment ?: return
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			TitleRow(stringResource(R.string.task_my_assignment)) { AssignmentBadge(assignment) }
			val canRespond = assignment.status == "PENDING" && isOpenTask(task.state)
			if (canRespond) {
				assignment.respondBy?.let { RespondBy(assignment) }
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Button(onClick = { onAccept(assignment.id) }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
						if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.task_accept))
					}
					OutlinedButton(onClick = { onReject(assignment.id) }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
						Text(stringResource(R.string.task_reject))
					}
				}
			} else if (assignment.status == "ACCEPTED" && isOpenTask(task.state)) {
				Text(stringResource(R.string.task_accepted_hint), style = MaterialTheme.typography.bodyMedium)
			}
		}
	}
}

@Composable
private fun TaskFacts(task: TaskView) {
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			task.description?.takeIf { it.isNotBlank() }?.let { Fact(R.string.task_description, it) }
			task.meetingPoint?.takeIf { it.isNotBlank() }?.let { Fact(R.string.task_meeting_point, it) }
			timeWindowText(task.timeWindow)?.let { Fact(R.string.task_time, it) }
			Fact(
				R.string.task_capacity,
				task.capacity?.let { stringResource(R.string.task_capacity_value, task.acceptedCount, it) }
					?: stringResource(R.string.task_capacity_unlimited, task.acceptedCount),
			)
			task.createdBy?.let { Fact(R.string.task_created_by, it.displayName) }
			task.publishedAt?.let { Fact(R.string.task_published_at, formatDateTime(it)) }
		}
	}
}
