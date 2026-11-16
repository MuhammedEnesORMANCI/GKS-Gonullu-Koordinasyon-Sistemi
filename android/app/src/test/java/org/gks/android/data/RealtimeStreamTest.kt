package org.gks.android.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gks.android.data.realtime.LiveEvent
import org.gks.android.data.realtime.RealtimeStream
import org.gks.android.data.realtime.SseParser
import org.gks.android.data.remote.InboxItem
import org.gks.android.ui.notify.LiveNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RT-01 Android istemcisi (ADR-021): ayrıştırma, yeniden bağlanma + Last-Event-ID, yerel bildirim seçimi. */
class RealtimeStreamTest {

	@Test
	fun parserHandlesFieldsCommentsMultilineDataAndRetry() {
		val parser = SseParser()
		val lines = listOf(":bağlandı", "retry:3000", "", "id:1731-000001", "event:inbox.changed", "data:{\"a\":1,", "data: \"b\":2}", "",
			": ping", "event:session.revoked", "data:{}", "")
		val events = lines.mapNotNull(parser::feed)
		assertEquals(listOf(LiveEvent("1731-000001", "inbox.changed", "{\"a\":1,\n\"b\":2}"), LiveEvent(null, "session.revoked", "{}")), events)
		assertEquals(3000L, parser.retryMillis)
		assertEquals("1731-000001", parser.lastEventId)
	}

	@Test
	fun reconnectsAfterDropAndSendsLastEventId() = runBlocking {
		val server = MockWebServer()
		val requests = CopyOnWriteArrayList<RecordedRequest>()
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				requests += request
				val n = requests.size
				return MockResponse().setHeader("Content-Type", "text/event-stream")
					.setBody("retry:50\n\nid:1-00000$n\nevent:inbox.changed\ndata:{\"change\":\"CREATED\",\"n\":$n}\n\n")
			}
		}
		server.start()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val stream = RealtimeStream(OkHttpClient(), server.url("/").toString(), scope, hasSession = { true }, backoff = { 50L })
		try {
			stream.acquire()
			val second = withTimeout(5_000) { stream.events.first { it.data.contains("\"n\":2") } }
			assertEquals("inbox.changed", second.type)
			assertEquals("/api/v1/stream", requests[0].path)
			assertEquals("text/event-stream", requests[0].getHeader("Accept"))
			assertNull(requests[0].getHeader("Last-Event-ID"))
			assertEquals("kopunca kaldığı yerden", "1-000001", requests[1].getHeader("Last-Event-ID"))
		}
		finally {
			stream.release()
			scope.cancel()
			server.shutdown()
		}
	}

	/** RAPOR_7 madde 2: mesaj olayı bağlantı açıkken (yavaş/parça parça ileten vekil) gelir; sıkıştırma istenmez. */
	@Test
	fun messageEventsArriveWhileConnectionIsOpenThroughSlowProxy() = runBlocking {
		val server = MockWebServer()
		val requests = CopyOnWriteArrayList<RecordedRequest>()
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				requests += request
				val ping = ": ping\n\n".repeat(200)
				return MockResponse().setHeader("Content-Type", "text/event-stream")
					.setHeader("Cache-Control", "no-cache, no-transform")
					.setBody(":bağlandı\n\nid:9\nevent:message.created\ndata:{\"channelId\":\"c1\",\"messageId\":\"m1\"}\n\n$ping")
					.throttleBody(64, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
			}
		}
		server.start()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val stream = RealtimeStream(OkHttpClient(), server.url("/").toString(), scope, hasSession = { true }, backoff = { 50L })
		try {
			stream.acquire()
			val event = withTimeout(3_000) { stream.events.first { it.type == "message.created" } }
			assertEquals("{\"channelId\":\"c1\",\"messageId\":\"m1\"}", event.data)
			assertTrue("olay geldiğinde bağlantı hâlâ açık", stream.connected.value)
			assertEquals("identity", requests[0].getHeader("Accept-Encoding"))
		}
		finally {
			stream.release()
			scope.cancel()
			server.shutdown()
		}
	}

	/** RAPOR_7 madde 2: akış bağlı değilken açık sohbet 5 sn'de bir yoklanır; bağlanınca durur, kopunca sürer. */
	@Test
	fun chatPollsOnlyWhileStreamIsDisconnected() = kotlinx.coroutines.test.runTest {
		val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
		var refreshes = 0
		val job = launch { org.gks.android.ui.messaging.pollWhileDisconnected(connected) { refreshes++ } }
		advanceTimeBy(org.gks.android.ui.messaging.CHAT_POLL_MILLIS * 2 + 1)
		assertEquals(2, refreshes)
		connected.value = true
		advanceTimeBy(60_000)
		assertEquals(2, refreshes)
		connected.value = false
		advanceTimeBy(org.gks.android.ui.messaging.CHAT_POLL_MILLIS + 1)
		assertEquals(3, refreshes)
		job.cancel()
	}

	@Test
	fun noConnectionWithoutSession() = runBlocking {
		val server = MockWebServer()
		server.start()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		val stream = RealtimeStream(OkHttpClient(), server.url("/").toString(), scope, hasSession = { false })
		stream.acquire()
		kotlinx.coroutines.delay(300)
		assertEquals(0, server.requestCount)
		stream.release()
		scope.cancel()
		server.shutdown()
	}

	@Test
	fun onlyNewUnreadAlertsAndAnnouncementsAreNotifiedAlertIsUrgent() {
		fun item(id: String, category: String, kind: String, read: Boolean = false) =
			InboxItem(id, category, kind, "HIGH", "Başlık", "Metin", "2026-11-16T09:00:00Z", if (read) "2026-11-16T09:01:00Z" else null)
		val items = listOf(item("a", "ALERT", "ALERT_SENT"), item("b", "ANNOUNCEMENT", "ANNOUNCEMENT"), item("c", "TASK", "TASK_ASSIGNED"),
			item("d", "ANNOUNCEMENT", "ANNOUNCEMENT", read = true), item("e", "ALERT", "ALERT_CLOSED"))
		assertEquals(listOf("a", "b", "e"), LiveNotifier.selectNew(items).map { it.id })
		assertTrue(LiveNotifier.isUrgent(items[0]))
		assertFalse(LiveNotifier.isUrgent(items[4]))
		assertFalse(LiveNotifier.isUrgent(items[1]))
	}
}
