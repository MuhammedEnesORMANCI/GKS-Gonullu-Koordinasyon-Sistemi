package org.gks.android.ui.management

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.ManagementRepository
import org.gks.android.data.TaskDraft
import org.gks.android.data.TaskManagementRepository
import org.gks.android.data.nextTaskStates
import org.gks.android.data.remote.AssignmentView
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.Participant
import org.gks.android.data.remote.TaskSummary
import org.gks.android.data.remote.TaskView
import org.gks.android.ui.account.fieldErrors
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.StatusKind
import org.gks.android.ui.common.StatusBadge
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.DateTimeField
import org.gks.android.ui.common.DateTimes
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.FormField
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.PriorityBadge
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.assignmentStatusLabel
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.priorityLabel
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.taskStateLabel
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** Görev yönetimi hata kodları (backend §9.1, LD-46, LD-53). */
private val TASK_ERRORS = mapOf(
	"VERSION_CONFLICT" to R.string.task_error_version,
	"INVALID_STATE_TRANSITION" to R.string.operation_transition_invalid,
	"CAPACITY_BELOW_ACCEPTED" to R.string.task_error_capacity_below,
	"CAPACITY_FULL" to R.string.task_capacity_full,
	"NOT_A_PARTICIPANT" to R.string.group_error_not_participant,
	"ALREADY_EXISTS" to R.string.task_error_assignment_active,
	"FORBIDDEN" to R.string.management_forbidden,
	"CLIENT_NOT_ALLOWED" to R.string.management_session_required,
)

private val PRIORITIES = listOf("LOW", "NORMAL", "HIGH", "URGENT")

/** Sunucunun zaman aralığı alan adları (§9.3): nesne düzeyi, başlangıç ve bitiş. */
private val TIME_FIELDS = setOf("timeWindow", "timeWindow.start", "timeWindow.end")
private val ACTIVE_ASSIGNMENT = setOf("PENDING", "ACCEPTED")

// ---------------------------------------------------------------- liste (TK-07)

class OperationTasksViewModel(private val repository: TaskManagementRepository, private val operationId: String) :
	LoadViewModel<List<TaskSummary>>() {
	init { load() }

	override suspend fun fetch() = repository.tasks(operationId)
}

@Composable
fun OperationTasksScreen(viewModel: OperationTasksViewModel, canManageTasks: Boolean, onOpen: (String) -> Unit, onCreate: () -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { tasks ->
		if (canManageTasks) {
			item { Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.task_create)) } }
		} else {
			item { MessageCard(stringResource(R.string.tasks_management_read_only), error = false) }
		}
		if (tasks.isEmpty()) {
			item { EmptyView(R.string.tasks_management_empty) }
		} else {
			items(tasks, key = { it.id }) { task ->
				ElevatedCard(onClick = { onOpen(task.id) }, modifier = Modifier.fillMaxWidth()) {
					Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						TitleRow(task.title) { PriorityBadge(task.priority) }
						StatusBadge(StatusKind.TASK, task.state)
						Counts(task.acceptedCount, task.capacity, task.pendingCount, task.overdueCount)
					}
				}
			}
		}
	}
}

