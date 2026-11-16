package org.gks.android.data.realtime

/** RT-01 olayı (§12.2): veri yalnızca kimlik, durum ve zaman taşır; içerik REST ile çekilir. */
data class LiveEvent(val id: String?, val type: String, val data: String)

/**
 * `text/event-stream` satır ayrıştırıcısı (WHATWG SSE): `id`, `event`, `data` (çok satırlı birleşir), `retry`;
 * `:` ile başlayan satır yorumdur (sunucunun `: ping`'i). Boş satır olayı tamamlar. Durumludur, tek akış içindir.
 */
class SseParser {

	private var id: String? = null
	private var type: String? = null
	private val data = StringBuilder()

	/** Son `id:` (yeniden bağlanmada `Last-Event-ID`). */
	var lastEventId: String? = null
		private set

	/** Sunucunun önerdiği yeniden bağlanma süresi (`retry:`), yoksa `null`. */
	var retryMillis: Long? = null
		private set

	/** @return satır bir olayı tamamladıysa olay */
	fun feed(line: String): LiveEvent? {
		if (line.isEmpty()) {
			val complete = if (data.isNotEmpty() || type != null) {
				LiveEvent(id, type ?: "message", data.toString())
			} else null
			id?.let { lastEventId = it }
			id = null
			type = null
			data.setLength(0)
			return complete
		}
		if (line.startsWith(":")) return null
		val colon = line.indexOf(':')
		val field = if (colon < 0) line else line.substring(0, colon)
		var value = if (colon < 0) "" else line.substring(colon + 1)
		if (value.startsWith(" ")) value = value.substring(1)
		when (field) {
			"id" -> id = value
			"event" -> type = value
			"data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
			"retry" -> value.toLongOrNull()?.let { retryMillis = it }
		}
		return null
	}
}
