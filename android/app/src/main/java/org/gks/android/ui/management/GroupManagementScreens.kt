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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import org.gks.android.data.VolunteerRepository
import org.gks.android.data.remote.GroupMember
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.Participant
import org.gks.android.domain.ManagementAccess
import org.gks.android.ui.account.fieldErrors
import org.gks.android.ui.common.Badge
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.FormField
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.groupRoleLabel
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText
import org.gks.android.ui.groups.GroupDetailViewModel
import org.gks.android.ui.groups.MeetingPointCard
import org.gks.android.ui.groups.MeetingPointDialog
import org.gks.android.ui.home.GroupSummaryCard

/** Grup ve üyelik işlemlerinin hata kodları (backend §8.1, LD-36/LD-49). */
private val GROUP_ERRORS = mapOf(
	"LEADER_REQUIRED" to R.string.group_error_leader_required,
	"NOT_A_PARTICIPANT" to R.string.group_error_not_participant,
	"GROUP_FULL" to R.string.group_error_full,
	"ALREADY_EXISTS" to R.string.group_error_already_member,
	"FORBIDDEN" to R.string.management_forbidden,
	"CLIENT_NOT_ALLOWED" to R.string.management_session_required,
)

private const val NAME_MIN = 2
private const val NAME_MAX = 80
private const val DESCRIPTION_MAX = 1000
private const val MEMBER_LIMIT = 50

// ---------------------------------------------------------------- operasyonun grupları

/** GR-02 `?operationId=`: KOR kendi operasyonu, ADM salt-okur. */
class OperationGroupsViewModel(private val repository: ManagementRepository, private val operationId: String) :
	LoadViewModel<List<GroupSummary>>() {
	init { load() }

	override suspend fun fetch() = repository.groups(operationId)
}

@Composable
fun OperationGroupsScreen(viewModel: OperationGroupsViewModel, canManageGroups: Boolean, onOpen: (String) -> Unit, onCreate: () -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { groups ->
		if (canManageGroups) {
			item { Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.group_create)) } }
		} else {
			item { MessageCard(stringResource(R.string.groups_management_read_only), error = false) }
		}
		if (groups.isEmpty()) {
			item { EmptyView(R.string.groups_management_empty) }
		} else {
			items(groups, key = { it.id }) { group -> GroupSummaryCard(group) { onOpen(group.id) } }
		}
	}
}

// ---------------------------------------------------------------- grup oluşturma (GR-01)

data class GroupCreateState(
	val participants: Load<List<Participant>> = Load.Loading,
	val name: String = "",
	val description: String = "",
	val leaderId: String? = null,
	val memberIds: Set<String> = emptySet(),
	val busy: Boolean = false,
	val fieldErrors: Map<String, UiText> = emptyMap(),
	val error: UiText? = null,
)

class GroupCreateViewModel(private val repository: ManagementRepository, private val operationId: String) : ViewModel() {

	private val _state = MutableStateFlow(GroupCreateState())
	val state: StateFlow<GroupCreateState> = _state.asStateFlow()

	init { loadParticipants() }

	fun loadParticipants() {
		_state.update { it.copy(participants = Load.Loading) }
		viewModelScope.launch {
			_state.update {
				when (val result = repository.participants(operationId)) {
					is ApiResult.Success -> it.copy(participants = Load.Ready(result.value.sortedBy { p -> p.user.displayName.lowercase() }))
					is ApiResult.Failure -> it.copy(participants = Load.Failed(result.error.toUiText(GROUP_ERRORS)))
				}
			}
		}
	}

	fun edit(field: String, transform: GroupCreateState.() -> GroupCreateState) =
		_state.update { it.transform().copy(fieldErrors = it.fieldErrors - field, error = null) }

	fun pickLeader(id: String) = edit("leaderUserId") { copy(leaderId = id, memberIds = memberIds - id) }

	fun toggleMember(id: String) = edit("memberUserIds") { copy(memberIds = if (id in memberIds) memberIds - id else memberIds + id) }

	fun create(onCreated: (String) -> Unit) {
		val s = _state.value
		if (s.busy) return
		val local = buildMap<String, UiText> {
			if (s.name.trim().length !in NAME_MIN..NAME_MAX) put("name", text(R.string.group_name_invalid))
			if (s.description.trim().length > DESCRIPTION_MAX) put("description", text(R.string.group_description_too_long))
			if (s.leaderId == null) put("leaderUserId", text(R.string.group_leader_required))
			if (s.memberIds.size + 1 > MEMBER_LIMIT) put("memberUserIds", text(R.string.group_members_too_many))
		}
		if (local.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = local) }
			return
		}
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			when (val result = repository.createGroup(operationId, s.name, s.description, s.leaderId!!, s.memberIds.toList())) {
				is ApiResult.Success -> onCreated(result.value.id)
				is ApiResult.Failure -> {
					val fields = result.error.fieldErrors(setOf("name", "description", "leaderUserId", "memberUserIds"))
					_state.update { it.copy(busy = false, fieldErrors = fields, error = if (fields.isEmpty()) result.error.toUiText(GROUP_ERRORS) else null) }
				}
			}
		}
	}
}

