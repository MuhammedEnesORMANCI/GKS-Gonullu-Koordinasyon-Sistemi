package org.gks.android.ui.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.AlertManagementRepository
import org.gks.android.data.ApiResult
import org.gks.android.data.ManagementRepository
import org.gks.android.data.remote.AlertPreviewView
import org.gks.android.data.remote.AlertRecipientRowView
import org.gks.android.data.remote.AlertSummaryView
import org.gks.android.data.remote.EventLocation
import org.gks.android.data.remote.GeneralAlertDraftRequest
import org.gks.android.data.remote.GeneralAlertTargets
import org.gks.android.data.remote.ManagedAlertView
import org.gks.android.data.remote.OperationView
import org.gks.android.data.remote.PersonCandidate
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.toUiText
import org.gks.android.ui.map.GksMap
import org.gks.android.ui.map.MapColors
import org.gks.android.ui.map.MapPoint
import org.gks.android.ui.map.coord
import org.gks.android.ui.map.formatLatLon
import org.gks.android.ui.map.parseCoordinate

const val GENERAL_ALERT_MESSAGE_MAX = 500
const val GENERAL_ALERT_ACTION_MAX = 200

/** ADR-026 Genel Acil Alarm formu: hedefler (kurum geneli yalnızca ADM; operasyonlar), olay yeri, önizleme, güçlü onay. */
data class GeneralAlertState(
	val operations: List<OperationView> = emptyList(),
	val message: String = "",
	val action: String = "",
	val organization: Boolean = false,
	val operationIds: Set<String> = emptySet(),
	/** RAPOR_7 madde 3: kişi seçimi sunucuda aranır ve sayfalanır. */
	val personQuery: String = "",
	val personResults: List<PersonCandidate> = emptyList(),
	val personCursor: String? = null,
	val personLoading: Boolean = false,
	val people: Map<String, PersonCandidate> = emptyMap(),
	val lat: String = "",
	val lon: String = "",
	val busy: Boolean = false,
	val error: UiText? = null,
	val fieldError: Int? = null,
	val preview: AlertPreviewView? = null,
	val confirmed: Boolean = false,
	val sentId: String? = null,
)

