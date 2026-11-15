package org.gks.android.ui.management

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiError
import org.gks.android.data.ApiResult
import org.gks.android.data.ManagementRepository
import org.gks.android.data.problem
import org.gks.android.data.remote.Participant
import org.gks.android.data.remote.PendingInvitation
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.FormField
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

data class VolunteersData(val participants: List<Participant>, val invitations: List<PendingInvitation>)

/**
 * Gönüllü yönetimi (KOR kendi operasyonu, ADM): OR-06 katılımcılar, ID-12 davet, ID-13 bekleyenler, ID-14 iptal.
 * Yetki ve kapsam her istekte sunucuda (`invitation.create`, `operation.manage`; ADR-014 yönetim oturumu).
 */
class VolunteersViewModel(private val repository: ManagementRepository, private val operationId: String) :
	LoadViewModel<VolunteersData>() {

	private val _email = MutableStateFlow("")
	val email: StateFlow<String> = _email.asStateFlow()

	private val _emailError = MutableStateFlow<UiText?>(null)
	val emailError: StateFlow<UiText?> = _emailError.asStateFlow()

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()

	init { load() }

	override suspend fun fetch(): ApiResult<VolunteersData> {
		val participants = when (val result = repository.participants(operationId)) {
			is ApiResult.Success -> result.value
			is ApiResult.Failure -> return result
		}
		return when (val invitations = repository.pendingInvitations(operationId)) {
			is ApiResult.Success -> ApiResult.Success(VolunteersData(participants.sortedBy { it.user.displayName.lowercase() }, invitations.value))
			is ApiResult.Failure -> invitations
		}
	}

	fun onEmail(value: String) {
		_email.value = value
		_emailError.value = null
	}

	fun invite() {
		val email = _email.value.trim()
		if (!EMAIL.matches(email)) {
			_emailError.value = text(R.string.volunteers_email_invalid)
			return
		}
		act(R.string.volunteers_invited, clearEmail = true) { repository.invite(operationId, email) }
	}

	fun revoke(invitationId: String) = act(R.string.volunteers_revoked) { repository.revokeInvitation(invitationId) }

	private fun act(success: Int, clearEmail: Boolean = false, block: suspend () -> ApiResult<*>) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			val result = block()
			_action.value = when (result) {
				is ApiResult.Success -> {
					if (clearEmail) _email.value = ""
					ActionState(notice = text(success))
				}
				is ApiResult.Failure -> {
					val field = result.error.problem?.errors?.firstOrNull { it.field == "email" }?.message
					if (field != null) {
						_emailError.value = UiText.Raw(field)
						ActionState()
					} else {
						ActionState(error = invitationMessage(result.error))
					}
				}
			}
			load()
		}
	}

	private fun invitationMessage(error: ApiError): UiText {
		val problem = error.problem
		return when {
			problem?.code == "ALREADY_EXISTS" && problem.conflict == "INVITATION_PENDING" -> text(R.string.invite_pending_exists)
			problem?.code == "ALREADY_EXISTS" && problem.conflict == "ALREADY_PARTICIPANT" -> text(R.string.invite_already_participant)
			problem?.code == "INVALID_STATE_TRANSITION" -> text(R.string.invite_not_pending)
			else -> error.toUiText(mapOf("FORBIDDEN" to R.string.management_forbidden, "CLIENT_NOT_ALLOWED" to R.string.management_session_required))
		}
	}

	private companion object {
		val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
	}
}

@Composable
fun VolunteersScreen(viewModel: VolunteersViewModel) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val email by viewModel.email.collectAsStateWithLifecycle()
	val emailError by viewModel.emailError.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	var confirmRevoke by remember { mutableStateOf<PendingInvitation?>(null) }

	LoadContent(state, onRetry = viewModel::load) { data ->
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }

		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(stringResource(R.string.volunteers_invite_title), style = MaterialTheme.typography.titleMedium)
					Text(stringResource(R.string.volunteers_invite_help), style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
					FormField(email, R.string.volunteers_email, emailError?.resolve(), !action.busy,
						KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrect = false, imeAction = ImeAction.Done),
						onChange = viewModel::onEmail)
					Button(onClick = viewModel::invite, enabled = !action.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
						if (action.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
						else Text(stringResource(R.string.volunteers_invite))
					}
				}
			}
		}

		item { SectionTitle(R.string.volunteers_pending) }
		if (data.invitations.isEmpty()) {
			item { Text(stringResource(R.string.volunteers_no_pending), color = MaterialTheme.colorScheme.onSurfaceVariant) }
		} else {
			items(data.invitations, key = { "inv-" + it.id }) { invitation ->
				ElevatedCard(Modifier.fillMaxWidth()) {
					Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						Column(Modifier.weight(1f)) {
							Text(invitation.email ?: "—", style = MaterialTheme.typography.titleSmall)
							Text(stringResource(R.string.home_invitation_expires, formatDateTime(invitation.expiresAt)),
								style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
						TextButton(onClick = { confirmRevoke = invitation }, enabled = !action.busy) { Text(stringResource(R.string.volunteers_revoke)) }
					}
				}
			}
		}

		item { SectionTitle(R.string.volunteers_participants_header) }
		item { Text(stringResource(R.string.volunteers_participants, data.participants.size), style = MaterialTheme.typography.bodySmall) }
		if (data.participants.isEmpty()) {
			item { EmptyView(R.string.volunteers_no_participants) }
		} else {
			items(data.participants, key = { "p-" + it.user.id }) { participant -> ParticipantCard(participant) }
		}
	}

	confirmRevoke?.let { invitation ->
		AlertDialog(
			onDismissRequest = { confirmRevoke = null },
			title = { Text(stringResource(R.string.volunteers_revoke)) },
			text = { Text(stringResource(R.string.volunteers_revoke_body, invitation.email ?: "")) },
			confirmButton = {
				TextButton(onClick = { confirmRevoke = null; viewModel.revoke(invitation.id) }) { Text(stringResource(R.string.action_confirm)) }
			},
			dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

@Composable
private fun ParticipantCard(participant: Participant) {
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(participant.user.displayName, style = MaterialTheme.typography.titleSmall)
			Text(
				participant.groups.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: stringResource(R.string.volunteers_no_group),
				style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (participant.openAssignmentCount > 0) {
				Text(stringResource(R.string.volunteers_open_assignments, participant.openAssignmentCount), style = MaterialTheme.typography.bodySmall)
			}
		}
	}
}
