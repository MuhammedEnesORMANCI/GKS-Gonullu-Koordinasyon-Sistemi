package org.gks.android.data.session

import kotlinx.serialization.Serializable

/** Cihazda tutulan oturum: erişim (15 dk) ve yenileme (30 gün, kayan) belirteçleri (backend §2.2). */
@Serializable
data class StoredSession(
	val accessToken: String,
	val refreshToken: String,
	val sessionId: String,
	val accessTokenExpiresAt: String,
) {
	override fun toString() = "StoredSession[session=$sessionId]"
}

/** Kalıcı oturum deposu. Uygulamada [KeystoreSessionStore]; testlerde bellek içi. */
interface SessionStore {
	fun read(): StoredSession?
	fun save(session: StoredSession)
	fun clear()
}
