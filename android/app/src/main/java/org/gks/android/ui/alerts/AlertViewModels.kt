package org.gks.android.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.AlertRepository
import org.gks.android.data.ApiResult
import org.gks.android.data.remote.MyAlertView
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/**
 * Açık alarmlarım (AL-08; AND-03 alarm bandı). FCM yokken ön plandayken yoklanır. Yoklamada ilk kez görülen
 * (`SENT`) alarm için bir kez "alındı" sinyali gider (AL-13; otomatik tekrar yok, LD-10).
 */
class OpenAlertsViewModel(private val repository: AlertRepository) : ViewModel() {

	private val _alerts = MutableStateFlow<List<MyAlertView>>(emptyList())
	val alerts: StateFlow<List<MyAlertView>> = _alerts.asStateFlow()
	private val signalled = mutableSetOf<String>()

	fun refresh() {
		viewModelScope.launch {
			val result = repository.openAlerts() as? ApiResult.Success ?: return@launch   // hata listeyi silmez
			_alerts.value = result.value
			result.value.filter { it.recipient.state == "SENT" && signalled.add(it.recipient.id) }
				.forEach { repository.markDelivered(it.recipient.id) }
		}
	}

	companion object {
		const val POLL_MILLIS = 15_000L
	}
}

/** AL-07 + AL-14 (açılışta "görüldü") + AL-15 (yanıt ve değişikliği). */
class AlertDetailViewModel(private val repository: AlertRepository, private val alertId: String) : LoadViewModel<MyAlertView>() {

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()
	private var seenSent = false

	init { load() }

	override suspend fun fetch(): ApiResult<MyAlertView> {
		val result = repository.alert(alertId)
		if (result is ApiResult.Success && !seenSent && result.value.state == "OPEN" &&
			result.value.recipient.state in setOf("SENT", "DELIVERED")) {
			seenSent = true
			repository.markSeen(result.value.recipient.id)
		}
		return result
	}

	fun respond(recipientId: String, option: String) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			_action.value = when (val result = repository.respond(recipientId, option)) {
				is ApiResult.Success -> ActionState(notice = text(R.string.alert_response_sent))
				is ApiResult.Failure -> ActionState(error = result.error.toUiText(ERRORS))
			}
			load()
		}
	}

	private companion object {
		val ERRORS = mapOf("ALERT_NOT_OPEN" to R.string.alert_not_open)
	}
}
