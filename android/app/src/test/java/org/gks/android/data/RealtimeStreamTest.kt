package org.gks.android.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
		assertEquals(listOf("a", "e"), LiveNotifier.selectNew(items, setOf("b")).map { it.id })
		assertTrue(LiveNotifier.isUrgent(items[0]))
		assertFalse(LiveNotifier.isUrgent(items[4]))
		assertFalse(LiveNotifier.isUrgent(items[1]))
	}
}