@Composable
private fun Counts(accepted: Int, capacity: Int?, pending: Int?, overdue: Int?) {
	Text(
		(capacity?.let { stringResource(R.string.task_capacity_value, accepted, it) } ?: stringResource(R.string.task_capacity_unlimited, accepted)) +
			(if (pending != null) " · " + stringResource(R.string.task_pending_overdue, pending, overdue ?: 0) else ""),
		style = MaterialTheme.typography.bodySmall,
		color = if ((overdue ?: 0) > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

// ---------------------------------------------------------------- form (TK-01, TK-02)

data class TaskFormState(
	val loading: Boolean = true,
	val original: TaskView? = null,
	val title: String = "",
	val description: String = "",
	val priority: String = "NORMAL",
	val meetingPoint: String = "",
	/** UTC ISO-8601 veya `null` (seçici: [DateTimeField]). Düzenlemede sunucunun özgün değeri aynen tutulur. */
	val start: String? = null,
	val end: String? = null,
	val capacity: String = "",
	val window: String = "",
	val userIds: Set<String> = emptySet(),
	val groupIds: Set<String> = emptySet(),
	val groups: List<GroupSummary> = emptyList(),
	val participants: List<Participant> = emptyList(),
	val busy: Boolean = false,
	val fieldErrors: Map<String, UiText> = emptyMap(),
	val error: UiText? = null,
) {
	/** Hedefler yalnızca taslakta (oluşturma veya DRAFT düzenleme) seçilebilir (§9.3). */
	val targetsEditable: Boolean get() = original == null || original.state == "DRAFT"
}

class TaskFormViewModel(
	private val tasks: TaskManagementRepository,
	private val management: ManagementRepository,
	private val operationId: String,
	private val taskId: String?,
) : ViewModel() {

	private val _state = MutableStateFlow(TaskFormState())
	val state: StateFlow<TaskFormState> = _state.asStateFlow()

	init {
		viewModelScope.launch {
			val original = taskId?.let {
				when (val result = tasks.task(it)) {
					is ApiResult.Success -> result.value
					is ApiResult.Failure -> {
						_state.value = TaskFormState(loading = false, error = result.error.toUiText(TASK_ERRORS))
						return@launch
					}
				}
			}
			val editableTargets = original == null || original.state == "DRAFT"
			val groups = if (editableTargets) (management.groups(operationId) as? ApiResult.Success)?.value.orEmpty() else emptyList()
			val people = if (editableTargets) (management.participantCandidates(operationId) as? ApiResult.Success)?.value.orEmpty() else emptyList()
			_state.value = TaskFormState(
				loading = false,
				original = original,
				title = original?.title.orEmpty(),
				description = original?.description.orEmpty(),
				priority = original?.priority ?: "NORMAL",
				meetingPoint = original?.meetingPoint.orEmpty(),
				start = original?.timeWindow?.start,
				end = original?.timeWindow?.end,
				capacity = original?.capacity?.toString().orEmpty(),
				window = original?.responseWindowMinutes?.toString().orEmpty(),
				userIds = original?.targets?.userIds.orEmpty().toSet(),
				groupIds = original?.targets?.groupIds.orEmpty().toSet(),
				groups = groups.filter { !it.archived },
				participants = people.sortedBy { it.user.displayName.lowercase() },
			)
		}
	}

	fun edit(field: String, transform: TaskFormState.() -> TaskFormState) =
		_state.update { it.transform().copy(fieldErrors = it.fieldErrors - field, error = null) }

	/** Zaman aralığı alanları birlikte doğrulandığı için ikisinin hataları birlikte temizlenir. */
	fun editTime(transform: TaskFormState.() -> TaskFormState) =
		_state.update { it.transform().copy(fieldErrors = it.fieldErrors - TIME_FIELDS, error = null) }

	fun toggleUser(id: String) = edit("targets") { copy(userIds = if (id in userIds) userIds - id else userIds + id) }

	fun toggleGroup(id: String) = edit("targets") { copy(groupIds = if (id in groupIds) groupIds - id else groupIds + id) }

	fun save(onSaved: (String) -> Unit) {
		val s = _state.value
		if (s.busy) return
		val errors = linkedMapOf<String, UiText>()
		if (s.title.trim().length !in 3..120) errors["title"] = text(R.string.task_title_invalid)
		if (s.description.trim().length > 2000) errors["description"] = text(R.string.task_description_too_long)
		val start = s.start
		val end = s.end
		// backend §9.3 ile aynı: bitiş başlangıçsız olamaz, başlangıçtan sonra olmalı (geçmiş tarih backend'de serbest).
		if (end != null && start == null) errors["timeWindow.start"] = text(R.string.task_end_requires_start)
		val startAt = DateTimes.parse(start)
		val endAt = DateTimes.parse(end)
		if (startAt != null && endAt != null && !endAt.isAfter(startAt)) errors["timeWindow.end"] = text(R.string.task_end_before_start)
		val capacity = number(s.capacity, 1..500) { errors["capacity"] = text(R.string.task_capacity_invalid) }
		val window = number(s.window, 5..1440) { errors["responseWindowMinutes"] = text(R.string.task_window_invalid) }
		if (s.targetsEditable && s.userIds.isEmpty() && s.groupIds.isEmpty()) errors["targets"] = text(R.string.task_targets_required)
		if (errors.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = errors) }
			return
		}
		val draft = TaskDraft(s.title, s.description, s.priority, s.meetingPoint, start, end, capacity, window,
			s.userIds.toList(), s.groupIds.toList())
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			val result = s.original?.let { tasks.update(it, draft) } ?: tasks.create(operationId, draft)
			when (result) {
				is ApiResult.Success -> onSaved(result.value.id)
				is ApiResult.Failure -> {
					val fields = result.error.fieldErrors(setOf("title", "description", "meetingPoint", "capacity",
						"responseWindowMinutes", "targets", "priority") + TIME_FIELDS)
					_state.update { it.copy(busy = false, fieldErrors = fields, error = if (fields.isEmpty()) result.error.toUiText(TASK_ERRORS) else null) }
				}
			}
		}
	}

	private fun number(input: String, range: IntRange, invalid: () -> Unit): Int? {
		val trimmed = input.trim()
		if (trimmed.isEmpty()) return null
		return trimmed.toIntOrNull()?.takeIf { it in range } ?: run { invalid(); null }
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormScreen(viewModel: TaskFormViewModel, onSaved: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	if (state.loading) {
		LoadingView()
		return
	}
	Column(
		Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			state.error?.let { MessageCard(it.resolve(), error = true) }
			val enabled = !state.busy
			val sentence = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
			val number = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
			FormField(state.title, R.string.task_title, state.fieldErrors["title"]?.resolve(), enabled, sentence,
				supporting = R.string.task_title_help) { viewModel.edit("title") { copy(title = it) } }
			OutlinedTextField(
				value = state.description,
				onValueChange = { viewModel.edit("description") { copy(description = it) } },
				label = { Text(stringResource(R.string.task_description)) },
				enabled = enabled,
				isError = state.fieldErrors["description"] != null,
				supportingText = state.fieldErrors["description"]?.let { { Text(it.resolve()) } },
				minLines = 3,
				modifier = Modifier.fillMaxWidth(),
			)
			Text(stringResource(R.string.task_priority), style = MaterialTheme.typography.labelLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				PRIORITIES.forEach { p ->
					FilterChip(selected = state.priority == p, onClick = { viewModel.edit("priority") { copy(priority = p) } },
						label = { Text(priorityLabel(p)) }, enabled = enabled)
				}
			}
			FormField(state.meetingPoint, R.string.task_meeting_point, state.fieldErrors["meetingPoint"]?.resolve(), enabled, sentence,
				supporting = R.string.task_meeting_point_help) { viewModel.edit("meetingPoint") { copy(meetingPoint = it) } }
			DateTimeField(state.start, R.string.task_start,
				(state.fieldErrors["timeWindow.start"] ?: state.fieldErrors["timeWindow"])?.resolve(), enabled,
				onChange = { viewModel.editTime { copy(start = it) } }, supporting = R.string.task_time_help)
			DateTimeField(state.end, R.string.task_end, state.fieldErrors["timeWindow.end"]?.resolve(), enabled,
				onChange = { viewModel.editTime { copy(end = it) } }, supporting = R.string.task_time_help,
				minDate = DateTimes.localDate(state.start, java.time.ZoneId.systemDefault()))
			FormField(state.capacity, R.string.task_capacity_field, state.fieldErrors["capacity"]?.resolve(), enabled, number,
				supporting = R.string.task_capacity_help) { viewModel.edit("capacity") { copy(capacity = it) } }
			FormField(state.window, R.string.task_window_field, state.fieldErrors["responseWindowMinutes"]?.resolve(), enabled, number,
				supporting = R.string.task_window_help) { viewModel.edit("responseWindowMinutes") { copy(window = it) } }

			SectionTitle(R.string.task_targets)
			if (!state.targetsEditable) {
				MessageCard(stringResource(R.string.task_targets_locked), error = false)
			} else {
				Text(stringResource(R.string.task_targets_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				state.fieldErrors["targets"]?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
				if (state.groups.isNotEmpty()) Text(stringResource(R.string.task_target_groups), style = MaterialTheme.typography.labelLarge)
				state.groups.forEach { g ->
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = g.id in state.groupIds, onCheckedChange = { viewModel.toggleGroup(g.id) }, enabled = enabled)
						Text(stringResource(R.string.task_target_group_line, g.name, g.memberCount))
					}
				}
				if (state.participants.isNotEmpty()) Text(stringResource(R.string.task_target_people), style = MaterialTheme.typography.labelLarge)
				state.participants.forEach { p ->
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = p.user.id in state.userIds, onCheckedChange = { viewModel.toggleUser(p.user.id) }, enabled = enabled)
						Text(p.user.displayName)
					}
				}
				if (state.groups.isEmpty() && state.participants.isEmpty()) MessageCard(stringResource(R.string.group_no_participants), error = true)
			}
			Button(onClick = { viewModel.save(onSaved) }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
				if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
				else Text(stringResource(if (state.original == null) R.string.task_create else R.string.action_save))
			}
		}
	}
}

