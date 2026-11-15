package org.gks.android.data.session

import org.gks.android.data.remote.RefreshApi
import org.gks.android.data.remote.RefreshRequest
import java.io.IOException

/** Yenileme geçici olarak yapılamadı (ağ, 5xx, 503). Oturum korunur; istek hatası kullanıcıya gösterilir. */
class RefreshUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * ID-05 döndürmesi, süreç içinde tek uçuşla (ADR-012, backend §2.2):
 * - Eşzamanlı istekler aynı kilidi bekler; biri yeniledikten sonra diğerleri yeni belirteci kullanır ve eski
 *   yenileme belirtecini ikinci kez göndermez (tekrar kullanım tüm zinciri iptal eder).
 * - Yeni çift, isteği tekrarlamadan **önce** kaydedilir (BS-01).
 * - `401` → oturum sunucuda bitmiş: yerel oturum silinir. Diğer hatalar oturumu silmez.
 */
class TokenRefresher(private val sessions: SessionManager, private val api: RefreshApi) {

	/**
	 * @param expiredAccessToken süresi dolduğu bildirilen isteğin taşıdığı belirteç
	 * @return tekrar için kullanılacak oturum; oturum sona erdiyse `null`
	 */
	@Synchronized
	@Throws(IOException::class)
	fun refreshAfterExpiry(expiredAccessToken: String): StoredSession? {
		val current = sessions.current() ?: return null
		if (current.accessToken != expiredAccessToken) {
			return current   // başka bir istek bu arada yeniledi
		}
		val response = try {
			api.refresh(RefreshRequest(current.refreshToken)).execute()
		} catch (e: IOException) {
			throw RefreshUnavailableException("Oturum yenilenemedi: ağ hatası", e)
		}
		val body = response.body()
		if (response.isSuccessful && body?.accessToken != null && body.refreshToken != null) {
			val next = StoredSession(body.accessToken, body.refreshToken, body.sessionId, body.accessTokenExpiresAt)
			sessions.update(next)
			return next
		}
		response.errorBody()?.close()
		if (response.code() == 401) {
			sessions.endIfCurrent(current.accessToken, SessionEndReason.REVOKED)
			return null
		}
		throw RefreshUnavailableException("Oturum yenilenemedi: HTTP ${response.code()}")
	}
}
