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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.ManagementRepository.Candidate
import org.gks.android.data.remote.OperationView
import org.gks.android.domain.ManagementAccess
import org.gks.android.domain.nextOperationStates
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.FormField
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.operationStateLabel
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.text

// ---------------------------------------------------------------- liste

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementScreen(
	viewModel: ManagementOperationsViewModel,
	access: ManagementAccess,
	sessionExpiresAt: String,
	onOpen: (String) -> Unit,
	onCreate: () -> Unit,
	onOrganizationAnnouncement: () -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val filter by viewModel.filter.collectAsStateWithLifecycle()
	Column(Modifier.fillMaxSize()) {
		Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Badge(stringResource(if (access.isAdmin) R.string.role_adm else R.string.role_coordinator),
					MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
				Text(stringResource(R.string.management_session_until, formatDateTime(sessionExpiresAt)),
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OperationFilter.entries.forEach { option ->
					FilterChip(
						selected = filter == option,
						onClick = { viewModel.select(option) },
						label = { Text(stringResource(filterLabel(option))) },
					)
				}
			}
			if (access.canCreateOperation) {
				Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.operation_create)) }
			}
			if (access.canAnnounceOrganization) {
				OutlinedButton(onClick = onOrganizationAnnouncement, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.announcements_organization_title))
				}
			}
		}
		LoadContent(state, onRetry = viewModel::load) { operations ->
			if (operations.isEmpty()) {
				item {
					EmptyView(
						R.string.management_empty_title,
						if (access.canCreateOperation) R.string.management_empty_admin else R.string.management_empty_coordinator,
					)
				}
			} else {
				items(operations, key = { it.id }) { operation -> OperationRow(operation) { onOpen(operation.id) } }
			}
		}
	}
}

private fun filterLabel(filter: OperationFilter) = when (filter) {
	OperationFilter.OPEN -> R.string.operation_filter_open
	OperationFilter.CLOSED -> R.string.operation_filter_closed
	OperationFilter.ALL -> R.string.operation_filter_all
}

@Composable
private fun OperationRow(operation: OperationView, onClick: () -> Unit) {
	ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			TitleRow(operation.name) { Badge(operationStateLabel(operation.state)) }
			if (operation.coordinators.isNotEmpty()) {
				Text(stringResource(R.string.operation_coordinators_line, operation.coordinators.joinToString(", ") { it.displayName }),
					style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			OperationSignals(operation)
		}
	}
}

