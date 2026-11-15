package org.gks.android.ui.home

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiError
import org.gks.android.data.ApiResult
import org.gks.android.data.InvitationRepository
import org.gks.android.data.VolunteerRepository
import org.gks.android.data.remote.OperationView
import org.gks.android.data.remote.TaskSummary
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText
import org.gks.android.ui.groups.GroupsViewModel

/** Ana sayfa: bağlam `AppViewModel`'den gelir; burada grup özetleri (lider bilgisi) ve davet yanıtı. */
class HomeViewModel(
	volunteer: VolunteerRepository,
	private val invitations: InvitationRepository,
) : GroupsViewModel(volunteer) {

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()

	/** ID-17/18. Başarıda [onChanged] bağlamı yeniden çeker (katılım ve rol değişir). */
	fun respond(invitationId: String, accept: Boolean, onChanged: () -> Unit) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			when (val result = invitations.respond(invitationId, accept)) {
				is ApiResult.Success -> {
					_action.value = ActionState(notice = text(if (accept) R.string.invitation_accepted_notice else R.string.invitation_declined_notice))
					onChanged()
					load()
				}
				is ApiResult.Failure -> {
					_action.value = ActionState(error = result.error.toUiText(INVITATION_ERRORS))
					// Süresi dolan / kapanan davet listeden düşsün.
					if (result.error is ApiError.Http) onChanged()
				}
			}
		}
	}

	private companion object {
		val INVITATION_ERRORS = mapOf("INVITATION_INVALID" to R.string.invitation_invalid)
	}
}

data class OperationDetail(val operation: OperationView, val tasks: List<TaskSummary>)

/** OR-05 + TK-07 (gönüllü izdüşümü: yalnızca bana atanmış görevler). */
class OperationDetailViewModel(private val repository: VolunteerRepository, private val operationId: String) :
	LoadViewModel<OperationDetail>() {

	init { load() }

	override suspend fun fetch(): ApiResult<OperationDetail> {
		val operation = when (val result = repository.operation(operationId)) {
			is ApiResult.Success -> result.value
			is ApiResult.Failure -> return result
		}
		return when (val tasks = repository.operationTasks(operationId)) {
			is ApiResult.Success -> ApiResult.Success(OperationDetail(operation, tasks.value))
			is ApiResult.Failure -> tasks
		}
	}
}