class GeneralAlertViewModel(
	private val alerts: AlertManagementRepository,
	private val management: ManagementRepository,
) : ViewModel() {
	private val _state = MutableStateFlow(GeneralAlertState())
	val state: StateFlow<GeneralAlertState> = _state.asStateFlow()

	private var search: Job? = null

	init {
		viewModelScope.launch {
			(management.operations(listOf("ACTIVE")) as? ApiResult.Success)?.let { r -> _state.update { it.copy(operations = r.value) } }
		}
		searchPeople("")
	}

	/** Yazdıkça (300 ms bekleyip) sunucuda arar; önceki arama iptal edilir. */
	fun searchPeople(query: String) {
		_state.update { it.copy(personQuery = query) }
		search?.cancel()
		search = viewModelScope.launch {
			if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MILLIS)
			_state.update { it.copy(personLoading = true) }
			val r = alerts.generalCandidates(query)
			_state.update {
				when (r) {
					is ApiResult.Success -> it.copy(personLoading = false, personResults = r.value.items, personCursor = r.value.nextCursor)
					is ApiResult.Failure -> it.copy(personLoading = false, personResults = emptyList(), personCursor = null, error = r.error.toUiText())
				}
			}
		}
	}

	/** Sonraki sayfa (aynı arama). */
	fun morePeople() {
		val s = _state.value
		val cursor = s.personCursor ?: return
		if (s.personLoading) return
		_state.update { it.copy(personLoading = true) }
		viewModelScope.launch {
			val r = alerts.generalCandidates(s.personQuery, cursor)
			_state.update {
				when (r) {
					is ApiResult.Success -> it.copy(personLoading = false, personResults = it.personResults + r.value.items, personCursor = r.value.nextCursor)
					is ApiResult.Failure -> it.copy(personLoading = false, error = r.error.toUiText())
				}
			}
		}
	}

	fun togglePerson(person: PersonCandidate) = edit {
		copy(people = if (person.id in people) people - person.id else people + (person.id to person))
	}

	fun edit(transform: GeneralAlertState.() -> GeneralAlertState) = _state.update { it.transform().copy(error = null, fieldError = null) }

	fun toggleOperation(id: String) = edit { copy(operationIds = if (id in operationIds) operationIds - id else operationIds + id) }

	/** Taslak + önizleme; yerel denetim sunucununkiyle aynı (mesaj zorunlu, en az bir hedef, olay yeri birlikte/aralıkta). */
	fun preview() {
		val s = _state.value
		if (s.busy) return
		val location = eventLocation(s.lat, s.lon)
		val error = when {
			s.message.isBlank() -> R.string.general_alert_message_required
			s.message.trim().length > GENERAL_ALERT_MESSAGE_MAX -> R.string.general_alert_message_too_long
			s.action.trim().length > GENERAL_ALERT_ACTION_MAX -> R.string.general_alert_action_too_long
			!s.organization && s.operationIds.isEmpty() && s.people.isEmpty() -> R.string.general_alert_targets_required
			location.isFailure -> R.string.area_err_lat
			else -> null
		}
		if (error != null) {
			_state.update { it.copy(fieldError = error) }
			return
		}
		_state.update { it.copy(busy = true) }
		viewModelScope.launch {
			val request = GeneralAlertDraftRequest(
				message = s.message.trim(),
				requestedAction = s.action.trim().ifEmpty { null },
				targets = GeneralAlertTargets(organization = s.organization, operationIds = s.operationIds.toList(), userIds = s.people.keys.toList()),
				eventLocation = location.getOrNull(),
			)
			when (val r = alerts.createGeneral(request)) {
				is ApiResult.Success -> _state.update { it.copy(busy = false, preview = r.value, confirmed = false) }
				is ApiResult.Failure -> _state.update { it.copy(busy = false, error = r.error.toUiText()) }
			}
		}
	}

	fun confirm(checked: Boolean) = _state.update { it.copy(confirmed = checked) }

	fun send(onSent: (String) -> Unit) {
		val s = _state.value
		val preview = s.preview ?: return
		if (!s.confirmed || s.busy) return
		_state.update { it.copy(busy = true) }
		viewModelScope.launch {
			when (val r = alerts.send(preview)) {
				is ApiResult.Success -> {
					_state.update { it.copy(busy = false, sentId = r.value.id) }
					onSent(r.value.id)
				}
				is ApiResult.Failure -> _state.update { it.copy(busy = false, error = r.error.toUiText()) }
			}
		}
	}

	/** Önizlemeden vazgeç: taslak silinir, forma dönülür. */
	fun back() {
		val preview = _state.value.preview ?: return
		_state.update { it.copy(preview = null, confirmed = false) }
		viewModelScope.launch { alerts.discard(preview.alertId) }
	}

	companion object {
		const val SEARCH_DEBOUNCE_MILLIS = 300L

		/** Boş → null; yarım/hatalı → hata. */
		fun eventLocation(lat: String, lon: String): Result<EventLocation?> {
			val la = parseCoordinate(lat)
			val lo = parseCoordinate(lon)
			if (la == null && lo == null) return Result.success(null)
			if (la == null || lo == null || la.isNaN() || lo.isNaN() || la !in -90.0..90.0 || lo !in -180.0..180.0) {
				return Result.failure(IllegalArgumentException("event location"))
			}
			return Result.success(EventLocation(la, lo))
		}
	}
}

