package org.gks.android.alarm

import org.gks.android.data.remote.InboxItem

/**
 * FCM data mesajından veya SSE ile okunan gelen kutusu kaydından gelen tek bildirim olayı (ADR-022). İki yol aynı
 * `notificationId` ile tekilleştirilir; böylece aynı olay iki kez bildirim üretmez.
 */
data class Incoming(
	val notificationId: String,
	val category: String,
	val kind: String,
	val title: String,
	val body: String?,
	/** Alarm olaylarında alarm kimliği (`sourceType = ALERT`). */
	val alertId: String?,
	/** Alıcının gelen kutusu kaydı (duyuru ayrıntısı bu kimlikle açılır). */
	val inboxItemId: String?,
) {
	val isAlert get() = category == "ALERT" && alertId != null

	companion object {
		/** Sunucu yükü (`FcmPushGateway.data`): `type`, `kind`, `notificationId`, `inboxItemId`, `sourceType`, `sourceId`, `title`, `body`. */
		fun fromData(data: Map<String, String>): Incoming? {
			val id = data["notificationId"] ?: return null
			val type = data["type"] ?: return null
			return Incoming(
				notificationId = id,
				category = type,
				kind = data["kind"] ?: type,
				title = data["title"].orEmpty(),
				body = data["body"],
				alertId = data["sourceId"]?.takeIf { data["sourceType"] == "ALERT" },
				inboxItemId = data["inboxItemId"],
			)
		}

		fun fromInbox(item: InboxItem): Incoming? {
			val id = item.notificationId ?: return null
			return Incoming(id, item.category, item.kind, item.title, item.body,
				item.link?.takeIf { it.type == "ALERT" }?.id, item.id)
		}
	}
}

/** Son görülen olay kimlikleri (sınırlı, en eski düşer); metin olarak saklanır. Tekilleştirme için. */
class RecentIds(private val capacity: Int = 300, serialized: String? = null) {

	private val ids = ArrayDeque<String>().apply { serialized?.split(',')?.filter { it.isNotBlank() }?.forEach { addLast(it) } }

	/** @return kimlik yeniyse `true` (ve kaydedilir); daha önce görüldüyse `false` */
	fun add(id: String): Boolean {
		if (id in ids) return false
		ids.addLast(id)
		while (ids.size > capacity) ids.removeFirst()
		return true
	}

	operator fun contains(id: String) = id in ids

	fun serialize(): String = ids.joinToString(",")
}
