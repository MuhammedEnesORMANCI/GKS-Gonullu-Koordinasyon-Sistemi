package org.gks.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.remote.AlertPreviewView
import org.gks.android.data.remote.AlertRecipientRowView
import org.gks.android.data.remote.EventLocation
import org.gks.android.data.remote.GeneralAlertDraftRequest
import org.gks.android.data.remote.GeneralAlertTargets
import org.gks.android.data.remote.MyAlertView
import org.gks.android.data.remote.UserRef
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.ui.alerts.AlertFollowUpViewModel
import org.gks.android.ui.alerts.GeneralAlertViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-026: genel alarm isteği, güçlü onay (`confirmed=true`), alıcı görünümü ve takip grupları. */
class GeneralAlertTest {

	private val preview = """{"alertId":"a1","version":1,"audienceHash":"h1","recipientCount":2,
		"recipients":[{"id":"u1","displayName":"Ayşe"},{"id":"u2","displayName":"Burak"}],"message":"Tatbikat","scope":"GENERAL"}"""
	private val view = """{"id":"a1","scope":"GENERAL","state":"OPEN","message":"Tatbikat","counts":{"total":2,"pending":2}}"""

	@Test
	fun createAndSendCarryTargetsAndConfirmationWithIdempotencyKey() = runBlocking {
		val backend = FakeBackend()
		try {
			val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
			val repo = AlertManagementRepository(backend.api(sessions), ApiCaller(GksJson))
			backend.handler = { MockResponse().json(201, preview) }
			val created = repo.createGeneral(GeneralAlertDraftRequest("Tatbikat", targets = GeneralAlertTargets(organization = true,
				operationIds = listOf("op1")), eventLocation = EventLocation(40.98, 29.05)))
			val p = (created as ApiResult.Success).value
			assertEquals(2, p.recipientCount)
			val draftBody = GksJson.parseToJsonElement(backend.requests.last().body.readUtf8()).jsonObject
			assertEquals("/api/v1/general-alerts", backend.requests.last().path)
			assertEquals("true", draftBody["targets"]!!.jsonObject["organization"]!!.jsonPrimitive.content)
			assertEquals("40.98", draftBody["eventLocation"]!!.jsonObject["lat"]!!.jsonPrimitive.content)

			backend.handler = { MockResponse().json(202, view) }
			assertTrue(repo.send(p) is ApiResult.Success)
			val send = backend.requests.last()
			assertEquals("/api/v1/alerts/a1/send", send.path)
			assertTrue(send.getHeader("Idempotency-Key")!!.isNotBlank())
			val sendBody = GksJson.parseToJsonElement(send.body.readUtf8()).jsonObject
			assertEquals("true", sendBody["confirmed"]!!.jsonPrimitive.content)
			assertEquals("h1", sendBody["audienceHash"]!!.jsonPrimitive.content)
		}
		finally {
			backend.shutdown()
		}
	}

	@Test
	fun recipientViewWithoutOperationParsesAsGeneral() {
		val alert = GksJson.decodeFromString(MyAlertView.serializer(), """{"id":"a1","scope":"GENERAL","state":"OPEN",
			"message":"Binayı boşaltın","priority":"CRITICAL","eventLocation":{"lat":40.5,"lon":29.1},
			"recipient":{"id":"r1","state":"SENT"}}""")
		assertNull(alert.operationId)
		assertEquals("GENERAL", alert.scope)
		assertEquals(EventLocation(40.5, 29.1), alert.eventLocation)
		GksJson.decodeFromString(AlertPreviewView.serializer(), preview)
	}

	@Test
	fun eventLocationValidationAndFollowUpGroups() {
		assertNull(GeneralAlertViewModel.eventLocation("", "").getOrThrow())
		assertEquals(EventLocation(40.5, 29.0), GeneralAlertViewModel.eventLocation("40,5", "29").getOrThrow())
		assertTrue(GeneralAlertViewModel.eventLocation("95", "29").isFailure)
		assertTrue(GeneralAlertViewModel.eventLocation("40", "").isFailure)

		fun row(id: String, state: String, option: String? = null) = AlertRecipientRowView(id, UserRef(id, id), state, option)
		val (help, responded, waiting) = AlertFollowUpViewModel.groups(listOf(
			row("a", "RESPONDED", "NEED_HELP"), row("b", "RESPONDED", "ON_MY_WAY"), row("c", "SENT"), row("d", "UNANSWERED")))
		assertEquals(listOf("a"), help.map { it.id })
		assertEquals(listOf("b"), responded.map { it.id })
		assertEquals(listOf("c", "d"), waiting.map { it.id })
	}
}
