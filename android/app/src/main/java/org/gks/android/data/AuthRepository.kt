package org.gks.android.data

import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.LoginRequest
import org.gks.android.data.remote.LoginResponse
import org.gks.android.data.remote.MfaEnrollment
import org.gks.android.data.remote.MfaTicketRequest
import org.gks.android.data.remote.MfaVerifyRequest
import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.MeProfile
import org.gks.android.data.remote.PasswordChangeRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession

sealed interface LoginOutcome {
	data object SessionStarted : LoginOutcome

	/** ADM/KOR (ADR-014): TOTP adımı gerekir; bilet 5 dk geçerli ve yalnızca bellekte tutulur. */
	data class MfaRequired(val ticket: String, val enrollmentRequired: Boolean) : LoginOutcome
}

class AuthRepository(
	private val api: GksApi,
	private val sessions: SessionManager,
	/** Kalıcı kurulum kimliği ([org.gks.android.data.session.InstallationId.get]). */
	private val installationId: () -> String,
	private val caller: ApiCaller,
) {

	fun hasSession(): Boolean = sessions.current() != null

	/**
	 * ID-02 Android girişi. Gönüllü: MFA yok, belirteçler gövdede gelir ve şifreli depoya yazılır. ADM/KOR
	 * (ADR-014): oturum açılmaz, MFA bileti döner; oturum [verifyMfa] ile açılır.
	 */
	suspend fun login(email: String, password: String): ApiResult<LoginOutcome> {
		val request = LoginRequest(email.trim(), password, CLIENT_TYPE, installationId())
		return when (val result = caller.call { api.login(request) }) {
			is ApiResult.Failure -> result
			is ApiResult.Success -> {
				val body = result.value
				if (body.mfaRequired) {
					body.mfaTicket?.let { ApiResult.Success(LoginOutcome.MfaRequired(it, body.mfaEnrollmentRequired)) }
						?: ApiResult.Failure(ApiError.Unexpected)
				} else {
					startSession(body).map { LoginOutcome.SessionStarted }
				}
			}
		}
	}

	/** ID-03: yalnızca TOTP kurulmamışsa. */
	suspend fun enrollMfa(ticket: String): ApiResult<MfaEnrollment> = caller.call { api.enrollMfa(MfaTicketRequest(ticket)) }

	/** ID-04: doğru kod → MFA'lı Android yönetim oturumu (12 sa mutlak), Keystore destekli depoya yazılır. */
	suspend fun verifyMfa(ticket: String, code: String): ApiResult<Unit> =
		when (val result = caller.call { api.verifyMfa(MfaVerifyRequest(ticket, code)) }) {
			is ApiResult.Failure -> result
			is ApiResult.Success -> startSession(result.value)
		}

	private fun startSession(body: LoginResponse): ApiResult<Unit> {
		val session = body.session
		if (body.accessToken == null || body.refreshToken == null || session == null || body.accessTokenExpiresAt == null) {
			return ApiResult.Failure(ApiError.Unexpected)
		}
		sessions.start(StoredSession(body.accessToken, body.refreshToken, session.sessionId, body.accessTokenExpiresAt))
		return ApiResult.Success(Unit)
	}

	/** ID-11. Süresi dolmuş erişim belirteci kimlik doğrulayıcıda sessizce yenilenir. */
	suspend fun loadContext(): ApiResult<MeContext> = caller.call { api.meContext() }

	/** ID-09. */
	suspend fun profile(): ApiResult<MeProfile> = caller.call { api.me() }

	/** ID-10. [changes]: alan adı → yeni değer; `null` alanı temizler (yalnızca `phone` için anlamlı). */
	suspend fun updateProfile(changes: Map<String, String?>): ApiResult<MeProfile> {
		val body = JsonObject(changes.mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull })
		return caller.call { api.updateProfile(body) }
	}

	/**
	 * ID-08. Sunucu bu oturum dahil tüm oturumları iptal edip yeni çift döndürür; yeni çift, başka istek eski
	 * belirteçle gitmeden **hemen** şifreli depoya yazılır (aksi hâlde sonraki istek `AUTH_SESSION_REVOKED` alırdı).
	 */
	suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> {
		val result = caller.call { api.changePassword(PasswordChangeRequest(currentPassword, newPassword)) }
		if (result !is ApiResult.Success) return result as ApiResult.Failure
		val body = result.value
		if (body.accessToken == null || body.refreshToken == null) return ApiResult.Failure(ApiError.Unexpected)
		sessions.update(StoredSession(body.accessToken, body.refreshToken, body.sessionId, body.accessTokenExpiresAt))
		return ApiResult.Success(Unit)
	}

	/**
	 * ID-06. Yerel oturum her durumda silinir.
	 * @return sunucu oturumu kapattıysa (veya oturum zaten bitmişse) `true`; sunucuya ulaşılamadıysa `false`
	 */
	suspend fun logout(): Boolean {
		if (sessions.current() == null) return true
		val result = caller.call { api.logout() }
		sessions.clear()
		return when (result) {
			is ApiResult.Success -> true
			is ApiResult.Failure -> result.error.problem?.status == 401
		}
	}

	companion object {
		const val CLIENT_TYPE = "ANDROID"
	}
}
