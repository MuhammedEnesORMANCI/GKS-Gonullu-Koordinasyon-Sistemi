package org.gks.android.ui.mfa

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.AuthRepository
import org.gks.android.data.problem
import org.gks.android.data.remote.MfaEnrollment
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

data class MfaState(
	val enrollment: MfaEnrollment? = null,
	val enrolling: Boolean = false,
	val code: String = "",
	val busy: Boolean = false,
	val codeError: UiText? = null,
	val error: UiText? = null,
)

/**
 * ADR-014 Android yönetim girişi: (gerekirse) TOTP kurulumu (ID-03) ve kod doğrulaması (ID-04). Bilet yalnızca
 * bellekte; gizli anahtar ekranda gösterilir, cihaza yazılmaz.
 */
class MfaViewModel(
	private val auth: AuthRepository,
	private val ticket: String,
	enrollmentRequired: Boolean,
) : ViewModel() {

	private val _state = MutableStateFlow(MfaState())
	val state: StateFlow<MfaState> = _state.asStateFlow()

	init {
		if (enrollmentRequired) enroll()
	}

	private fun enroll() {
		_state.update { it.copy(enrolling = true, error = null) }
		viewModelScope.launch {
			_state.update {
				when (val result = auth.enrollMfa(ticket)) {
					is ApiResult.Success -> it.copy(enrolling = false, enrollment = result.value)
					is ApiResult.Failure -> it.copy(enrolling = false, error = result.error.toUiText(MFA_ERRORS))
				}
			}
		}
	}

	fun onCode(value: String) = _state.update { it.copy(code = value.filter(Char::isDigit).take(6), codeError = null, error = null) }

	/** @param onExpired bilet geçersiz (süre doldu / 5 hatalı kod): giriş ekranına dönülür */
	fun verify(onVerified: () -> Unit, onExpired: () -> Unit) {
		val s = _state.value
		if (s.busy) return
		if (s.code.length != 6) {
			_state.update { it.copy(codeError = text(R.string.mfa_code_length)) }
			return
		}
		_state.update { it.copy(busy = true, error = null, codeError = null) }
		viewModelScope.launch {
			when (val result = auth.verifyMfa(ticket, s.code)) {
				is ApiResult.Success -> onVerified()
				is ApiResult.Failure -> {
					val problem = result.error.problem
					when {
						problem?.code == "AUTH_MFA_REQUIRED" -> onExpired()
						problem?.errors?.any { it.code == "INVALID_CODE" } == true ->
							_state.update { it.copy(busy = false, code = "", codeError = text(R.string.mfa_code_invalid)) }
						else -> _state.update { it.copy(busy = false, code = "", error = result.error.toUiText(MFA_ERRORS)) }
					}
				}
			}
		}
	}

	private companion object {
		val MFA_ERRORS = mapOf(
			"ACCOUNT_LOCKED" to R.string.error_account_locked,
			"ACCOUNT_DISABLED" to R.string.error_account_disabled,
			"CLIENT_NOT_ALLOWED" to R.string.mfa_role_removed,
		)
	}
}