@Composable
fun GroupCreateScreen(viewModel: GroupCreateViewModel, onCreated: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val participants = state.participants
	when (participants) {
		Load.Loading -> LoadingView()
		is Load.Failed -> Column(Modifier.padding(16.dp)) { MessageCard(participants.message.resolve(), error = true) }
		is Load.Ready -> Column(
			Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				state.error?.let { MessageCard(it.resolve(), error = true) }
				val enabled = !state.busy
				FormField(state.name, R.string.group_name, state.fieldErrors["name"]?.resolve(), enabled,
					KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
					supporting = R.string.group_name_help) { viewModel.edit("name") { copy(name = it) } }
				OutlinedTextField(
					value = state.description,
					onValueChange = { viewModel.edit("description") { copy(description = it) } },
					label = { Text(stringResource(R.string.group_description)) },
					enabled = enabled,
					isError = state.fieldErrors["description"] != null,
					supportingText = state.fieldErrors["description"]?.let { { Text(it.resolve()) } },
					minLines = 2,
					modifier = Modifier.fillMaxWidth(),
				)
				if (participants.value.isEmpty()) {
					MessageCard(stringResource(R.string.group_no_participants), error = true)
				} else {
					SectionTitle(R.string.group_leader_pick)
					state.fieldErrors["leaderUserId"]?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
					participants.value.forEach { p ->
						Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
							RadioButton(selected = state.leaderId == p.user.id, onClick = { viewModel.pickLeader(p.user.id) }, enabled = enabled)
							Text(p.user.displayName)
						}
					}
					SectionTitle(R.string.group_members_pick)
					state.fieldErrors["memberUserIds"]?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
					participants.value.filter { it.user.id != state.leaderId }.forEach { p ->
						Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
							Checkbox(checked = p.user.id in state.memberIds, onCheckedChange = { viewModel.toggleMember(p.user.id) }, enabled = enabled)
							Text(p.user.displayName)
						}
					}
				}
				Button(
					onClick = { viewModel.create(onCreated) },
					enabled = enabled && participants.value.isNotEmpty(),
					modifier = Modifier.fillMaxWidth().height(48.dp),
				) {
					if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.group_create))
				}
			}
		}
	}
}

// ---------------------------------------------------------------- grup ayrıntısı ve üyelik (GR-03 – GR-08)

/**
 * Açıklama ve toplanma noktası lider ekranıyla aynı kodla (GR-07/GR-08, [GroupDetailViewModel]); üyelik, lider değişimi
 * ve ad değişikliği yalnızca operasyonun KOR'u için (GR-04 – GR-07 `name`).
 */
class ManagedGroupViewModel(
	volunteer: VolunteerRepository,
	private val management: ManagementRepository,
	groupId: String,
) : GroupDetailViewModel(volunteer, groupId) {

	override val errorMessages: Map<String, Int> get() = GROUP_ERRORS

	private val _candidates = MutableStateFlow<Load<List<Participant>>?>(null)
	val candidates: StateFlow<Load<List<Participant>>?> = _candidates.asStateFlow()

	fun loadCandidates(operationId: String) {
		_candidates.value = Load.Loading
		viewModelScope.launch {
			_candidates.value = when (val result = management.participants(operationId, notInGroup = groupId)) {
				is ApiResult.Success -> Load.Ready(result.value)
				is ApiResult.Failure -> Load.Failed(result.error.toUiText(GROUP_ERRORS))
			}
		}
	}

	fun closeCandidates() {
		_candidates.value = null
	}

	fun addMember(userId: String) {
		_candidates.value = null
		run(R.string.group_member_added) { management.addGroupMember(groupId, userId) }
	}

	fun removeMember(userId: String) = run(R.string.group_member_removed) { management.removeGroupMember(groupId, userId) }

	fun makeLeader(userId: String) = run(R.string.group_leader_changed) { management.changeGroupLeader(groupId, userId) }

	/** @return yerel doğrulama hatası; `null` ise istek gönderildi */
	fun rename(name: String, description: String): UiText? {
		if (name.trim().length !in NAME_MIN..NAME_MAX) return text(R.string.group_name_invalid)
		if (description.trim().length > DESCRIPTION_MAX) return text(R.string.group_description_too_long)
		run(R.string.group_saved) { management.renameGroup(groupId, name, description) }
		return null
	}
}

private sealed interface MemberAction {
	data class Remove(val member: GroupMember) : MemberAction
	data class Lead(val member: GroupMember) : MemberAction
}

