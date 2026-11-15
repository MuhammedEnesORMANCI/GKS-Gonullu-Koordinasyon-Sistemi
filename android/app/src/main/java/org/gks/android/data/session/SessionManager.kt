package org.gks.android.data.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Oturumun istemci tarafından değil sunucu tarafından sona erdirilme nedeni. */
enum class SessionEndReason {
	/** `401 AUTH_SESSION_REVOKED` / `AUTH_UNAUTHENTICATED`: iptal, süre dolumu veya yenileme reddi. */
	REVOKED,

	/** `403 ACCOUNT_DISABLED`. */
	DISABLED,
}

/**
 * Tek oturum kaynağı. Sunucu oturumu sonlandırdığında yerel belirteçler silinir ve [ended] yayınlanır.
 * `503 SERVICE_UNAVAILABLE` ve ağ hataları oturum sonu **değildir** (LD-48): belirteçler korunur.
 */
class SessionManager(private val store: SessionStore) {

	private val _ended = MutableSharedFlow<SessionEndReason>(extraBufferCapacity = 1)
	val ended: SharedFlow<SessionEndReason> = _ended.asSharedFlow()

	fun current(): StoredSession? = store.read()

	fun start(session: StoredSession) = store.save(session)

	fun update(session: StoredSession) = store.save(session)

	/** Kullanıcının kendi çıkışı: olay yayınlanmaz. */
	fun clear() = store.clear()

	/**
	 * Hata, gönderildiği belirteç hâlâ geçerli oturumunsa uygulanır. Böylece eski bir oturumdan geç dönen
	 * yanıt, bu arada açılmış yeni oturumu silmez.
	 */
	@Synchronized
	fun endIfCurrent(sentAccessToken: String, reason: SessionEndReason) {
		val current = store.read() ?: return
		if (current.accessToken != sentAccessToken) return
		store.clear()
		_ended.tryEmit(reason)
	}
}