@Composable
fun GeneralAlertScreen(viewModel: GeneralAlertViewModel, isAdmin: Boolean, onSent: (String) -> Unit) {
	val s by viewModel.state.collectAsStateWithLifecycle()
	Column(
		modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			s.error?.let { MessageCard(it.resolve(), error = true) }
			val preview = s.preview
			if (preview == null) {
				MessageCard(stringResource(R.string.general_alert_warning), error = true)
				OutlinedTextField(s.message, { v -> viewModel.edit { copy(message = v) } }, label = { Text(stringResource(R.string.general_alert_message)) },
					minLines = 2, modifier = Modifier.fillMaxWidth(), enabled = !s.busy,
					supportingText = { Text("${s.message.trim().length}/$GENERAL_ALERT_MESSAGE_MAX") })
				OutlinedTextField(s.action, { v -> viewModel.edit { copy(action = v) } }, label = { Text(stringResource(R.string.general_alert_action)) },
					modifier = Modifier.fillMaxWidth(), enabled = !s.busy)
				Text(stringResource(R.string.general_alert_targets), style = MaterialTheme.typography.titleMedium)
				if (isAdmin) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(s.organization, { v -> viewModel.edit { copy(organization = v) } }, enabled = !s.busy)
						Text(stringResource(R.string.general_alert_organization))
					}
				}
				if (s.operations.isEmpty()) Text(stringResource(R.string.general_alert_no_operations), color = MaterialTheme.colorScheme.onSurfaceVariant)
				s.operations.forEach { op ->
					Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
						Checkbox(op.id in s.operationIds, { viewModel.toggleOperation(op.id) }, enabled = !s.busy)
						Text(op.name)
					}
				}
				Text(stringResource(R.string.general_alert_people), style = MaterialTheme.typography.titleSmall)
				OutlinedTextField(s.personQuery, viewModel::searchPeople, label = { Text(stringResource(R.string.general_alert_people_search)) },
					singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !s.busy,
					supportingText = { Text(stringResource(if (isAdmin) R.string.general_alert_people_hint_adm else R.string.general_alert_people_hint_kor)) })
				if (s.people.isNotEmpty()) Text(stringResource(R.string.general_alert_people_selected, s.people.size, s.people.values.joinToString { it.displayName }))
				(s.people.values.filter { p -> s.personResults.none { it.id == p.id } } + s.personResults).forEach { p ->
					Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
						Checkbox(p.id in s.people, { viewModel.togglePerson(p) }, enabled = !s.busy)
						Text(if (p.fullName != null && p.fullName != p.displayName) "${p.displayName} — ${p.fullName}" else p.displayName)
					}
				}
				if (!s.personLoading && s.personResults.isEmpty()) {
					Text(stringResource(R.string.general_alert_people_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				if (s.personCursor != null) {
					OutlinedButton(onClick = viewModel::morePeople, enabled = !s.personLoading && !s.busy) { Text(stringResource(R.string.load_more_people)) }
				}
				Text(stringResource(R.string.event_location_optional), style = MaterialTheme.typography.titleMedium)
				val loc = GeneralAlertViewModel.eventLocation(s.lat, s.lon).getOrNull()
				GksMap(
					points = loc?.let { listOf(MapPoint("event", it.lat, it.lon, MapColors.ALERT, "")) }.orEmpty(),
					height = 200.dp,
					description = stringResource(R.string.event_location),
					onPick = { la, lo -> if (!s.busy) viewModel.edit { copy(lat = coord(la), lon = coord(lo)) } },
				)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(s.lat, { v -> viewModel.edit { copy(lat = v) } }, label = { Text(stringResource(R.string.area_lat)) },
						modifier = Modifier.weight(1f), enabled = !s.busy)
					OutlinedTextField(s.lon, { v -> viewModel.edit { copy(lon = v) } }, label = { Text(stringResource(R.string.area_lon)) },
						modifier = Modifier.weight(1f), enabled = !s.busy)
				}
				s.fieldError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
				Button(onClick = viewModel::preview, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
					Text(stringResource(R.string.general_alert_preview))
				}
			} else {
				Text(stringResource(R.string.general_alert_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
				Text(preview.message.orEmpty(), style = MaterialTheme.typography.headlineSmall)
				preview.requestedAction?.let { Text(stringResource(R.string.alert_requested_action, it)) }
				MessageCard(stringResource(R.string.general_alert_recipient_count, preview.recipientCount), error = true)
				ElevatedCard(Modifier.fillMaxWidth()) {
					Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						preview.recipients.take(100).forEach { Text(it.displayName.orEmpty()) }
						if (preview.recipients.size > 100) Text("… +${preview.recipients.size - 100}")
					}
				}
				Row(verticalAlignment = Alignment.CenterVertically) {
					Checkbox(s.confirmed, viewModel::confirm, enabled = !s.busy)
					Text(stringResource(R.string.general_alert_confirm, preview.recipientCount))
				}
				Button(
					onClick = { viewModel.send(onSent) },
					enabled = s.confirmed && !s.busy,
					colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
					modifier = Modifier.fillMaxWidth().height(56.dp),
				) { Text(stringResource(R.string.general_alert_send)) }
				OutlinedButton(onClick = viewModel::back, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.general_alert_back))
				}
			}
		}
	}
}

