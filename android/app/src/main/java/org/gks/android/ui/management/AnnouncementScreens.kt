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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.gks.android.data.AnnouncementRepository
import org.gks.android.data.ApiError
import org.gks.android.data.ApiResult
import org.gks.android.data.ManagementRepository
import org.gks.android.data.problem
import org.gks.android.data.remote.AnnouncementPreview
import org.gks.android.data.remote.AnnouncementView
import org.gks.android.data.remote.GroupSummary
import org.gks.android.data.remote.Participant
import org.gks.android.domain.AnnouncementFieldError
import org.gks.android.domain.AudienceKind
import org.gks.android.domain.audienceOf
import org.gks.android.domain.filterPeople
import org.gks.android.domain.validateAnnouncement
import org.gks.android.ui.account.fieldErrors
import org.gks.android.ui.common.EmptyView
import org.gks.android.ui.common.Fact
import org.gks.android.ui.common.LoadContent
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.SectionTitle
import org.gks.android.ui.common.TitleRow
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** Duyuru hata kodları (backend §11; N-19, AnnouncementService). */
private val ANNOUNCEMENT_ERRORS = mapOf(
	"OPERATION_NOT_ACTIVE" to R.string.announcement_error_not_active,
	"FORBIDDEN" to R.string.announcement_error_forbidden,   // kapsam dışı kişi/grup (operasyon dışı, arşivli) veya yetkisiz
	"CLIENT_NOT_ALLOWED" to R.string.management_session_required,
)

/**
 * Duyuru hatası → ileti. `422 VALIDATION_FAILED` alan kodları (`CONFIRMATION_REQUIRED`, `EMPTY_AUDIENCE`) için yerel
 * Türkçe ileti; diğerleri ortak eşleme (sunucunun Türkçe alan iletisi dahil).
 */
fun announcementError(error: ApiError): UiText = when (error.problem?.errors?.firstOrNull()?.code) {
	"CONFIRMATION_REQUIRED" -> text(R.string.announcement_error_confirmation)
	"EMPTY_AUDIENCE" -> text(R.string.announcement_error_empty)
	else -> error.toUiText(ANNOUNCEMENT_ERRORS)
}

fun AnnouncementFieldError.message(): UiText = text(when (this) {
	AnnouncementFieldError.TITLE_REQUIRED -> R.string.announcement_error_title_required
	AnnouncementFieldError.TITLE_TOO_SHORT -> R.string.announcement_error_title_short
	AnnouncementFieldError.TITLE_TOO_LONG -> R.string.announcement_error_title_long
	AnnouncementFieldError.BODY_REQUIRED -> R.string.announcement_error_body_required
	AnnouncementFieldError.BODY_TOO_LONG -> R.string.announcement_error_body_long
})

// ---------------------------------------------------------------- form (NT-01, NT-02)

data class AnnouncementFormState(
	val loading: Boolean = true,
	val loadError: UiText? = null,
	/** `false`: operasyon `ACTIVE` değil; gönderim kapalı, geçmiş görünür (N-19). */
	val operationActive: Boolean = true,
	val groups: List<GroupSummary> = emptyList(),
	val participants: List<Participant> = emptyList(),
	val kind: AudienceKind = AudienceKind.WHOLE_OPERATION,
	val groupIds: Set<String> = emptySet(),
	val userIds: Set<String> = emptySet(),
	val query: String = "",
	val title: String = "",
	val body: String = "",
	val preview: AnnouncementPreview? = null,
	val confirm: Boolean = false,
	val busy: Boolean = false,
	val error: UiText? = null,
	val notice: UiText? = null,
	val fieldErrors: Map<String, UiText> = emptyMap(),
)

/**
 * @param operationId `null` → kurum geneli duyuru (yalnızca ADM). Hedef türü veya seçim değişince önizleme ve onay
 * sıfırlanır; seçim gerektiren türde seçim yoksa önizleme ve gönderim kapalıdır.
 */
