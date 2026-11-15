package org.gks.android.ui.register

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiError
import org.gks.android.data.ApiResult
import org.gks.android.data.InvitationRepository
import org.gks.android.data.Registration
import org.gks.android.data.problem
import org.gks.android.data.remote.InvitationPublicView
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText
import org.gks.android.ui.account.fieldErrors

enum class RegistrationStep { CODE, FORM, DONE }

data class RegistrationState(
	val step: RegistrationStep = RegistrationStep.CODE,
	val code: String = "",
	val invitation: InvitationPublicView? = null,
	val fullName: String = "",
	val displayName: String = "",
	val phone: String = "",
	val password: String = "",
	val passwordRepeat: String = "",
	val busy: Boolean = false,
	/** Alan adı (sunucu `errors[].field` ile aynı) → ileti. */
	val fieldErrors: Map<String, UiText> = emptyMap(),
	val error: UiText? = null,
) {
	/** Kod kayıtlı kullanıcı içinse (operasyon daveti) form açılmaz; giriş yapıp uygulamada yanıtlanır. */
	val isOperationInvitation: Boolean get() = invitation?.kind == "OPERATION"
}

/** ID-15 → ID-16. Başarılı kayıttan sonra kullanıcı giriş ekranına döner. */
class RegistrationViewModel(private val invitations: InvitationRepository) : ViewModel() {

	private val _state = MutableStateFlow(RegistrationState())
	val state: StateFlow<RegistrationState> = _state.asStateFlow()

	fun update(transform: RegistrationState.() -> RegistrationState) =
		_state.update { it.transform().copy(error = null) }

	fun clearFieldError(field: String) = _state.update { it.copy(fieldErrors = it.fieldErrors - field) }

	fun lookup() {
		val current = _state.value
		if (current.busy) return
		val code = InvitationRepository.normalizeCode(current.code)
		if (code.length != CODE_LENGTH) {
			_state.update { it.copy(fieldErrors = mapOf("code" to text(R.string.register_code_invalid))) }
			return
		}
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			when (val result = invitations.lookup(code)) {
				is ApiResult.Success -> _state.update {
					it.copy(busy = false, invitation = result.value, step = if (result.value.kind == "OPERATION") it.step else RegistrationStep.FORM)
				}
				is ApiResult.Failure -> _state.update { it.copy(busy = false, error = invitationError(result.error)) }
			}
		}
	}

	/** Ekran kapanırken: ViewModel etkinlik kapsamında yaşadığı için bir sonraki açılış temiz başlar. */
	fun reset() {
		_state.value = RegistrationState()
	}

	/** Koda dönüş (yanlış davet). */
	fun back() = _state.update { RegistrationState(code = it.code) }

	fun submit() {
		val s = _state.value
		if (s.busy) return
		val errors = buildMap {
			if (s.fullName.trim().length !in 2..100) put("fullName", text(R.string.register_full_name_invalid))
			if (s.displayName.trim().length !in 2..40) put("displayName", text(R.string.register_display_name_invalid))
			if (s.phone.isNotBlank() && !PHONE.matches(s.phone.trim())) put("phone", text(R.string.register_phone_invalid))
			if (s.password.length < MIN_PASSWORD) put("password", text(R.string.register_password_short))
			if (s.password != s.passwordRepeat) put("passwordRepeat", text(R.string.register_password_mismatch))
		}
		if (errors.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = errors) }
			return
		}
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			val result = invitations.register(Registration(s.code, s.fullName, s.displayName, s.phone, s.password))
			_state.update {
				when (result) {
					is ApiResult.Success -> it.copy(busy = false, step = RegistrationStep.DONE, password = "", passwordRepeat = "")
					is ApiResult.Failure -> {
						val fields = result.error.fieldErrors(FORM_FIELDS)
						it.copy(busy = false, fieldErrors = fields, error = if (fields.isEmpty()) invitationError(result.error) else null)
					}
				}
			}
		}
	}

	private fun invitationError(error: ApiError): UiText {
		val problem = error.problem
		return when {
			problem?.code == "INVITATION_INVALID" -> text(
				when (problem.invitationStatus) {
					"EXPIRED" -> R.string.invitation_expired
					"ACCEPTED" -> R.string.invitation_used
					"REVOKED" -> R.string.invitation_revoked
					"DECLINED" -> R.string.invitation_declined
					"OPERATION_CLOSED" -> R.string.error_operation_closed
					else -> R.string.invitation_invalid
				},
			)
			problem?.code == "NOT_FOUND" -> text(R.string.invitation_not_found)
			problem?.code == "ALREADY_EXISTS" -> text(R.string.invitation_email_registered)
			else -> error.toUiText()
		}
	}

	private companion object {
		const val CODE_LENGTH = 8
		const val MIN_PASSWORD = 10
		val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
		val FORM_FIELDS = setOf("fullName", "displayName", "phone", "password")
	}
}
