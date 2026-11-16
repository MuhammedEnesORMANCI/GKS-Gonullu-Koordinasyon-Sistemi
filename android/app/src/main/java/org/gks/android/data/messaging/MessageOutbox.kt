package org.gks.android.data.messaging

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Çevrimdışı gönderim kuyruğu (ADR-024). Ağ yokken yazılan mesaj burada bekler; ağ gelince aynı
 * {@code Idempotency-Key} ile gönderilir (sunucu tekrarı tekilleştirir). Sunucunun reddettiği mesaj "başarısız"
 * olarak kalır ve elle yeniden denenebilir. Kalıcıdır (uygulama kapanınca kaybolmaz).
 */
@Serializable
data class PendingMessage(
	val key: String,
	val channelId: String,
	val body: String,
	val createdAt: String,
	val failed: Boolean = false,
	val error: String? = null,
)

/** Kalıcı depo soyutlaması (üretimde SharedPreferences, testte bellek). */
interface OutboxStore {
	fun read(): String?
	fun write(value: String)
}

class MessageOutbox(private val store: OutboxStore) {

	private val json = Json { ignoreUnknownKeys = true }
	private val serializer = ListSerializer(PendingMessage.serializer())
	private val _items = MutableStateFlow(load())
	val items: StateFlow<List<PendingMessage>> = _items.asStateFlow()

	private fun load(): List<PendingMessage> =
		store.read()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

	@Synchronized
	private fun update(transform: (List<PendingMessage>) -> List<PendingMessage>) {
		val next = transform(_items.value)
		_items.value = next
		store.write(json.encodeToString(serializer, next))
	}

	fun add(channelId: String, body: String, key: String = UUID.randomUUID().toString(), now: Instant = Instant.now()): PendingMessage {
		val item = PendingMessage(key, channelId, body, now.toString())
		update { it + item }
		return item
	}

	fun remove(key: String) = update { list -> list.filterNot { it.key == key } }

	fun markFailed(key: String, error: String) = update { list -> list.map { if (it.key == key) it.copy(failed = true, error = error) else it } }

	fun markPending(key: String) = update { list -> list.map { if (it.key == key) it.copy(failed = false, error = null) else it } }

	/** Gönderilmeyi bekleyenler (başarısızlar hariç), en eskiden. */
	fun pending(): List<PendingMessage> = _items.value.filter { !it.failed }

	fun forChannel(channelId: String): List<PendingMessage> = _items.value.filter { it.channelId == channelId }
}