class AnnouncementFormViewModel(
	private val announcements: AnnouncementRepository,
	private val management: ManagementRepository,
	val operationId: String?,
) : ViewModel() {

	private val _state = MutableStateFlow(AnnouncementFormState(loading = operationId != null))
	val state: StateFlow<AnnouncementFormState> = _state.asStateFlow()

	init { load() }

	fun load() {
		val op = operationId ?: return
		_state.update { it.copy(loading = true, loadError = null) }
		viewModelScope.launch {
			val operation = management.operation(op)
			val groups = management.groups(op)
			val participants = management.participants(op)   // tüm sayfalar (ApiCaller.allPages)
			val failure = listOf(operation, groups, participants).filterIsInstance<ApiResult.Failure>().firstOrNull()
			_state.update {
				if (failure != null) it.copy(loading = false, loadError = failure.error.toUiText(ANNOUNCEMENT_ERRORS))
				else it.copy(
					loading = false,
					operationActive = (operation as ApiResult.Success).value.state == "ACTIVE",
					groups = (groups as ApiResult.Success).value.filter { g -> !g.archived },
					participants = (participants as ApiResult.Success).value,
				)
			}
		}
	}

	private val audience get() = _state.value.let { audienceOf(operationId, it.kind, it.groupIds, it.userIds) }

	val hasSelection: Boolean get() = !audience.isEmpty

	/** Hedef değişikliği: önizleme ve onay geçersizleşir. */
	private fun target(change: AnnouncementFormState.() -> AnnouncementFormState) =
		_state.update { it.change().copy(preview = null, confirm = false, error = null, notice = null) }

	fun selectKind(kind: AudienceKind) = target { copy(kind = kind) }

	fun toggleGroup(id: String) = target { copy(groupIds = if (id in groupIds) groupIds - id else groupIds + id) }

	fun togglePerson(id: String) = target { copy(userIds = if (id in userIds) userIds - id else userIds + id) }

	fun search(query: String) = _state.update { it.copy(query = query) }

	fun editTitle(value: String) = _state.update { it.copy(title = value, fieldErrors = it.fieldErrors - "title", notice = null) }

	fun editBody(value: String) = _state.update { it.copy(body = value, fieldErrors = it.fieldErrors - "body", notice = null) }

	fun setConfirm(value: Boolean) = _state.update { it.copy(confirm = value, error = null) }

	fun preview() {
		val s = _state.value
		if (s.busy || !hasSelection || !s.operationActive) return
		val requested = audience
		_state.update { it.copy(busy = true, error = null, notice = null) }
		viewModelScope.launch {
			val result = announcements.preview(requested)
			_state.update {
				if (audience != requested) return@update it.copy(busy = false)   // hedef bu arada değişti
				when (result) {
					is ApiResult.Success -> it.copy(busy = false, preview = result.value, confirm = false)
					is ApiResult.Failure -> it.copy(busy = false, preview = null, error = announcementError(result.error))
				}
			}
		}
	}

	fun send(onSent: () -> Unit = {}) {
		val s = _state.value
		if (s.busy || !s.operationActive) return
		val local = validateAnnouncement(s.title, s.body).mapValues { it.value.message() }
		if (local.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = local) }
			return
		}
		val preview = s.preview ?: run {
			_state.update { it.copy(error = text(R.string.announcement_preview_first)) }
			return
		}
		if (preview.confirmationRequired && !s.confirm) {
			_state.update { it.copy(error = text(R.string.announcement_error_confirmation)) }
			return
		}
		_state.update { it.copy(busy = true, error = null, notice = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			when (val result = announcements.send(audience, s.title, s.body, preview.confirmationRequired && s.confirm)) {
				is ApiResult.Success -> {
					_state.update { it.copy(busy = false, title = "", body = "", preview = null, confirm = false, notice = text(R.string.announcement_sent)) }
					onSent()
				}
				is ApiResult.Failure -> {
					val fields = result.error.fieldErrors(setOf("title", "body"))
					_state.update { it.copy(busy = false, fieldErrors = fields, error = if (fields.isEmpty()) announcementError(result.error) else null) }
				}
			}
		}
	}
}

