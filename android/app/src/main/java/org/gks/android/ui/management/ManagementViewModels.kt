package org.gks.android.ui.management

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.ManagementRepository
import org.gks.android.data.ManagementRepository.Candidate
import org.gks.android.data.OperationDraft
import org.gks.android.data.remote.OperationView
import org.gks.android.data.remote.RoleAssignment
import org.gks.android.ui.account.fieldErrors
import org.gks.android.ui.common.ActionState
import org.gks.android.ui.common.DateTimes
import org.gks.android.ui.common.Load
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** Operasyon yönetimi işlemlerine özgü hata kodları (§7, §6). */
private val OPERATION_ERRORS = mapOf(
	"INVALID_STATE_TRANSITION" to R.string.operation_transition_invalid,
	"ALREADY_EXISTS" to R.string.coordinator_already,
	"FORBIDDEN" to R.string.management_forbidden,
	"CLIENT_NOT_ALLOWED" to R.string.management_session_required,
)

/** Liste süzgeci: süren (planlı/aktif/askıda), kapanan, tümü. */
enum class OperationFilter(val states: List<String>?) {
	OPEN(listOf("PLANNED", "ACTIVE", "SUSPENDED")),
	CLOSED(listOf("COMPLETED", "CANCELLED")),
	ALL(null),
}

/** OR-04: ADM tüm, KOR yalnızca koordine ettiği operasyonlar (kapsam sunucuda). */
class ManagementOperationsViewModel(private val repository: ManagementRepository) : LoadViewModel<List<OperationView>>() {

	private val _filter = MutableStateFlow(OperationFilter.OPEN)
	val filter: StateFlow<OperationFilter> = _filter.asStateFlow()

	init { load() }

	override suspend fun fetch() = repository.operations(_filter.value.states)

	fun select(filter: OperationFilter) {
		if (filter == _filter.value) return
		_filter.value = filter
		load()
	}
}

data class ManagedOperation(val operation: OperationView, val coordinators: List<RoleAssignment>?)

/**
 * OR-05 + OR-03 + (ADM) AC-01 – AC-03. Koordinatör atamaları yalnızca `role.assign` iznine sahip ADM için
 * okunur; KOR koordinatörleri yalnızca adlarıyla operasyon görünümünden görür.
 */
class OperationManageViewModel(
	private val repository: ManagementRepository,
	private val operationId: String,
	private val canAssignRoles: Boolean,
) : LoadViewModel<ManagedOperation>() {

	private val _action = MutableStateFlow(ActionState())
	val action: StateFlow<ActionState> = _action.asStateFlow()

	private val _candidates = MutableStateFlow<Load<List<Candidate>>?>(null)
	val candidates: StateFlow<Load<List<Candidate>>?> = _candidates.asStateFlow()

	init { load() }

	override suspend fun fetch(): ApiResult<ManagedOperation> {
		val operation = when (val result = repository.operation(operationId)) {
			is ApiResult.Success -> result.value
			is ApiResult.Failure -> return result
		}
		if (!canAssignRoles) return ApiResult.Success(ManagedOperation(operation, null))
		return when (val coordinators = repository.coordinators(operationId)) {
			is ApiResult.Success -> ApiResult.Success(ManagedOperation(operation, coordinators.value))
			is ApiResult.Failure -> coordinators
		}
	}

	/** @param onChanged bağlam yeniden okunur (durum ve roller menü/izinleri etkiler) */
	fun changeState(target: String, onChanged: () -> Unit) =
		act(R.string.operation_state_changed) { repository.changeState(operationId, target).also { if (it is ApiResult.Success) onChanged() } }

	fun revokeCoordinator(assignmentId: String, onChanged: () -> Unit) =
		act(R.string.coordinator_removed) { repository.revoke(assignmentId).also { if (it is ApiResult.Success) onChanged() } }

	fun grantCoordinator(userId: String, onChanged: () -> Unit) {
		_candidates.value = null
		act(R.string.coordinator_added) { repository.grantCoordinator(operationId, userId).also { if (it is ApiResult.Success) onChanged() } }
	}

	fun loadCandidates() {
		_candidates.value = Load.Loading
		viewModelScope.launch {
			_candidates.value = when (val result = repository.coordinatorCandidates(operationId)) {
				is ApiResult.Success -> Load.Ready(result.value)
				is ApiResult.Failure -> Load.Failed(result.error.toUiText(OPERATION_ERRORS))
			}
		}
	}

	fun closeCandidates() {
		_candidates.value = null
	}

	private fun act(success: Int, block: suspend () -> ApiResult<*>) {
		if (_action.value.busy) return
		_action.value = ActionState(busy = true)
		viewModelScope.launch {
			_action.value = when (val result = block()) {
				is ApiResult.Success -> ActionState(notice = text(success))
				is ApiResult.Failure -> ActionState(error = result.error.toUiText(OPERATION_ERRORS))
			}
			load()   // başarıda da 409'da da güncel durum sunucudan
		}
	}
}

