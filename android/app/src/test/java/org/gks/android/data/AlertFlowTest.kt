package org.gks.android.data

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AL-07/08/13/14/15 alıcı akışı: gövdeler backend AlertApiTests'teki biçimde. */
class AlertFlowTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
	private val clock = Clock.fixed(Instant.parse("2026-11-16T09:00:00Z"), ZoneOffset.UTC)
	private val repository = AlertRepository(backend.api(sessions), ApiCaller(GksJson), clock = clock)

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun openAlertsAskForOpenStateAndParseRecipient() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"items":[$ALERT]}""") }

		val alert = (repository.openAlerts() as ApiResult.Success).value.single()

		assertEquals("/api/v1/me/alerts?state=OPEN&limit=100", backend.requests[0].path)
		assertEquals("r1", alert.recipient.id)
		assertEquals(listOf("RECEIVED", "NEED_HELP", "ON_MY_WAY"), alert.responseOptions)
		assertEquals("Sel müdahalesi", alert.operationName)
	}

	@Test
	fun signalsPostToRecipientPaths() = runBlocking {
		backend.handler = { MockResponse().setResponseCode(204) }

		assertTrue(repository.markDelivered("r1") is ApiResult.Success)
		assertTrue(repository.markSeen("r1") is ApiResult.Success)

		assertEquals("/api/v1/alert-recipients/r1/delivered", backend.requests[0].path)
		assertEquals("/api/v1/alert-recipients/r1/seen", backend.requests[1].path)
		assertTrue(backend.requests[1].body.readUtf8().contains("2026-11-16T09:00:00Z"))
	}

	@Test
	fun networkRetryKeepsKeyAndBodyNewOptionGetsNewKey() = runBlocking {
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		assertTrue(repository.respond("r1", "NEED_HELP") is ApiResult.Failure)
		backend.handler = { MockResponse().json(200, ALERT.replace("\"state\":\"SENT\"", "\"state\":\"RESPONDED\",\"responseOption\":\"NEED_HELP\"")) }
		val answered = (repository.respond("r1", "NEED_HELP") as ApiResult.Success).value
		repository.respond("r1", "ON_MY_WAY")

		val first = backend.requests[0]
		val retry = backend.requests[1]
		assertEquals("/api/v1/alert-recipients/r1/responses", first.path)
		assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
		val body = first.body.readUtf8()
		assertEquals(body, retry.body.readUtf8())
		assertTrue(body.contains("\"option\":\"NEED_HELP\"") && body.contains("\"respondedAtDevice\":\"2026-11-16T09:00:00Z\""))
		assertNotEquals(first.getHeader("Idempotency-Key"), backend.requests[2].getHeader("Idempotency-Key"))
		assertEquals("NEED_HELP", answered.recipient.responseOption)
	}

	@Test
	fun closedAlertResponseIsReportedAsNotOpen() = runBlocking {
		backend.handler = { MockResponse().json(409, problem(409, "ALERT_NOT_OPEN", ",\"alertState\":\"CANCELLED\"")) }
		val failure = repository.respond("r1", "RECEIVED") as ApiResult.Failure
		assertEquals("ALERT_NOT_OPEN", failure.error.problem?.code)
	}

	private companion object {
		const val ALERT = """{"id":"a1","operationId":"o1","operationName":"Sel müdahalesi","state":"OPEN",
			"message":"Su baskını, yüksek kata çıkın","priority":"CRITICAL","responseOptions":["RECEIVED","NEED_HELP","ON_MY_WAY"],
			"sentAt":"2026-11-16T08:59:00Z","recipient":{"id":"r1","state":"SENT"}}"""
	}
}
