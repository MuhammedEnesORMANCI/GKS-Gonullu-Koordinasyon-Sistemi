package org.gks.android.data.session

import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.gks.android.data.remote.ErrorCodes
import org.gks.android.data.remote.NO_AUTH_HEADER
import org.gks.android.data.remote.parseProblem

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "
private const val PEEK_BYTES = 16L * 1024

private fun Request.bearerToken(): String? = header(AUTHORIZATION)?.takeIf { it.startsWith(BEARER) }?.substring(BEARER.length)

private fun Response.problemCode(json: Json): String? = parseProblem(json, code, peekBody(PEEK_BYTES).string()).code

/**
 * Android erişim belirteci yalnızca `Authorization: Bearer` ile taşınır (§2.2 taşıma kuralı); çerez kullanılmaz,
 * dolayısıyla CSRF de gerekmez. `403 ACCOUNT_DISABLED` oturumu bitirir.
 */
class AuthInterceptor(private val sessions: SessionManager, private val json: Json) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val original = chain.request()
		if (original.header(NO_AUTH_HEADER) != null) {
			return chain.proceed(original.newBuilder().removeHeader(NO_AUTH_HEADER).build())
		}
		val session = sessions.current() ?: return chain.proceed(original)
		val response = chain.proceed(original.newBuilder().header(AUTHORIZATION, BEARER + session.accessToken).build())
		if (response.code == 403 && response.problemCode(json) == ErrorCodes.ACCOUNT_DISABLED) {
			sessions.endIfCurrent(session.accessToken, SessionEndReason.DISABLED)
		}
		return response
	}
}

/**
 * OkHttp yalnızca `401` yanıtında çağırır:
 * - `AUTH_TOKEN_EXPIRED` → tek uçuşlu yenileme, istek yeni belirteçle bir kez tekrarlanır.
 * - `AUTH_SESSION_REVOKED` / `AUTH_UNAUTHENTICATED` → oturum sonu (yerel belirteçler silinir).
 */
class TokenAuthenticator(
	private val sessions: SessionManager,
	private val refresher: TokenRefresher,
	private val json: Json,
) : Authenticator {

	override fun authenticate(route: Route?, response: Response): Request? {
		val sent = response.request.bearerToken() ?: return null
		when (response.problemCode(json)) {
			ErrorCodes.AUTH_TOKEN_EXPIRED -> Unit
			ErrorCodes.AUTH_SESSION_REVOKED, ErrorCodes.AUTH_UNAUTHENTICATED -> {
				sessions.endIfCurrent(sent, SessionEndReason.REVOKED)
				return null
			}
			else -> return null
		}
		if (response.priorResponse != null) {
			return null   // yenilenmiş belirteçle de süresi dolmuş: döngüye girme
		}
		val fresh = refresher.refreshAfterExpiry(sent) ?: return null
		return response.request.newBuilder().header(AUTHORIZATION, BEARER + fresh.accessToken).build()
	}
}