/** Görev 24e: KOR/ADM açık alarmlar (operasyon + genel). */
class ManagedAlertsViewModel(private val alerts: AlertManagementRepository) : LoadViewModel<List<AlertSummaryView>>() {
	init { load() }

	override suspend fun fetch() = alerts.openAlerts()
}

@Composable
fun ManagedAlertsScreen(viewModel: ManagedAlertsViewModel, onOpen: (String) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { list ->
		if (list.isEmpty()) item { EmptyView(R.string.alerts_managed_empty) }
		items(list, key = { it.id }) { a ->
			ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(a.id) }) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(if (a.scope == "GENERAL") stringResource(R.string.general_alert_title) else a.operationName.orEmpty(),
						style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
					Text(a.message, style = MaterialTheme.typography.titleMedium)
					Text(countsLine(a.counts.responded, a.counts.pending + a.counts.unanswered + a.counts.failed, a.counts.needHelp),
						style = MaterialTheme.typography.bodyMedium)
				}
			}
		}
	}
}

@Composable
private fun countsLine(responded: Int, waiting: Int, needHelp: Int) =
	stringResource(R.string.alert_follow_counts, responded, waiting, needHelp)

/** Görev 24e: alarm takip ekranı: yanıtlayan / bekleyen / yardım isteyen; ADM ya da yetkili KOR kapatabilir. */
data class AlertFollowUp(val alert: ManagedAlertView, val recipients: List<AlertRecipientRowView>)

class AlertFollowUpViewModel(private val alerts: AlertManagementRepository, private val alertId: String) : LoadViewModel<AlertFollowUp>() {
	private val _notice = MutableStateFlow<UiText?>(null)
	val notice: StateFlow<UiText?> = _notice.asStateFlow()

	init { load() }

	override suspend fun fetch(): ApiResult<AlertFollowUp> {
		val alert = when (val r = alerts.alert(alertId)) {
			is ApiResult.Success -> r.value
			is ApiResult.Failure -> return r
		}
		return when (val r = alerts.recipients(alertId)) {
			is ApiResult.Success -> ApiResult.Success(AlertFollowUp(alert, r.value))
			is ApiResult.Failure -> r
		}
	}

	fun close() {
		viewModelScope.launch {
			when (val r = alerts.close(alertId)) {
				is ApiResult.Success -> load()
				is ApiResult.Failure -> _notice.value = r.error.toUiText()
			}
		}
	}

