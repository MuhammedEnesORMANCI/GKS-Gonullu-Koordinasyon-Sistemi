package org.gks.android.ui.tasks

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.VolunteerRepository
import org.gks.android.data.remote.MyAssignmentView
import org.gks.android.data.remote.TaskView
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** Görev görevin yanıtlanabileceği durumda mı (TK-11/12: `PUBLISHED`/`IN_PROGRESS`). */
fun isOpenTask(state: String) = state == "PUBLISHED" || state == "IN_PROGRESS"

/** Görevler sekmesinin bölümleri. */
data class AssignmentSections(
	val waiting: List<MyAssignmentView>,
	val accepted: List<MyAssignmentView>,
	val past: List<MyAssignmentView>,
) {
	val isEmpty get() = waiting.isEmpty() && accepted.isEmpty() && past.isEmpty()

	companion object {
		fun of(items: List<MyAssignmentView>): AssignmentSections {
			val (open, closed) = items.partition { isOpenTask(it.task.state) }
			return AssignmentSections(
				waiting = open.filter { it.status == "PENDING" }.sortedBy { it.respondBy },
				accepted = open.filter { it.status == "ACCEPTED" },
				past = closed + open.filter { it.status != "PENDING" && it.status != "ACCEPTED" },
			)
		}
	}
}

/** TK-10: bana atanmış tüm görevler. */
class TasksViewModel(private val repository: VolunteerRepository) : LoadViewModel<AssignmentSections>() {
	init { load() }

	override suspend fun fetch() = when (val result = repository.myAssignments()) {
		is ApiResult.Success -> ApiResult.Success(AssignmentSections.of(result.value))
		is ApiResult.Failure -> result
	}
}

/** TK-08 + TK-11/12. */
class TaskDetailViewModel(private val repository: VolunteerRepository, private val taskId: String) : LoadViewModel<TaskView>() {

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()

	init { load() }

	override suspend fun fetch() = repository.task(taskId)

	fun respond(assignmentId: String, accept: Boolean) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			val result = repository.respondToAssignment(assignmentId, accept)
			_action.value = when (result) {
				is ApiResult.Success -> ActionState(notice = text(if (accept) R.string.task_accepted_notice else R.string.task_rejected_notice))
				is ApiResult.Failure -> ActionState(error = result.error.toUiText(RESPONSE_ERRORS))
			}
			// Başarıda da 409'da da güncel durum sunucudan okunur (ör. kapasite başkası tarafından dolduruldu).
			load()
		}
	}

	private companion object {
		val RESPONSE_ERRORS = mapOf(
			"CAPACITY_FULL" to R.string.task_capacity_full,
			"ASSIGNMENT_NOT_PENDING" to R.string.task_already_answered,
			"INVALID_STATE_TRANSITION" to R.string.task_not_open,
		)
	}
}
