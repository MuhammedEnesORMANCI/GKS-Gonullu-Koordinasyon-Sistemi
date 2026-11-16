package org.gks.android.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.data.remote.MyAlertView
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve

fun responseLabelRes(option: String): Int = when (option) {
	"RECEIVED" -> R.string.alert_response_received
	"NEED_HELP" -> R.string.alert_response_need_help
	"ON_MY_WAY" -> R.string.alert_response_on_my_way
	else -> R.string.alert_response_received
}

/** AND-03: açık alarm bandı (her sekmenin üstünde); dokununca alarm ekranı açılır. */
@Composable
fun OpenAlertsBand(alerts: List<MyAlertView>, onOpen: (String) -> Unit) {
	if (alerts.isEmpty()) return
	Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }) {
		Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			alerts.forEach { alert ->
				val answered = alert.recipient.responseOption != null
				Button(
					onClick = { onOpen(alert.id) },
					colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
					modifier = Modifier.fillMaxWidth(),
				) {
					Text(
						stringResource(if (answered) R.string.alert_band_answered else R.string.alert_band_open, alert.message),
						maxLines = 2,
					)
				}
			}
		}
	}
}

/** AND-04: alarm ekranı; üç sabit yanıt (K-13), alarm açıkken değiştirilebilir (TD-07). */
@Composable
fun AlertDetailScreen(viewModel: AlertDetailViewModel, onResponded: () -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val action by viewModel.action.collectAsStateWithLifecycle()
	val sounding by viewModel.sounding.collectAsStateWithLifecycle()
	// Yanıt iletilince alarm bandı tazelenir.
	LaunchedEffect(action.notice) { if (action.notice != null) onResponded() }
	LoadContent(state, onRetry = viewModel::load) { alert ->
		item {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(stringResource(R.string.alert_title, alert.operationName.orEmpty()), style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.error)
				Text(alert.message, style = MaterialTheme.typography.headlineSmall)
				alert.requestedAction?.let { Text(stringResource(R.string.alert_requested_action, it)) }
				alert.sentAt?.let { Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall) }
			}
		}
		if (sounding && alert.state == "OPEN") {
			item {
				OutlinedButton(onClick = viewModel::mute, modifier = Modifier.fillMaxWidth().height(48.dp)) {
					Text(stringResource(R.string.alarm_mute))
				}
			}
		}
		action.notice?.let { item { MessageCard(it.resolve(), error = false) } }
		action.error?.let { item { MessageCard(it.resolve(), error = true) } }
		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					when (alert.state) {
						"OPEN" -> {
							alert.recipient.responseOption?.let {
								Text(stringResource(R.string.alert_my_response, stringResource(responseLabelRes(it))))
							}
							alert.responseOptions.forEach { option ->
								val chosen = option == alert.recipient.responseOption
								val onClick = { viewModel.respond(alert.recipient.id, option) }
								val modifier = Modifier.fillMaxWidth().height(56.dp)
								if (option == "NEED_HELP") {
									Button(onClick = onClick, enabled = !action.busy, modifier = modifier,
										colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
										ResponseLabel(option, chosen, action.busy)
									}
								} else if (chosen) {
									Button(onClick = onClick, enabled = !action.busy, modifier = modifier) { ResponseLabel(option, true, action.busy) }
								} else {
									OutlinedButton(onClick = onClick, enabled = !action.busy, modifier = modifier) { ResponseLabel(option, false, action.busy) }
								}
							}
						}
						"CANCELLED" -> Text(stringResource(R.string.alert_cancelled), style = MaterialTheme.typography.titleMedium)
						else -> Text(stringResource(R.string.alert_closed), style = MaterialTheme.typography.titleMedium)
					}
				}
			}
		}
	}
}

@Composable
private fun ResponseLabel(option: String, chosen: Boolean, busy: Boolean) {
	if (busy) {
		CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
	} else {
		Text(stringResource(responseLabelRes(option)) + if (chosen) " ✓" else "")
	}
}
