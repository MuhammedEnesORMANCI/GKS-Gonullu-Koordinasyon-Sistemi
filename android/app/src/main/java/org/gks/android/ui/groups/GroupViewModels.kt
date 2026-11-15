package org.gks.android.ui.groups

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.VolunteerRepository
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.GroupView
import org.gks.android.data.remote.MeetingPointRequest
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.DateInput
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** GR-02: aktif üyeliklerim. Ana sayfa da bu yükleyiciyi kullanır. */
open class GroupsViewModel(protected val volunteer: VolunteerRepository) : LoadViewModel<List<GroupSummary>>() {
	init { load() }

	override suspend fun fetch() = volunteer.groups()
}

/** GR-03 + liderin GR-07 / GR-08 güncellemeleri. */
open class GroupDetailViewModel(private val repository: VolunteerRepository, protected val groupId: String) : LoadViewModel<GroupView>() {

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()

	init { load() }

	override suspend fun fetch() = repository.group(groupId)

	/** @return yerel doğrulama hatası; `null` ise istek gönderildi */
	fun saveDescription(description: String): UiText? {
		if (description.trim().length > MAX_DESCRIPTION) return text(R.string.group_description_too_long)
		run(R.string.group_description_saved) { repository.updateGroupDescription(groupId, description) }
		return null
	}

	/** Zaman `gg.aa.yyyy ss:dd` (cihaz saat dilimi) olarak girilir, sunucuya ISO-8601 UTC gider. */
	fun saveMeetingPoint(address: String, description: String, time: String): UiText? {
		val instant = DateInput.parse(time) ?: return text(R.string.meeting_point_time_invalid)
		when {
			address.isBlank() -> return text(R.string.meeting_point_address_required)
			address.trim().length > MAX_ADDRESS -> return text(R.string.meeting_point_address_too_long)
			description.trim().length > MAX_MEETING_DESCRIPTION -> return text(R.string.meeting_point_description_too_long)
		}
		val request = MeetingPointRequest(address.trim(), description.trim().takeIf { it.isNotEmpty() }, instant.toString())
		run(R.string.meeting_point_saved) { repository.updateMeetingPoint(groupId, request) }
		return null
	}

	/** Alt sınıfın işlem hata eşlemesi (yönetim ekranı); lider ekranında boş. */
	protected open val errorMessages: Map<String, Int> get() = emptyMap()

	protected fun run(success: Int, block: suspend () -> ApiResult<*>) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			_action.value = when (val result = block()) {
				is ApiResult.Success -> ActionState(notice = text(success))
				is ApiResult.Failure -> ActionState(error = result.error.toUiText(errorMessages))
			}
			load()
		}
	}

	companion object {
		const val MAX_DESCRIPTION = 1000
		const val MAX_ADDRESS = 300
		const val MAX_MEETING_DESCRIPTION = 500
	}
}
