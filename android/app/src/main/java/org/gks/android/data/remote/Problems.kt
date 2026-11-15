package org.gks.android.data.remote

import kotlinx.serialization.json.Json

/** §4.2 kodlarından istemcinin oturum kararı verdiği kodlar. */
object ErrorCodes {
	const val AUTH_TOKEN_EXPIRED = "AUTH_TOKEN_EXPIRED"
	const val AUTH_SESSION_REVOKED = "AUTH_SESSION_REVOKED"
	const val AUTH_UNAUTHENTICATED = "AUTH_UNAUTHENTICATED"
	const val AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS"
	const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
	const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
	const val VALIDATION_FAILED = "VALIDATION_FAILED"
	const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
}

/** Hata gövdesi problem+json değilse (ör. vekil sunucu sayfası) yalnızca durum kodu döner. */
fun parseProblem(json: Json, status: Int, body: String?): Problem {
	if (body.isNullOrBlank()) return Problem(status = status)
	return try {
		json.decodeFromString(Problem.serializer(), body).let { if (it.status == 0) it.copy(status = status) else it }
	} catch (e: IllegalArgumentException) {
		Problem(status = status)
	}
}
