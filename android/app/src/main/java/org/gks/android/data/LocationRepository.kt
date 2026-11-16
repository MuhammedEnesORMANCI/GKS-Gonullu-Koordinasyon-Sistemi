package org.gks.android.data

import java.time.Instant
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.LocationMeasurementRequest
import org.gks.android.data.remote.LocationSharingStartRequest
import org.gks.android.data.remote.LocationSharingView

/**
 * Konum paylaşımı (LC-01 – LC-04; K-15 – K-16, ADR-019). Paylaşım yalnızca kullanıcı açıkça başlatınca ve yalnızca
 * aktif operasyonda yapılır; istediği an durdurulur. Konumu yalnızca operasyonun koordinatörü görür.
 */
class LocationRepository(private val api: GksApi, private val caller: ApiCaller) {

	suspend fun mine(): ApiResult<List<LocationSharingView>> = caller.call { api.myLocationSharings() }

	suspend fun start(operationId: String): ApiResult<LocationSharingView> =
		caller.call { api.startLocationSharing(operationId, LocationSharingStartRequest()) }

	/** Cihaz konum alamıyor (izin yok, GPS kapalı, ölçüm yok): paylaşım `UNAVAILABLE` (N-07). */
	suspend fun reportUnavailable(operationId: String, reason: String): ApiResult<LocationSharingView> =
		caller.call { api.startLocationSharing(operationId, LocationSharingStartRequest("UNAVAILABLE", reason)) }

	suspend fun stop(operationId: String): ApiResult<Unit> = caller.call { api.stopLocationSharing(operationId) }

	suspend fun send(operationId: String, lat: Double, lon: Double, accuracy: Double, measuredAt: Instant): ApiResult<Unit> =
		caller.call { api.sendLocation(operationId, LocationMeasurementRequest(lat, lon, accuracy, measuredAt.toString())) }

	companion object {
		/** Bu yanıtlar paylaşımın sunucuda bittiğini söyler: ön plan servisi kendini durdurur (TD-18). */
		val STOP_CODES = setOf("LOCATION_SHARING_STOPPED", "OPERATION_NOT_ACTIVE", "NOT_A_PARTICIPANT")

		fun shouldStop(result: ApiResult<*>): Boolean =
			result is ApiResult.Failure && result.error.problem?.code in STOP_CODES
	}
}
