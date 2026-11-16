package org.gks.android.data

import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LC-01 – LC-04 (K-15 – K-16, ADR-019): gövdeler backend LocationApiTests'teki biçimde. */
class LocationFlowTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
	private val repository = LocationRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun explicitStartUnavailableAndStopUseSharingEndpoint() = runBlocking {
		backend.handler = { request ->
			if (request.method == "DELETE") MockResponse().setResponseCode(204) else MockResponse().json(200, SHARING)
		}

		val view = (repository.start("o1") as ApiResult.Success).value
		repository.reportUnavailable("o1", "GPS_OFF")
		assertTrue(repository.stop("o1") is ApiResult.Success)

		assertEquals("ACTIVE", view.state)
		assertEquals("/api/v1/operations/o1/location-sharing", backend.requests[0].path)
		assertEquals("{}", backend.requests[0].body.readUtf8())
		assertEquals("""{"availability":"UNAVAILABLE","reason":"GPS_OFF"}""", backend.requests[1].body.readUtf8())
		assertEquals("DELETE", backend.requests[2].method)
	}

	@Test
	fun measurementBodyAndServerStopSignal() = runBlocking {
		backend.handler = { MockResponse().setResponseCode(204) }
		assertTrue(repository.send("o1", 41.0082, 28.9784, 12.5, Instant.parse("2026-11-16T09:00:00Z")) is ApiResult.Success)
		assertEquals("/api/v1/operations/o1/locations", backend.requests[0].path)
		assertEquals("""{"lat":41.0082,"lon":28.9784,"accuracy":12.5,"measuredAt":"2026-11-16T09:00:00Z"}""",
			backend.requests[0].body.readUtf8())

		backend.handler = { MockResponse().json(409, problem(409, "LOCATION_SHARING_STOPPED")) }
		val stopped = repository.send("o1", 41.0, 29.0, 5.0, Instant.parse("2026-11-16T09:01:00Z"))
		assertTrue("sunucu paylaşımı bitirdi: servis durur", LocationRepository.shouldStop(stopped))
		backend.handler = { MockResponse().json(503, problem(503, "SERVICE_UNAVAILABLE")) }
		assertFalse("geçici hata servisi durdurmaz", LocationRepository.shouldStop(repository.send("o1", 41.0, 29.0, 5.0, Instant.now())))
	}

	@Test
	fun mySharingsParse() = runBlocking {
		backend.handler = { MockResponse().json(200, "[$SHARING]") }
		val mine = (repository.mine() as ApiResult.Success).value
		assertEquals("/api/v1/me/location-sharing", backend.requests[0].path)
		assertEquals("2026-11-16T09:00:05Z", mine.single().lastSentAt)
	}

	private companion object {
		const val SHARING = """{"operationId":"o1","operationName":"Sel müdahalesi","state":"ACTIVE",
			"startedAt":"2026-11-16T08:50:00Z","lastSentAt":"2026-11-16T09:00:05Z"}"""
	}
}
