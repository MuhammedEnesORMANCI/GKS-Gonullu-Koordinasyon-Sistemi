package org.gks.android.data

import org.gks.android.data.remote.AlertPreviewView
import org.gks.android.data.remote.AlertRecipientRowView
import org.gks.android.data.remote.AlertSendRequest
import org.gks.android.data.remote.AlertSummaryView
import org.gks.android.data.remote.GeneralAlertDraftRequest
import org.gks.android.data.remote.GksApi
import org.gks.android.data.remote.ManagedAlertView

/**
 * ADM/KOR alarm yönetimi (Android, MFA'lı yönetim oturumu; ADR-014/026): Genel Acil Alarm ve alarm takip ekranı.
 * Yetki ve hedef kapsamı sunucuda denetlenir; gönderim aynı alarm için aynı anahtarla tekrarlanır.
 */
class AlertManagementRepository(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
) {

	suspend fun createGeneral(request: GeneralAlertDraftRequest): ApiResult<AlertPreviewView> =
		caller.call { api.createGeneralAlert(request) }

	/** Güçlü onaydan sonra çağrılır; `confirmed=true` her zaman gönderilir (sunucu onaysız genel alarmı reddeder). */
	suspend fun send(preview: AlertPreviewView): ApiResult<ManagedAlertView> =
		keys.run("alert:send:${preview.alertId}:${preview.audienceHash}") { key ->
			caller.call { api.sendAlert(preview.alertId, key, AlertSendRequest(preview.version, preview.audienceHash, confirmed = true)) }
		}

	suspend fun discard(alertId: String): ApiResult<Unit> = caller.call { api.deleteAlertDraft(alertId) }

	suspend fun openAlerts(): ApiResult<List<AlertSummaryView>> = caller.allPages { cursor -> api.managedAlerts("OPEN", cursor) }

	suspend fun alert(id: String): ApiResult<ManagedAlertView> = caller.call { api.managedAlert(id) }

	suspend fun recipients(id: String): ApiResult<List<AlertRecipientRowView>> =
		when (val r = caller.call { api.alertRecipients(id) }) {
			is ApiResult.Success -> ApiResult.Success(r.value.items)
			is ApiResult.Failure -> r
		}

	suspend fun close(id: String): ApiResult<ManagedAlertView> = caller.call { api.closeAlert(id) }
}