// ---------------------------------------------------------------- geçmiş (NT-03) ve ayrıntı (NT-06)

class AnnouncementHistoryViewModel(private val announcements: AnnouncementRepository, private val operationId: String?) :
	LoadViewModel<List<AnnouncementView>>() {
	init { load() }

	override suspend fun fetch() = announcements.history(operationId)
}

class AnnouncementDetailViewModel(private val announcements: AnnouncementRepository, private val id: String) :
	LoadViewModel<AnnouncementView>() {
	init { load() }

	override suspend fun fetch() = announcements.announcement(id)
}

// ---------------------------------------------------------------- ekranlar

@Composable
fun AnnouncementsScreen(
	form: AnnouncementFormViewModel,
	history: AnnouncementHistoryViewModel,
	canSend: Boolean,
	onOpen: (String) -> Unit,
) {
	val state by form.state.collectAsStateWithLifecycle()
	val past by history.state.collectAsStateWithLifecycle()
	if (state.loading) {
		LoadingView()
		return
	}
	LoadContent(past, onRetry = history::load) { items ->
		state.loadError?.let { item { MessageCard(it.resolve(), error = true) } }
		if (!canSend) {
			item { MessageCard(stringResource(R.string.management_forbidden), error = false) }
		} else if (!state.operationActive) {
			item { MessageCard(stringResource(R.string.announcement_operation_not_active), error = false) }
		} else {
			item { AnnouncementForm(form, state, onSent = history::load) }
		}
		item { SectionTitle(R.string.announcement_history) }
		if (items.isEmpty()) {
			item { EmptyView(R.string.announcement_history_empty) }
		} else {
			items(items, key = { it.id }) { a ->
				ElevatedCard(onClick = { onOpen(a.id) }, modifier = Modifier.fillMaxWidth()) {
					Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Text(a.title, style = MaterialTheme.typography.titleSmall)
						Text(
							listOfNotNull(
								a.createdAt?.let { formatDateTime(it) },
								a.operationName ?: if (a.operationId == null) stringResource(R.string.announcement_scope_organization) else null,
								stringResource(R.string.announcement_recipients, a.recipientCount),
							).joinToString(" · "),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun AnnouncementForm(form: AnnouncementFormViewModel, state: AnnouncementFormState, onSent: () -> Unit) {
	val enabled = !state.busy
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(stringResource(R.string.announcement_new), style = MaterialTheme.typography.titleMedium)
			state.notice?.let { MessageCard(it.resolve(), error = false) }
			state.error?.let { MessageCard(it.resolve(), error = true) }

			Text(stringResource(R.string.announcement_target), style = MaterialTheme.typography.labelLarge)
			if (form.operationId == null) {
				Text(stringResource(R.string.announcement_target_organization), style = MaterialTheme.typography.bodyMedium)
			} else {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					AudienceKind.entries.forEach { kind ->
						FilterChip(selected = state.kind == kind, onClick = { form.selectKind(kind) }, enabled = enabled,
							label = { Text(stringResource(kindLabel(kind))) })
					}
				}
				when (state.kind) {
					AudienceKind.WHOLE_OPERATION ->
						Text(stringResource(R.string.announcement_target_operation_help), style = MaterialTheme.typography.bodySmall)
					AudienceKind.GROUPS -> {
						if (state.groups.isEmpty()) Text(stringResource(R.string.announcement_groups_empty))
						state.groups.forEach { g ->
							Row(verticalAlignment = Alignment.CenterVertically) {
								Checkbox(checked = g.id in state.groupIds, onCheckedChange = { form.toggleGroup(g.id) }, enabled = enabled)
								Text(stringResource(R.string.announcement_group_line, g.name, g.memberCount))
							}
						}
					}
					AudienceKind.PEOPLE -> {
						if (state.participants.isEmpty()) {
							MessageCard(stringResource(R.string.group_no_participants), error = false)
						} else {
							OutlinedTextField(
								value = state.query,
								onValueChange = form::search,
								label = { Text(stringResource(R.string.announcement_people_search)) },
								singleLine = true,
								enabled = enabled,
								supportingText = { Text(stringResource(R.string.announcement_selected_count, state.userIds.size)) },
								modifier = Modifier.fillMaxWidth(),
							)
							val visible = filterPeople(state.participants, state.query)
							if (visible.isEmpty()) Text(stringResource(R.string.announcement_people_none_found))
							visible.forEach { p ->
								Row(verticalAlignment = Alignment.CenterVertically) {
									Checkbox(checked = p.user.id in state.userIds, onCheckedChange = { form.togglePerson(p.user.id) }, enabled = enabled)
									Text(p.user.displayName)
								}
							}
						}
					}
				}
			}

			val sentence = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
			OutlinedTextField(
				value = state.title,
				onValueChange = form::editTitle,
				label = { Text(stringResource(R.string.announcement_title_field)) },
				singleLine = true,
				enabled = enabled,
				isError = state.fieldErrors["title"] != null,
				supportingText = { Text(state.fieldErrors["title"]?.resolve() ?: stringResource(R.string.announcement_title_help)) },
				keyboardOptions = sentence,
				modifier = Modifier.fillMaxWidth(),
			)
			OutlinedTextField(
				value = state.body,
				onValueChange = form::editBody,
				label = { Text(stringResource(R.string.announcement_body_field)) },
				enabled = enabled,
				isError = state.fieldErrors["body"] != null,
				supportingText = { Text(state.fieldErrors["body"]?.resolve() ?: stringResource(R.string.announcement_body_help)) },
				keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
				minLines = 4,
				modifier = Modifier.fillMaxWidth(),
			)

			val selected = form.hasSelection
			if (!selected) Text(stringResource(R.string.announcement_select_first), color = MaterialTheme.colorScheme.onSurfaceVariant)
			OutlinedButton(onClick = form::preview, enabled = enabled && selected, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.announcement_preview))
			}
			state.preview?.let { p ->
				Text(stringResource(R.string.announcement_preview_result, p.recipientCount), style = MaterialTheme.typography.titleSmall)
				val names = p.recipients.take(5).joinToString(", ") { it.displayName }
				if (names.isNotEmpty()) {
					Text(
						if (p.recipientCount > 5) stringResource(R.string.announcement_preview_more, names, p.recipientCount - 5)
						else stringResource(R.string.announcement_preview_names, names),
						style = MaterialTheme.typography.bodySmall,
					)
				}
				if (p.confirmationRequired) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Checkbox(checked = state.confirm, onCheckedChange = form::setConfirm, enabled = enabled)
						Text(stringResource(R.string.announcement_confirm_large, p.recipientCount))
					}
				}
			}
			val canSubmit = enabled && selected && state.preview != null && (state.preview.confirmationRequired.not() || state.confirm)
			Button(onClick = { form.send(onSent) }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(48.dp)) {
				if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
				else Text(stringResource(R.string.announcement_send))
			}
		}
	}
}

private fun kindLabel(kind: AudienceKind) = when (kind) {
	AudienceKind.WHOLE_OPERATION -> R.string.announcement_target_operation
	AudienceKind.GROUPS -> R.string.announcement_target_groups
	AudienceKind.PEOPLE -> R.string.announcement_target_people
}

@Composable
fun AnnouncementDetailScreen(viewModel: AnnouncementDetailViewModel) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	LoadContent(state, onRetry = viewModel::load) { a ->
		item { TitleRow(a.title) {} }
		item {
			ElevatedCard(Modifier.fillMaxWidth()) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
					a.body?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
					Fact(R.string.announcement_scope, a.operationName ?: stringResource(R.string.announcement_scope_organization))
					a.createdBy?.let { Fact(R.string.announcement_sender, it.displayName) }
					a.createdAt?.let { Fact(R.string.announcement_sent_at, formatDateTime(it)) }
					Text(stringResource(R.string.announcement_recipients, a.recipientCount))
					a.delivery?.let { Text(stringResource(R.string.announcement_delivery, it.sent, it.read, it.failed), style = MaterialTheme.typography.bodySmall) }
				}
			}
		}
	}
}
