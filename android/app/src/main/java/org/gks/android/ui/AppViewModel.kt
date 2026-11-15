package org.gks.android.ui

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
import org.gks.android.data.LoginOutcome
import org.gks.android.data.remote.MeContext
import org.gks.android.data.session.SessionEndReason
import org.gks.android.data.session.SessionManager
import org.gks.android.ui.common.LOGIN_ERRORS
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

data class LoginForm(
	val email: String = "",
	val password: String = "",
	val submitting: Boolean = false,
	val emailError: UiText? = null,
	val passwordError: UiText? = null,
	val error: UiText? = null,
)

sealed interface Screen {
	data object Starting : Screen
	data class Login(val notice: UiText? = null) : Screen
	data object Register : Screen

	/** ADR-014: ADM/KOR Android girişinde TOTP adımı (bilet yalnızca bellekte). */
	data class Mfa(val ticket: String, val enrollmentRequired: Boolean) : Screen
	data object LoadingContext : Screen
	data class ContextError(val message: UiText) : Screen

	/** Oturum açık; bağlam yenilenirken eski bağlam görünmeye devam eder. */
	data class Main(val context: MeContext, val refreshing: Boolean = false, val error: UiText? = null) : Screen
}

/** Oturum yaşam döngüsü: giriş, kayıt, `/me/context`, oturum sonu ve çıkış. */
class AppViewModel(private val auth: AuthRepository, sessions: SessionManager) : ViewModel() {

	private val _screen = MutableStateFlow<Screen>(Screen.Starting)
	val screen: StateFlow<Screen> = _screen.asStateFlow()

	private val _form = MutableStateFlow(LoginForm())
	val form: StateFlow<LoginForm> = _form.asStateFlow()

	private var loggingOut = false

	init {
		viewModelScope.launch {
			sessions.ended.collect { reason ->
				if (loggingOut) return@collect
				_form.update { LoginForm(email = it.email) }
				_screen.value = Screen.Login(
					text(
						when (reason) {
							SessionEndReason.REVOKED -> R.string.notice_session_revoked
							SessionEndReason.DISABLED -> R.string.notice_account_disabled
						},
					),
				)
			}
		}
		if (auth.hasSession()) loadContext() else _screen.value = Screen.Login()
	}

	fun onEmailChange(value: String) = _form.update { it.copy(email = value, emailError = null, error = null) }

	fun onPasswordChange(value: String) = _form.update { it.copy(password = value, passwordError = null, error = null) }

	fun login() {
		val current = _form.value
		if (current.submitting) return
		val email = current.email.trim()
		val emailError = when {
			email.isEmpty() -> text(R.string.login_email_required)
			!EMAIL.matches(email) -> text(R.string.login_email_invalid)
			else -> null
		}
		val passwordError = if (current.password.isEmpty()) text(R.string.login_password_required) else null
		if (emailError != null || passwordError != null) {
			_form.update { it.copy(emailError = emailError, passwordError = passwordError) }
			return
		}
		_form.update { it.copy(submitting = true, error = null) }
		viewModelScope.launch {
			when (val result = auth.login(email, current.password)) {
				is ApiResult.Success -> {
					_form.value = LoginForm(email = email)
					when (val outcome = result.value) {
						LoginOutcome.SessionStarted -> loadContext()
						is LoginOutcome.MfaRequired -> _screen.value = Screen.Mfa(outcome.ticket, outcome.enrollmentRequired)
					}
				}
				is ApiResult.Failure -> _form.update {
					it.copy(submitting = false, password = "", error = result.error.toUiText(LOGIN_ERRORS))
				}
			}
		}
	}

	/** TOTP doğrulandı: yönetim oturumu depoda, bağlam okunur. */
	fun mfaVerified() = loadContext()

	/** @param expired bilet süresi doldu veya hak bitti */
	fun mfaCancelled(expired: Boolean) {
		_form.update { LoginForm(email = it.email) }
		_screen.value = Screen.Login(if (expired) text(R.string.mfa_ticket_expired) else null)
	}

	fun openRegistration() {
		_screen.value = Screen.Register
	}

	/** Kayıttan dönüş: başarılıysa bilgi notuyla giriş ekranı. */
	fun closeRegistration(registered: Boolean) {
		_form.value = LoginForm()
		_screen.value = Screen.Login(if (registered) text(R.string.register_done) else null)
	}

	fun retryContext() = loadContext()

	/** Bağlamı yeniden çeker (davet yanıtı sonrası veya kullanıcı yenilemesi). */
	fun refreshContext() {
		val main = _screen.value as? Screen.Main ?: return
		if (main.refreshing) return
		_screen.value = main.copy(refreshing = true, error = null)
		viewModelScope.launch {
			when (val result = auth.loadContext()) {
				is ApiResult.Success -> _screen.value = Screen.Main(result.value)
				is ApiResult.Failure -> (_screen.value as? Screen.Main)?.let {
					_screen.value = it.copy(refreshing = false, error = result.error.toUiText())
				}
			}
		}
	}

	fun logout() {
		if (loggingOut) return
		loggingOut = true
		viewModelScope.launch {
			val informed = try {
				auth.logout()
			} finally {
				loggingOut = false
			}
			_form.update { LoginForm(email = it.email) }
			_screen.value = Screen.Login(if (informed) null else text(R.string.notice_logout_offline))
		}
	}

	private fun loadContext() {
		_screen.value = Screen.LoadingContext
		viewModelScope.launch {
			when (val result = auth.loadContext()) {
				is ApiResult.Success -> _screen.value = Screen.Main(result.value)
				is ApiResult.Failure -> {
					// Oturum sona erdiyse SessionManager.ended giriş ekranına götürür; aksi hâlde oturum korunur.
					if (auth.hasSession() && _screen.value == Screen.LoadingContext) {
						_screen.value = Screen.ContextError(result.error.toUiText())
					}
				}
			}
		}
	}

	private companion object {
		val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
	}
}