// ---------------------------------------------------------------- ayrıntı, yayın, durum, atamalar (TK-03 – TK-09)

data class ManagedTask(val task: TaskView, val assignments: List<AssignmentView>)

class ManagedTaskViewModel(
	private val tasks: TaskManagementRepository,
	private val management: ManagementRepository,
	private val operationId: String,
	private val taskId: String,
) : LoadViewModel<ManagedTask>() {

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()

	private val _candidates = MutableStateFlow<Load<List<Participant>>?>(null)
	val candidates: StateFlow<Load<List<Participant>>?> = _candidates.asStateFlow()

	init { load() }

	override suspend fun fetch(): ApiResult<ManagedTask> {
		val task = when (val result = tasks.task(taskId)) {
			is ApiResult.Success -> result.value
			is ApiResult.Failure -> return result
		}
		// Taslakta atama yoktur; TK-09 yayından sonra anlamlıdır.
		if (task.state == "DRAFT") return ApiResult.Success(ManagedTask(task, emptyList()))
		return when (val assignments = tasks.assignments(taskId)) {
			is ApiResult.Success -> ApiResult.Success(ManagedTask(task, assignments.value))
			is ApiResult.Failure -> assignments
		}
	}

	fun publish(version: Int) = act(R.string.task_published) { tasks.publish(taskId, version) }

	fun changeState(target: String) = act(R.string.task_state_saved) { tasks.changeState(taskId, target) }

	fun removeAssignment(assignmentId: String) = act(R.string.task_assignment_removed) { tasks.removeAssignment(taskId, assignmentId) }

	fun addAssignment(userId: String) {
		_candidates.value = null
		act(R.string.task_assignment_added) { tasks.addAssignment(taskId, userId) }
	}

	/** Adaylar: etkin (PENDING/ACCEPTED) ataması olmayan katılımcılar. */
	fun loadCandidates(active: Set<String>) {
		_candidates.value = Load.Loading
		viewModelScope.launch {
			_candidates.value = when (val result = management.participantCandidates(operationId)) {
				is ApiResult.Success -> Load.Ready(result.value.filter { it.user.id !in active }.sortedBy { it.user.displayName.lowercase() })
				is ApiResult.Failure -> Load.Failed(result.error.toUiText(TASK_ERRORS))
			}
		}
	}

	fun closeCandidates() {
		_candidates.value = null
	}

	private fun act(success: Int, block: suspend () -> ApiResult<*>) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			_action.value = when (val result = block()) {
				is ApiResult.Success -> ActionState(notice = text(success))
				is ApiResult.Failure -> ActionState(error = result.error.toUiText(TASK_ERRORS))
			}
			load()   // sürüm çakışmasında da güncel hâl
		}
	}
}