@Composable
fun ManagedGroupScreen(viewModel: ManagedGroupViewModel, access: ManagementAccess?) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	val candidates by viewModel.candidates.collectAsStateWithLifecycle()
	var editing by remember { mutableStateOf(false) }
	var meetingPoint by remember { mutableStateOf(false) }
	var pending by remember { mutableStateOf<MemberAction?>(null) }
	val group = (state as? Load.Ready)?.value
	val canMembership = group != null && !group.archived && access?.canManageGroups(group.operationId) == true
	// Açıklama ve toplanma noktası: KOR veya grubun lideri (backend K1/K2 kuralı).
	val canDetails = group != null && !group.archived && (canMembership || group.myRole == "LEADER")

	LoadContent(state, onRetry = viewModel::load) { g ->
		item {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				TitleRow(g.name) { if (g.archived) Badge(stringResource(R.string.group_archived)) }
				g.operationName?.let { Text(stringResource(R.string.home_group_operation, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
			}
		}
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }
		if (!canMembership) item { MessageCard(stringResource(R.string.groups_management_read_only), error = false) }

		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					Fact(R.string.group_leader, g.leader?.displayName ?: "—")
					Fact(R.string.group_description, g.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.group_no_description))
					if (canMembership) {
						OutlinedButton(onClick = { editing = true }, enabled = !action.busy) { Text(stringResource(R.string.group_edit)) }
					}
				}
			}
		}

		item { SectionTitle(R.string.meeting_point_title) }
		item { MeetingPointCard(g.meetingPoint, canDetails, action.busy) { meetingPoint = true } }

		item { SectionTitle(R.string.group_members_title) }
		val members = g.members.sortedWith(compareBy<GroupMember> { it.role != "LEADER" }.thenBy { it.user.displayName })
		items(members, key = { it.user.id ?: it.user.displayName }) { member ->
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
					TitleRow(member.user.displayName) {
						if (member.role == "LEADER") {
							Badge(groupRoleLabel(member.role), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
						} else {
							Badge(groupRoleLabel(member.role))
						}
					}
					// Lider gruptan çıkarılamaz (LEADER_REQUIRED); önce başka biri lider yapılır.
					if (canMembership && member.role != "LEADER" && member.user.id != null) {
						Row {
							TextButton(onClick = { pending = MemberAction.Lead(member) }, enabled = !action.busy) { Text(stringResource(R.string.group_make_leader)) }
							TextButton(onClick = { pending = MemberAction.Remove(member) }, enabled = !action.busy) { Text(stringResource(R.string.group_member_remove)) }
						}
					}
				}
			}
		}
		if (canMembership) {
			item {
				OutlinedButton(onClick = { viewModel.loadCandidates(g.operationId) }, enabled = !action.busy, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.group_member_add))
				}
			}
		}
	}

	if (group != null && editing) {
		GroupEditDialog(group.name, group.description.orEmpty(), onDismiss = { editing = false }) { name, description ->
			viewModel.rename(name, description).also { if (it == null) editing = false }
		}
	}
	if (group != null && meetingPoint) {
		MeetingPointDialog(group.meetingPoint, onDismiss = { meetingPoint = false }) { address, description, time ->
			viewModel.saveMeetingPoint(address, description, time).also { if (it == null) meetingPoint = false }
		}
	}
	pending?.let { act ->
		val (title, body, confirm) = when (act) {
			is MemberAction.Lead -> Triple(R.string.group_make_leader, R.string.group_make_leader_body) { viewModel.makeLeader(act.member.user.id!!) }
			is MemberAction.Remove -> Triple(R.string.group_member_remove, R.string.group_member_remove_body) { viewModel.removeMember(act.member.user.id!!) }
		}
		val name = when (act) {
			is MemberAction.Lead -> act.member.user.displayName
			is MemberAction.Remove -> act.member.user.displayName
		}
		AlertDialog(
			onDismissRequest = { pending = null },
			title = { Text(stringResource(title)) },
			text = { Text(stringResource(body, name)) },
			confirmButton = { TextButton(onClick = { pending = null; confirm() }) { Text(stringResource(R.string.action_confirm)) } },
			dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
	candidates?.let { load ->
		AlertDialog(
			onDismissRequest = viewModel::closeCandidates,
			title = { Text(stringResource(R.string.group_member_add)) },
			text = {
				when (load) {
					Load.Loading -> CircularProgressIndicator()
					is Load.Failed -> Text(load.message.resolve(), color = MaterialTheme.colorScheme.error)
					is Load.Ready -> if (load.value.isEmpty()) {
						Text(stringResource(R.string.group_no_candidates))
					} else {
						LazyColumn(Modifier.height(320.dp)) {
							items(load.value, key = { it.user.id }) { p ->
								TextButton(onClick = { viewModel.addMember(p.user.id) }, modifier = Modifier.fillMaxWidth()) {
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

/** GR-07 (KOR): ad ve açıklama. [onSave] yerel hata döndürürse pencere açık kalır. */
@Composable
private fun GroupEditDialog(initialName: String, initialDescription: String, onDismiss: () -> Unit, onSave: (String, String) -> UiText?) {
	var name by remember { mutableStateOf(initialName) }
	var description by remember { mutableStateOf(initialDescription) }
	var error by remember { mutableStateOf<UiText?>(null) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.group_edit)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.group_name)) }, singleLine = true,
					modifier = Modifier.fillMaxWidth())
				OutlinedTextField(description, { description = it; error = null }, label = { Text(stringResource(R.string.group_description)) },
					minLines = 3, modifier = Modifier.fillMaxWidth())
				error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
			}
		},
		confirmButton = { TextButton(onClick = { error = onSave(name, description) }) { Text(stringResource(R.string.action_save)) } },
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
	)
}