/** Yönetim izdüşümündeki uyarılar: planlanan bitiş geçti (§7.4 `overdue`), yanıt bekleyen atamalar. */
@Composable
private fun OperationSignals(operation: OperationView) {
	if (operation.overdue == true) {
		Text(stringResource(R.string.operation_overdue), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
	}
	operation.pendingAssignmentCount?.takeIf { it > 0 }?.let {
		Text(stringResource(R.string.operation_pending_assignments, it), style = MaterialTheme.typography.bodySmall)
	}
}

// ---------------------------------------------------------------- ayrıntı

@Composable
fun OperationManageScreen(
	viewModel: OperationManageViewModel,
	access: ManagementAccess,
	onEdit: () -> Unit,
	onVolunteers: () -> Unit,
	onGroups: () -> Unit,
	onTasks: () -> Unit,
	onChanged: () -> Unit,
	onAnnouncements: () -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	val candidates by viewModel.candidates.collectAsStateWithLifecycle()
	var confirmState by remember { mutableStateOf<String?>(null) }
	var confirmRevoke by remember { mutableStateOf<Pair<String, String>?>(null) }

	LoadContent(state, onRetry = viewModel::load) { managed ->
		val operation = managed.operation
		val canManage = access.canManage(operation.id)
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				TitleRow(operation.name) { Badge(operationStateLabel(operation.state)) }
				OperationSignals(operation)
			}
		}
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }
		if (!canManage) item { MessageCard(stringResource(R.string.management_read_only), error = false) }

		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Fact(R.string.operation_description, operation.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.operation_no_description))
					Fact(R.string.operation_planned_end, operation.plannedEndAt?.let { formatDateTime(it) } ?: stringResource(R.string.operation_no_planned_end))
					operation.activatedAt?.let { Fact(R.string.operation_activated_at, formatDateTime(it)) }
					operation.closedAt?.let { Fact(R.string.operation_closed_at, formatDateTime(it)) }
					operation.createdAt?.let { Fact(R.string.operation_created_at, formatDateTime(it)) }
					if (canManage && nextOperationStates(operation.state).isNotEmpty()) {
						OutlinedButton(onClick = onEdit, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) {
							Text(stringResource(R.string.operation_edit))
						}
					}
				}
			}
		}

		val transitions = if (canManage) nextOperationStates(operation.state) else emptyList()
		if (transitions.isNotEmpty()) {
			item { SectionTitle(R.string.operation_state_section) }
			item {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					transitions.forEach { target ->
						val destructive = target == "CANCELLED"
						Button(
							onClick = { confirmState = target },
							enabled = !action.busy,
							colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
							modifier = Modifier.fillMaxWidth().height(48.dp),
						) { Text(stringResource(transitionLabel(target))) }
					}
				}
			}
		}

		if (canManage) {
			item {
				OutlinedButton(onClick = onVolunteers, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.volunteers_title)) }
			}
			item {
				OutlinedButton(onClick = onGroups, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.groups_management_title)) }
			}
			item {
				OutlinedButton(onClick = onTasks, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tasks_management_title)) }
			}
		}
		if (access.canAnnounce(operation.id)) {
			item {
				OutlinedButton(onClick = onAnnouncements, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.announcements_title)) }
			}
		}

		item { SectionTitle(R.string.operation_coordinators) }
		val assignments = managed.coordinators
		if (assignments == null) {
			// KOR: atamaları yönetemez, adları operasyon görünümünden görür.
			items(operation.coordinators, key = { "c-" + (it.id ?: it.displayName) }) { coordinator ->
				ElevatedCard(Modifier.fillMaxWidth()) { Text(coordinator.displayName, Modifier.padding(16.dp)) }
			}
		} else {
			items(assignments, key = { it.id }) { assignment ->
				ElevatedCard(Modifier.fillMaxWidth()) {
					Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						Column(Modifier.weight(1f)) {
							Text(assignment.user?.displayName ?: "—", style = MaterialTheme.typography.titleSmall)
							assignment.grantedAt?.let {
								Text(stringResource(R.string.coordinator_since, formatDateTime(it)), style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant)
							}
						}
						if (nextOperationStates(operation.state).isNotEmpty()) {
							TextButton(onClick = { confirmRevoke = assignment.id to (assignment.user?.displayName ?: "") }, enabled = !action.busy) {
								Text(stringResource(R.string.coordinator_remove))
							}
						}
					}
				}
			}
			if (nextOperationStates(operation.state).isNotEmpty()) {
				item {
					OutlinedButton(onClick = viewModel::loadCandidates, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) {
						Text(stringResource(R.string.coordinator_add))
					}
				}
			}
		}
	}

	confirmState?.let { target ->
		AlertDialog(
			onDismissRequest = { confirmState = null },
			title = { Text(stringResource(transitionLabel(target))) },
			text = { Text(stringResource(transitionWarning(target))) },
			confirmButton = {
				TextButton(onClick = { confirmState = null; viewModel.changeState(target, onChanged) }) { Text(stringResource(R.string.action_confirm)) }
			},
			dismissButton = { TextButton(onClick = { confirmState = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	confirmRevoke?.let { (assignmentId, name) ->
		val lastOne = ((state as? Load.Ready)?.value?.coordinators?.size ?: 0) <= 1
		AlertDialog(
			onDismissRequest = { confirmRevoke = null },
			title = { Text(stringResource(R.string.coordinator_remove)) },
			text = { Text(stringResource(if (lastOne) R.string.coordinator_remove_last else R.string.coordinator_remove_body, name)) },
			confirmButton = {
				TextButton(onClick = { confirmRevoke = null; viewModel.revokeCoordinator(assignmentId, onChanged) }) { Text(stringResource(R.string.action_confirm)) }
			},
			dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	candidates?.let { load ->
		val existing = (state as? Load.Ready)?.value?.coordinators?.mapNotNull { it.user?.id }.orEmpty().toSet()
		CandidateDialog(load, existing, onPick = { viewModel.grantCoordinator(it, onChanged) }, onDismiss = viewModel::closeCandidates)
	}
}

@Composable
private fun CandidateDialog(load: Load<List<Candidate>>, existing: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.coordinator_add)) },
		text = {
			when (load) {
				Load.Loading -> CircularProgressIndicator()
				is Load.Failed -> Text(load.message.resolve(), color = MaterialTheme.colorScheme.error)
				is Load.Ready -> {
					val options = load.value.filter { it.id !in existing }
					if (options.isEmpty()) {
						Text(stringResource(R.string.coordinator_no_candidates))
					} else {
						LazyColumn(Modifier.height(320.dp)) {
							item { Text(stringResource(R.string.coordinator_candidates_hint), style = MaterialTheme.typography.bodySmall) }
							items(options, key = { it.id }) { candidate ->
								TextButton(onClick = { onPick(candidate.id) }, modifier = Modifier.fillMaxWidth()) {
									Column(Modifier.fillMaxWidth()) {
										Text(candidate.displayName)
										Text(stringResource(candidateSource(candidate.source)), style = MaterialTheme.typography.labelSmall)
									}
								}
							}
						}
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}

private fun candidateSource(source: Candidate.Source) = when (source) {
	Candidate.Source.PARTICIPANT -> R.string.candidate_participant
	Candidate.Source.COORDINATOR_ELSEWHERE -> R.string.candidate_coordinator
}

private fun transitionLabel(target: String) = when (target) {
	"ACTIVE" -> R.string.operation_activate
	"COMPLETED" -> R.string.operation_complete
	"CANCELLED" -> R.string.operation_cancel
	else -> R.string.operation_state_section
}

private fun transitionWarning(target: String) = when (target) {
	"ACTIVE" -> R.string.operation_activate_warning
	"COMPLETED" -> R.string.operation_complete_warning
	"CANCELLED" -> R.string.operation_cancel_warning
	else -> R.string.operation_transition_invalid
}

// ---------------------------------------------------------------- form

@Composable
fun OperationFormScreen(viewModel: OperationFormViewModel, editing: Boolean, onSaved: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	if (state.loading) {
		LoadingView()
		return
	}
	if (editing && state.original == null) {
		// Operasyon okunamadı (yetkisiz, bulunamadı veya ağ): form açılmaz.
		Column(Modifier.padding(16.dp)) { MessageCard((state.error ?: text(R.string.error_unexpected)).resolve(), error = true) }
		return
	}
	val creating = !editing
	Column(
		modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			state.error?.let { MessageCard(it.resolve(), error = true) }
			val enabled = !state.busy
			FormField(state.name, R.string.operation_name, state.fieldErrors["name"]?.resolve(), enabled,
				KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
				supporting = R.string.operation_name_help) { viewModel.edit("name") { copy(name = it) } }
			OutlinedTextField(
				value = state.description,
				onValueChange = { viewModel.edit("description") { copy(description = it) } },
				label = { Text(stringResource(R.string.operation_description)) },
				enabled = enabled,
				isError = state.fieldErrors["description"] != null,
				supportingText = { Text(state.fieldErrors["description"]?.resolve() ?: stringResource(R.string.operation_description_help)) },
				minLines = 3,
				modifier = Modifier.fillMaxWidth(),
			)
			FormField(state.plannedEnd, R.string.operation_planned_end, state.fieldErrors["plannedEndAt"]?.resolve(), enabled,
				KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
				supporting = R.string.operation_planned_end_help) { viewModel.edit("plannedEndAt") { copy(plannedEnd = it) } }

			if (creating) {
				SectionTitle(R.string.operation_coordinators)
				state.fieldErrors["coordinatorIds"]?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
				Text(stringResource(R.string.operation_coordinators_help), style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant)
				state.candidates.forEach { candidate ->
					Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
						Checkbox(checked = candidate.id in state.coordinatorIds, onCheckedChange = { viewModel.toggleCoordinator(candidate.id) }, enabled = enabled)
						Text(candidate.displayName)
					}
				}
			}
			Button(onClick = { viewModel.save(onSaved) }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
				if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
				else Text(stringResource(if (creating) R.string.operation_create else R.string.action_save))
			}
		}
	}
}