data class OperationFormState(
	val loading: Boolean = false,
	val original: OperationView? = null,
	val name: String = "",
	val description: String = "",
	/** UTC ISO-8601 veya `null` (seçici: `DateTimeField`); düzenlemede sunucunun özgün değeri aynen tutulur. */
	val plannedEnd: String? = null,
	/** Yalnızca oluşturmada: seçilen koordinatörler (varsayılan: oluşturan ADM). */
	val coordinatorIds: Set<String> = emptySet(),
	val candidates: List<Candidate> = emptyList(),
	val busy: Boolean = false,
	val fieldErrors: Map<String, UiText> = emptyMap(),
	val error: UiText? = null,
	val savedId: String? = null,
)

/** OR-01 (oluştur, yalnızca ADM) ve OR-02 (düzenle, ADM veya kendi operasyonunun KOR'u). */
class OperationFormViewModel(
	private val repository: ManagementRepository,
	private val operationId: String?,
	private val selfUserId: String,
	private val selfDisplayName: String,
	private val clock: () -> java.time.Instant = java.time.Instant::now,
) : ViewModel() {

	private val _state = MutableStateFlow(OperationFormState(loading = true))
	val state: StateFlow<OperationFormState> = _state.asStateFlow()

	init {
		viewModelScope.launch {
			if (operationId == null) {
				val self = Candidate(selfUserId, selfDisplayName, Candidate.Source.COORDINATOR_ELSEWHERE)
				val candidates = (repository.coordinatorCandidates(null) as? ApiResult.Success)?.value.orEmpty()
				_state.value = OperationFormState(
					coordinatorIds = setOf(selfUserId),
					candidates = (listOf(self) + candidates.filter { it.id != selfUserId }),
				)
			} else {
				_state.value = when (val result = repository.operation(operationId)) {
					is ApiResult.Success -> OperationFormState(
						original = result.value,
						name = result.value.name,
						description = result.value.description.orEmpty(),
						plannedEnd = result.value.plannedEndAt,
					)
					is ApiResult.Failure -> OperationFormState(error = result.error.toUiText(OPERATION_ERRORS))
				}
			}
		}
	}

	fun edit(field: String, transform: OperationFormState.() -> OperationFormState) =
		_state.update { it.transform().copy(fieldErrors = it.fieldErrors - field, error = null) }

	fun toggleCoordinator(id: String) = edit("coordinatorIds") {
		copy(coordinatorIds = if (id in coordinatorIds) coordinatorIds - id else coordinatorIds + id)
	}

	fun save(onSaved: (String) -> Unit) {
		val s = _state.value
		if (s.busy) return
		val plannedEndIso = s.plannedEnd
		// Yalnızca yeni seçilen değer denetlenir: değişmeyen geçmiş bir bitiş düzenlemeyi engellemesin (backend de
		// gönderilmeyen alanı doğrulamaz). Kural backend OR-01/OR-02 ile aynı: gelecekte olmalı.
		val changed = s.original == null || plannedEndIso != s.original.plannedEndAt
		val instant = DateTimes.parse(plannedEndIso)
		val local = buildMap<String, UiText> {
			if (s.name.trim().length !in NAME_MIN..NAME_MAX) put("name", text(R.string.operation_name_invalid))
			if (s.description.trim().length > DESCRIPTION_MAX) put("description", text(R.string.operation_description_too_long))
			if (changed && instant != null && !instant.isAfter(clock())) put("plannedEndAt", text(R.string.operation_planned_end_past))
			if (operationId == null && s.coordinatorIds.isEmpty()) put("coordinatorIds", text(R.string.operation_coordinator_required))
			if (operationId == null && s.coordinatorIds.size > COORDINATOR_MAX) put("coordinatorIds", text(R.string.operation_coordinator_max))
		}
		if (local.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = local) }
			return
		}
		val draft = OperationDraft(s.name, s.description, plannedEndIso)
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			val result = if (s.original == null) repository.create(draft, s.coordinatorIds.toList()) else repository.update(s.original, draft)
			when (result) {
				is ApiResult.Success -> {
					_state.update { it.copy(busy = false, savedId = result.value.id) }
					onSaved(result.value.id)
				}
				is ApiResult.Failure -> {
					val fields = result.error.fieldErrors(setOf("name", "description", "plannedEndAt", "coordinatorIds"))
					_state.update { it.copy(busy = false, fieldErrors = fields, error = if (fields.isEmpty()) result.error.toUiText(OPERATION_ERRORS) else null) }
				}
			}
		}
	}

	private companion object {
		const val NAME_MIN = 3
		const val NAME_MAX = 120
		const val DESCRIPTION_MAX = 2000
		const val COORDINATOR_MAX = 10
	}
}
