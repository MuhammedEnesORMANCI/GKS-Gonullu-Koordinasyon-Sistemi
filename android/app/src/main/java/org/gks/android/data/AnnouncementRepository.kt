package org.gks.android.data

import org.gks.android.data.remote.AnnouncementCreateRequest
import org.gks.android.data.remote.AnnouncementPreview
import org.gks.android.data.remote.AnnouncementTarget
import org.gks.android.data.remote.AnnouncementView
import org.gks.android.data.remote.GksApi

/** Duyuru hedefi (NT-01/NT-02). Kişi ve grup seçimleri yalnızca bir operasyonun içindedir. */
sealed interface AnnouncementAudience {
	/** Operasyonun tüm katılımcıları. */
	data class WholeOperation(val operationId: String) : AnnouncementAudience

	/** Seçilen grupların aktif üyeleri (çoklu seçim). */
	data class Groups(val operationId: String, val groupIds: Set<String>) : AnnouncementAudience

	/** Operasyondaki tek tek seçilen katılımcılar (`userIds`). */
	data class People(val operationId: String, val userIds: Set<String>) : AnnouncementAudience

	/** Kurum geneli (yalnızca ADM, `announcement.send.organization`). */
	data object Organization : AnnouncementAudience

	/** Seçim gerektiren hedefte seçim yoksa önizleme ve gönderim yapılmaz. */
	val isEmpty: Boolean
		get() = when (this) {
			is Groups -> groupIds.isEmpty()
			is People -> userIds.isEmpty()
			else -> false
		}

	fun toTarget(): AnnouncementTarget = when (this) {
		is WholeOperation -> AnnouncementTarget(operationId = operationId)
		is Groups -> AnnouncementTarget(operationId = operationId, groupIds = groupIds.sorted())
		is People -> AnnouncementTarget(operationId = operationId, userIds = userIds.sorted())
		Organization -> AnnouncementTarget(organization = true)
	}
}

/**
 * ADM/KOR duyuru yönetimi (NT-01 – NT-03, NT-06). Web paneliyle aynı uçlar; Android'de yalnızca MFA'lı yönetim
 * oturumunda (ADR-014). Yetki (KOR kendi operasyonu, ADM tüm operasyonlar ve kurum geneli; ADR-015), `ACTIVE`
 * operasyon kuralı (N-19) ve 50 alıcı onay eşiği sunucudadır.
 */
class AnnouncementRepository(
	private val api: GksApi,
	private val caller: ApiCaller,
	private val keys: IdempotencyKeys = IdempotencyKeys(),
) {

	suspend fun preview(audience: AnnouncementAudience): ApiResult<AnnouncementPreview> =
		caller.call { api.previewAnnouncement(audience.toTarget()) }

	/**
	 * NT-02: anahtar gönderimin içeriğinden üretilir; ağ hatasında aynı gönderim aynı anahtarla tekrarlanır
	 * (sunucuya ulaşmış ama yanıtı kaybolmuş duyuru ikinci kez gönderilmez). Kesin sonuçtan sonra anahtar bırakılır.
	 */
	suspend fun send(audience: AnnouncementAudience, title: String, body: String, confirm: Boolean): ApiResult<AnnouncementView> {
		val target = audience.toTarget()
		val request = AnnouncementCreateRequest(
			operationId = target.operationId,
			userIds = target.userIds,
			groupIds = target.groupIds,
			organization = target.organization,
			title = title.trim(),
			body = body.trim(),
			confirm = if (confirm) true else null,
		)
		return keys.run("announcement:${request.hashCode()}") { key -> caller.call { api.createAnnouncement(key, request) } }
	}

	/** NT-03: operasyonun (veya `null` ile kapsamdaki tüm) duyuruları; en çok [maxPages] × 100 kayıt. */
	suspend fun history(operationId: String?, maxPages: Int = 3): ApiResult<List<AnnouncementView>> =
		caller.allPages(maxPages) { cursor -> api.announcements(operationId, cursor) }

	suspend fun announcement(id: String): ApiResult<AnnouncementView> = caller.call { api.announcement(id) }

	companion object {
		const val TITLE_MIN = 3
		const val TITLE_MAX = 120
		const val BODY_MAX = 2000
	}
}