	companion object {
		/** Takip grupları: yardım isteyen ⊂ yanıtlayan; bekleyen = henüz yanıt yok (gönderildi/iletildi/görüldü/yanıtsız/başarısız). */
		fun groups(rows: List<AlertRecipientRowView>): Triple<List<AlertRecipientRowView>, List<AlertRecipientRowView>, List<AlertRecipientRowView>> {
			val needHelp = rows.filter { it.responseOption == "NEED_HELP" }
			val responded = rows.filter { it.state == "RESPONDED" && it.responseOption != "NEED_HELP" }
			val waiting = rows.filter { it.state != "RESPONDED" }
			return Triple(needHelp, responded, waiting)
		}
	}
}

@Composable
fun AlertFollowUpScreen(viewModel: AlertFollowUpViewModel, onOpenChannel: ((String) -> Unit)? = null) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val notice by viewModel.notice.collectAsStateWithLifecycle()
	var confirmClose by remember { mutableStateOf(false) }
	LoadContent(state, onRetry = viewModel::load) { data ->
		val a = data.alert
		item {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(if (a.scope == "GENERAL") stringResource(R.string.general_alert_title) else stringResource(R.string.alert_title, a.operationName.orEmpty()),
					style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
				Text(a.message, style = MaterialTheme.typography.headlineSmall)
				a.requestedAction?.let { Text(stringResource(R.string.alert_requested_action, it)) }
				a.sentAt?.let { Text(formatDateTime(it) + (a.sentBy?.displayName?.let { n -> " · $n" } ?: ""), style = MaterialTheme.typography.bodySmall) }
				a.eventLocation?.let { Text(stringResource(R.string.event_location) + ": " + formatLatLon(it.lat, it.lon)) }
				Text(stringResource(R.string.alert_follow_counts, a.counts.responded, a.counts.pending + a.counts.unanswered + a.counts.failed,
					a.counts.needHelp), style = MaterialTheme.typography.titleMedium)
				Text(stringResource(when (a.state) {
					"OPEN" -> R.string.alert_state_open
					"CANCELLED" -> R.string.alert_cancelled
					else -> R.string.alert_closed
				}))
			}
		}
		notice?.let { item { MessageCard(it.resolve(), error = true) } }
		val opId = a.operationId
		if (opId != null && onOpenChannel != null) {
			item {
				OutlinedButton(onClick = { onOpenChannel(opId) }, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.open_operation_channel))
				}
			}
		}
		if (a.state == "OPEN") {
			item {
				Button(onClick = { confirmClose = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
					Text(stringResource(R.string.alert_close_action))
				}
			}
		}
		val (needHelp, responded, waiting) = AlertFollowUpViewModel.groups(data.recipients)
		item { Text(stringResource(R.string.alert_follow_need_help, needHelp.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) }
		items(needHelp, key = { "h-" + it.id }) { RecipientRow(it) }
		item { Text(stringResource(R.string.alert_follow_responded, responded.size), style = MaterialTheme.typography.titleMedium) }
		items(responded, key = { "r-" + it.id }) { RecipientRow(it) }
		item { Text(stringResource(R.string.alert_follow_waiting, waiting.size), style = MaterialTheme.typography.titleMedium) }
		items(waiting, key = { "w-" + it.id }) { RecipientRow(it) }
	}
	if (confirmClose) {
		AlertDialog(
			onDismissRequest = { confirmClose = false },
			title = { Text(stringResource(R.string.alert_close_action)) },
			text = { Text(stringResource(R.string.alert_close_confirm)) },
			confirmButton = { TextButton(onClick = { confirmClose = false; viewModel.close() }) { Text(stringResource(R.string.alert_close_action)) } },
			dismissButton = { TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.action_cancel)) } },
		)
	}
}

@Composable
private fun RecipientRow(r: AlertRecipientRowView) {
	Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
		Text(r.user.displayName)
		Text(
			r.responseOption?.let { stringResource(responseLabelRes(it)) } ?: stringResource(R.string.alert_follow_no_response),
			color = if (r.responseOption == "NEED_HELP") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
