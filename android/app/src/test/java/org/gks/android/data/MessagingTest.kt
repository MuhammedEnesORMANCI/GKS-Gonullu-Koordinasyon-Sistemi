package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.alarm.MessagePush
import org.gks.android.data.messaging.MessageOutbox
import org.gks.android.data.messaging.MessagingRepository
import org.gks.android.data.messaging.OutboxStore
import org.gks.android.data.messaging.SendOutcome
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.ui.openTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-024: çevrimdışı kuyruk ve tekilleştirme, içeriksiz push yükü, açık kanalda bildirim bastırma. */
class MessagingTest {

	private class MemoryOutboxStore : OutboxStore {
		var value: String? = null
		override fun read(): String? = value
		override fun write(value: String) { this.value = value }
	}

	private val ok = """{"id":"m1","channelId":"c1","kind":"USER","body":"Su lazım","state":"VISIBLE","createdAt":"2026-11-16T09:00:00Z","mine":true}"""

	private fun repository(backend: FakeBackend, store: OutboxStore = MemoryOutboxStore()): MessagingRepository {
		val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
		return MessagingRepository(backend.api(sessions), ApiCaller(GksJson), MessageOutbox(store))
	}

	@Test
	fun offlineMessageIsQueuedPersistedAndSentLaterWithTheSameIdempotencyKey() = runBlocking {
		val backend = FakeBackend()
		try {
			val store = MemoryOutboxStore()
			val repo = repository(backend, store)
			backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
			assertEquals(SendOutcome.Queued, repo.send("c1", "  Su lazım  "))
			val queued = repo.outbox.pending().single()
			assertEquals("Su lazım", queued.body)
			// Uygulama yeniden açıldı: kuyruk kalıcı.
			val reopened = MessageOutbox(store)
			assertEquals(queued.key, reopened.pending().single().key)

			backend.handler = { MockResponse().json(201, ok) }
			assertEquals(1, repo.flush())
			assertTrue(repo.outbox.items.value.isEmpty())
			val sent = backend.requests.last()
			assertEquals("POST", sent.method)
			assertEquals("/api/v1/channels/c1/messages", sent.path)
			assertEquals(queued.key, sent.getHeader("Idempotency-Key"))
			// Önceki (kopan) denemeler de aynı anahtarı kullandı: sunucu tekrarı tekilleştirir.
			assertTrue(backend.requests.filter { it.path == "/api/v1/channels/c1/messages" }.all { it.getHeader("Idempotency-Key") == queued.key })
		}
		finally {
			backend.shutdown()
		}
	}

	@Test
	fun rejectedMessageIsMarkedFailedVisibleAndRetryable() = runBlocking {
		val backend = FakeBackend()
		try {
			val repo = repository(backend)
			backend.handler = { MockResponse().json(403, problem(403, "FORBIDDEN")) }
			assertTrue(repo.send("c1", "Yazamam") is SendOutcome.Rejected)
			val failed = repo.outbox.items.value.single()
			assertTrue(failed.failed)
			assertEquals("FORBIDDEN", failed.error)
			assertEquals(0, repo.flush())   // başarısızlar kendiliğinden yeniden gönderilmez
			backend.handler = { MockResponse().json(201, ok) }
			assertTrue(repo.retry(failed.key) is SendOutcome.Sent)
			assertTrue(repo.outbox.items.value.isEmpty())
			assertEquals(failed.key, backend.requests.last().getHeader("Idempotency-Key"))
		}
		finally {
			backend.shutdown()
		}
	}

	@Test
	fun serverErrorKeepsMessagePendingNotFailed() = runBlocking {
		val backend = FakeBackend()
		try {
			val repo = repository(backend)
			backend.handler = { MockResponse().json(503, problem(503, "SERVICE_UNAVAILABLE")) }
			assertEquals(SendOutcome.Queued, repo.send("c1", "Tekrar denenecek"))
			assertFalse(repo.outbox.items.value.single().failed)
		}
		finally {
			backend.shutdown()
		}
	}

	@Test
	fun pushPayloadCarriesOnlyIdsAndOpenChannelIsNotNotified() {
		val push = MessagePush.fromData(mapOf("type" to "MESSAGE", "channelId" to "c1", "messageId" to "m1", "notificationId" to "n1"))!!
		assertEquals(MessagePush("c1", "m1"), push)
		assertNull(MessagePush.fromData(mapOf("type" to "ALERT", "channelId" to "c1")))
		assertNull(MessagePush.fromData(mapOf("type" to "MESSAGE")))
		assertFalse(MessagePush.shouldNotify(openChannelId = "c1", channelId = "c1"))
		assertTrue(MessagePush.shouldNotify(openChannelId = "c2", channelId = "c1"))
		assertTrue(MessagePush.shouldNotify(openChannelId = null, channelId = "c1"))
		assertEquals("messages/c1", openTarget("channel:c1"))
		assertNull(openTarget("alert:a1"))
	}
}
