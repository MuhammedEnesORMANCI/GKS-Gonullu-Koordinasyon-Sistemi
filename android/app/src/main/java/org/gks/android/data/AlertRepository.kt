package org.gks.android.data

import java.time.Clock
import java.time.Instant
import org.gks.android.data.remote.AlertResponseRequest
import org.gks.android.data.remote.AlertSignalRequest
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.MyAlertView

/**
 * Acil alarmın alıcı tarafı (AND-03/04, TD-10). FCM henüz yok (ADR-018): açık alarmlar uygulama ön plandayken
 * yoklanır ve gelen kutusunda `CRITICAL` bildirim olarak da görünür. Sinyaller (alındı, görüldü) yalnızca ileri
 * geçiştir ve otomatik tekrarlanmaz (LD-10).
 */
class AlertRepository(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
	private val clock: Clock = Clock.systemUTC(),
) {

	/** AL-08: açık alarmlarım (tek çağrı, operasyon sayısından bağımsız). */
	suspend fun openAlerts(): ApiResult<List<MyAlertView>> = caller.allPages { cursor -> api.myAlerts("OPEN", cursor) }

	suspend fun alert(id: String): ApiResult<MyAlertView> = caller.call { api.myAlert(id) }

	/** AL-13: yoklamada ilk kez görülen (`SENT`) alarm için "alındı". */
	suspend fun markDelivered(recipientId: String): ApiResult<Unit> =
		caller.call { api.alertDelivered(recipientId, AlertSignalRequest(now())) }

	/** AL-14: alarm ekranı açıldı. */
	suspend fun markSeen(recipientId: String): ApiResult<Unit> =
		caller.call { api.alertSeen(recipientId, AlertSignalRequest(now())) }

	/**
	 * AL-15: zorunlu `Idempotency-Key`. Aynı seçeneğin ağ tekrarı aynı anahtarla gider; farklı seçenek yeni anahtar
	 * alır (yanıt değişikliği, TD-07). Cihaz zamanı ilk denemede sabitlenir.
	 */
	suspend fun respond(recipientId: String, option: String): ApiResult<MyAlertView> {
		val action = "alert-response:$recipientId:$option"
		val at = synchronized(deviceTimes) { deviceTimes.getOrPut(action) { now() } }
		val result = keys.run(action) { key ->
			caller.call { api.respondToAlert(recipientId, key, AlertResponseRequest(option, at)) }
		}
		val transient = result is ApiResult.Failure &&
			(result.error == ApiError.Network || result.error == ApiError.RefreshUnavailable)
		if (!transient) synchronized(deviceTimes) { deviceTimes.remove(action) }
		return result
	}

	/** Aynı anahtarla tekrar aynı gövdeyi taşımalı (aksi `422 IDEMPOTENCY_KEY_MISMATCH`). */
	private val deviceTimes = mutableMapOf<String, String>()

	private fun now(): String = Instant.now(clock).toString()
}
