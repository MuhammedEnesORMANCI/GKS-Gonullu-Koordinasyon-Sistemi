package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.remote.OperationView
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.ui.common.DateTimes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Seçiciden gelen yerel zaman, istek gövdesine backend'in beklediği UTC ISO-8601 metni olarak gider. */
class DateTimeRequestTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("yonetim", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
	private val api = backend.api(sessions)
	private val istanbul = ZoneId.of("Europe/Istanbul")

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun taskTimeWindowIsSentAsUtcIso() = runBlocking {
		backend.handler = { MockResponse().json(201, """{"id":"t1","title":"Su","priority":"NORMAL","state":"DRAFT","version":1}""") }
		val start = DateTimes.toIso(LocalDate.of(2026, 12, 1), LocalTime.of(1, 30), istanbul)   // gün dönümü
		val end = DateTimes.toIso(LocalDate.of(2026, 12, 1), LocalTime.of(9, 0), istanbul)
		TaskManagementRepository(api, ApiCaller(GksJson))
			.create("o1", TaskDraft("Su dağıtımı", "", "NORMAL", "", start, end, null, null, listOf("u1"), emptyList()))

		val body = backend.requests.single().body.readUtf8()
		assertTrue(body, body.contains(""""timeWindow":{"start":"2026-11-30T22:30:00Z","end":"2026-12-01T06:00:00Z"}"""))
	}

	@Test
	fun plannedEndIsSentAsUtcIsoClearedWithNullAndKeptWhenUnchanged() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"id":"o1","name":"Sel","state":"PLANNED"}""") }
		val repository = ManagementRepository(api, ApiCaller(GksJson))
		val operation = OperationView(id = "o1", name = "Sel", description = "Eski", state = "PLANNED", plannedEndAt = "2026-12-01T05:30:00Z")
		val picked = DateTimes.toIso(LocalDate.of(2026, 12, 20), LocalTime.of(18, 0), istanbul)

		repository.update(operation, OperationDraft("Sel", "Eski", picked))
		repository.update(operation, OperationDraft("Sel", "Eski", null))                      // seçici ✕ ile temizlendi
		repository.update(operation, OperationDraft("Sel 2", "Eski", operation.plannedEndAt))   // bitiş değişmedi

		val (changed, cleared, kept) = backend.requests.map { it.body.readUtf8() }
		assertEquals("""{"plannedEndAt":"2026-12-20T15:00:00Z"}""", changed)
		assertEquals("""{"plannedEndAt":null}""", cleared)
		assertEquals("""{"name":"Sel 2"}""", kept)
	}
}
