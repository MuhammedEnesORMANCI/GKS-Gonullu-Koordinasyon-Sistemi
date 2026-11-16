package org.gks.android.ui.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.LocationRepository
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** Bir operasyondaki kendi paylaşım durumum (LC-04) ve başlat/durdur (LC-01/02). */
class LocationSharingViewModel(private val repository: LocationRepository, private val operationId: String) : ViewModel() {

	data class State(val sharing: Boolean = false, val unavailable: Boolean = false, val lastSentAt: String? = null,
		val busy: Boolean = false, val error: UiText? = null)

	private val _state = MutableStateFlow(State())
	val state: StateFlow<State> = _state.asStateFlow()

	init { refresh() }

	fun refresh() {
		viewModelScope.launch {
			val mine = (repository.mine() as? ApiResult.Success)?.value ?: return@launch
			val row = mine.firstOrNull { it.operationId == operationId }
			_state.value = _state.value.copy(
				sharing = row?.state == "ACTIVE" || row?.state == "UNAVAILABLE",
				unavailable = row?.state == "UNAVAILABLE",
				lastSentAt = row?.lastSentAt,
			)
		}
	}

	/** İzinler alındıktan sonra çağrılır; başarıda ön plan servisi `onStarted` ile başlatılır. */
	fun start(onStarted: () -> Unit) {
		if (_state.value.busy) return
		_state.value = _state.value.copy(busy = true, error = null)
		viewModelScope.launch {
			_state.value = when (val result = repository.start(operationId)) {
				is ApiResult.Success -> { onStarted(); _state.value.copy(busy = false, sharing = true, unavailable = false) }
				is ApiResult.Failure -> _state.value.copy(busy = false, error = result.error.toUiText(ERRORS))
			}
		}
	}

	fun stop(onStopped: () -> Unit) {
		if (_state.value.busy) return
		_state.value = _state.value.copy(busy = true, error = null)
		onStopped()   // servis hemen durur; sunucu çağrısı ağ yoksa da paylaşım cihazda biter
		viewModelScope.launch {
			_state.value = when (val result = repository.stop(operationId)) {
				is ApiResult.Success -> _state.value.copy(busy = false, sharing = false)
				is ApiResult.Failure -> _state.value.copy(busy = false, sharing = false, error = result.error.toUiText())
			}
		}
	}

	fun permissionDenied() {
		_state.value = _state.value.copy(error = text(R.string.location_permission_denied))
	}

	private companion object {
		val ERRORS = mapOf(
			"OPERATION_NOT_ACTIVE" to R.string.location_operation_not_active,
			"NOT_A_PARTICIPANT" to R.string.location_not_participant,
		)
	}
}