private sealed interface TaskConfirm {
	data class Publish(val version: Int) : TaskConfirm
	data class State(val target: String) : TaskConfirm
	data class Remove(val assignment: AssignmentView) : TaskConfirm
}

@Composable
fun ManagedTaskScreen(viewModel: ManagedTaskViewModel, canManageTasks: Boolean, onEdit: () -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	val candidates by viewModel.candidates.collectAsStateWithLifecycle()
	var confirm by remember { mutableStateOf<TaskConfirm?>(null) }

	LoadContent(state, onRetry = viewModel::load) { managed ->
		val task = managed.task
		val open = task.state != "DONE" && task.state != "CANCELLED"
		item {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				TitleRow(task.title) { PriorityBadge(task.priority) }
				StatusBadge(StatusKind.TASK, task.state)
				Counts(task.acceptedCount, task.capacity, task.pendingCount, task.overdueCount)
			}
		}
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }
		if (!canManageTasks) item { MessageCard(stringResource(R.string.tasks_management_read_only), error = false) }

		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					task.description?.takeIf { it.isNotBlank() }?.let { Fact(R.string.task_description, it) }
					task.meetingPoint?.takeIf { it.isNotBlank() }?.let { Fact(R.string.task_meeting_point, it) }
					task.timeWindow?.start?.let { start ->
						Fact(R.string.task_time, task.timeWindow.end?.let { stringResource(R.string.task_time_range, formatDateTime(start), formatDateTime(it)) }
							?: stringResource(R.string.task_time_from, formatDateTime(start)))
					}
					task.responseWindowMinutes?.let { Fact(R.string.task_window_field, it.toString()) }
					task.createdBy?.let { Fact(R.string.task_created_by, it.displayName) }
					task.publishedAt?.let { Fact(R.string.task_published_at, formatDateTime(it)) }
					if (canManageTasks && open) {
						OutlinedButton(onClick = onEdit, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.task_edit)) }
					}
				}
			}
		}

		if (canManageTasks) {
			item {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					if (task.state == "DRAFT" && task.version != null) {
						Button(onClick = { confirm = TaskConfirm.Publish(task.version) }, enabled = !action.busy,
							modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(R.string.task_publish)) }
					}
					nextTaskStates(task.state).forEach { target ->
						Button(
							onClick = { confirm = TaskConfirm.State(target) },
							enabled = !action.busy,
							colors = if (target == "CANCELLED") ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
							modifier = Modifier.fillMaxWidth().height(48.dp),
						) { Text(stringResource(stateAction(target))) }
					}
				}
			}
		}

		if (task.state != "DRAFT") {
			item { SectionTitle(R.string.task_assignments) }
			if (managed.assignments.isEmpty()) {
				item { Text(stringResource(R.string.task_no_assignments), color = MaterialTheme.colorScheme.onSurfaceVariant) }
			}
			items(managed.assignments, key = { it.id }) { a ->
				ElevatedCard(Modifier.fillMaxWidth()) {
					Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
						TitleRow(a.user.displayName) { Badge(assignmentStatusLabel(a.status)) }
						if (a.status == "PENDING" && a.respondBy != null) {
							Text(stringResource(if (a.overdue) R.string.task_respond_overdue else R.string.task_respond_by, formatDateTime(a.respondBy)),
								style = MaterialTheme.typography.bodySmall,
								color = if (a.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
						}
						if (canManageTasks && open && a.status in ACTIVE_ASSIGNMENT) {
							TextButton(onClick = { confirm = TaskConfirm.Remove(a) }, enabled = !action.busy) { Text(stringResource(R.string.task_assignment_remove)) }
						}
					}
				}
			}
			if (canManageTasks && (task.state == "PUBLISHED" || task.state == "IN_PROGRESS")) {
				item {
					OutlinedButton(
						onClick = { viewModel.loadCandidates(managed.assignments.filter { it.status in ACTIVE_ASSIGNMENT }.mapNotNull { it.user.id }.toSet()) },
						enabled = !action.busy,
						modifier = Modifier.fillMaxWidth(),
					) { Text(stringResource(R.string.task_assignment_add)) }
				}
			}
		}

		if (task.history.isNotEmpty()) {
			item { SectionTitle(R.string.task_history) }
			items(task.history.reversed(), key = { "h-" + it.changedAt + it.state }) { h ->
				Text(stringResource(R.string.task_history_line, taskStateLabel(h.state), formatDateTime(h.changedAt)) +
					(h.changedBy?.let { " · " + it.displayName } ?: ""), style = MaterialTheme.typography.bodySmall)
			}
		}
	}

	confirm?.let { c ->
		val (title, body) = when (c) {
			is TaskConfirm.Publish -> stringResource(R.string.task_publish) to stringResource(R.string.task_publish_body)
			is TaskConfirm.State -> stringResource(stateAction(c.target)) to stringResource(stateWarning(c.target))
			is TaskConfirm.Remove -> stringResource(R.string.task_assignment_remove) to stringResource(R.string.task_assignment_remove_body, c.assignment.user.displayName)
		}
		AlertDialog(
			onDismissRequest = { confirm = null },
			title = { Text(title) },
			text = { Text(body) },
			confirmButton = {
				TextButton(onClick = {
					confirm = null
					when (c) {
						is TaskConfirm.Publish -> viewModel.publish(c.version)
						is TaskConfirm.State -> viewModel.changeState(c.target)
						is TaskConfirm.Remove -> viewModel.removeAssignment(c.assignment.id)
					}
				}) { Text(stringResource(R.string.action_confirm)) }
			},
			dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	candidates?.let { load ->
		AlertDialog(
			onDismissRequest = viewModel::closeCandidates,
			title = { Text(stringResource(R.string.task_assignment_add)) },
			text = {
				when (load) {
					Load.Loading -> CircularProgressIndicator()
					is Load.Failed -> Text(load.message.resolve(), color = MaterialTheme.colorScheme.error)
					is Load.Ready -> if (load.value.isEmpty()) {
						Text(stringResource(R.string.task_no_candidates))
					} else {
						LazyColumn(Modifier.height(320.dp)) {
							items(load.value, key = { it.user.id }) { p ->
								TextButton(onClick = { viewModel.addAssignment(p.user.id) }, modifier = Modifier.fillMaxWidth()) {
									Text(p.user.displayName, modifier = Modifier.fillMaxWidth())
								}
							}
						}
					}
				}
			},
			confirmButton = {},
			dismissButton = { TextButton(onClick = viewModel::closeCandidates) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

private fun stateAction(target: String) = when (target) {
	"IN_PROGRESS" -> R.string.task_start_progress
	"DONE" -> R.string.task_mark_done
	else -> R.string.task_cancel
}

private fun stateWarning(target: String) = when (target) {
	"IN_PROGRESS" -> R.string.task_progress_body
	"DONE" -> R.string.task_done_body
	else -> R.string.task_cancel_body
}
