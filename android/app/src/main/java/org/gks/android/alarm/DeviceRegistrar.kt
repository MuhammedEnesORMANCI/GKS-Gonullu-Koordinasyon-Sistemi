package org.gks.android.alarm

import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.gks.android.BuildConfig
import org.gks.android.data.ApiCaller
import org.gks.android.data.ApiResult
import org.gks.android.data.map
import org.gks.android.data.remote.DeviceRegisterRequest
import org.gks.android.data.remote.GksApi
import org.gks.android.data.session.SessionManager

/**
 * ID-26 cihaz kaydı (ADR-022): FCM belirteci oturum varken sunucuya yazılır (girişten sonra, uygulama açılışında ve
 * belirteç yenilenince). Çıkışta sunucu kaydı pasifleştirir; yeniden girişte tekrar etkinleşir. Belirteç günlüğe
 * yazılmaz.
 */
class DeviceRegistrar(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val sessions: SessionManager,
	private val installationId: () -> String,
	private val currentToken: suspend () -> String? = ::firebaseToken,
) {

	suspend fun sync(token: String? = null): ApiResult<Unit>? {
		if (sessions.current() == null) return null
		val value = token ?: currentToken() ?: return null
		return caller.call { api.registerDevice(installationId(), DeviceRegisterRequest(value, BuildConfig.VERSION_NAME)) }.map { }
	}

	companion object {
		/** Firebase yapılandırılmamışsa (ör. `google-services.json` yok) `null`; uygulama push'suz çalışır. */
		suspend fun firebaseToken(): String? = try {
			suspendCancellableCoroutine { cont ->
				FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
					cont.resume(if (task.isSuccessful) task.result else null)
				}
			}
		}
		catch (ex: IllegalStateException) {
			null
		}
	}
}
