package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.data.remote.MeetingPointRequest
import org.gks.android.data.remote.MyAssignmentView
import org.gks.android.data.remote.TaskSummary
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.ui.tasks.AssignmentSections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Görev yanıtı (TK-10/11/12), sayfalama ve grup lideri güncellemeleri (GR-07/08). */
class VolunteerFlowTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-15T12:00:00Z")))
	private val repository = VolunteerRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun acceptAndRejectSendIdempotencyKeyWithBearer() = runBlocking {
		backend.handler = { MockResponse().json(200, "{}") }

		assertTrue(repository.respondToAssignment("a1", accept = true) is ApiResult.Success)
		assertTrue(repository.respondToAssignment("a2", accept = false) is ApiResult.Success)

		val (accept, reject) = backend.requests
		assertEquals("/api/v1/assignments/a1/accept", accept.path)
		assertEquals("/api/v1/assignments/a2/reject", reject.path)
		backend.requests.forEach {
			assertEquals("POST", it.method)
			assertEquals("Bearer access", it.getHeader("Authorization"))
			assertNotNull("TK-11/12 Idempotency-Key zorunlu", it.getHeader("Idempotency-Key"))
		}
	}

	@Test
	fun retryAfterNetworkErrorReusesKeyButNewActionGetsNewKey() = runBlocking {
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		repository.respondToAssignment("a1", accept = true)
		backend.handler = { MockResponse().json(409, problem(409, "CAPACITY_FULL")) }
		val second = repository.respondToAssignment("a1", accept = true)
		backend.handler = { MockResponse().json(200, "{}") }
		repository.respondToAssignment("a1", accept = true)

		val keys = backend.requests.map { it.getHeader("Idempotency-Key") }
		assertEquals("Ağ hatasından sonra aynı anahtar", keys[0], keys[1])
		assertNotEquals("Kesin sonuçtan (409) sonra yeni anahtar", keys[1], keys[2])
		assertEquals("CAPACITY_FULL", (second as ApiResult.Failure).error.problem?.code)
	}

	@Test
	fun myAssignmentsFollowsCursorPages() = runBlocking {
		backend.handler = { request ->
			if (request.path!!.contains("cursor=c2")) {
				MockResponse().json(200, """{"items":[${assignment("a2", "ACCEPTED", "IN_PROGRESS")}]}""")
			} else {
				MockResponse().json(200, """{"items":[${assignment("a1", "PENDING", "PUBLISHED")}],"nextCursor":"c2"}""")
			}
		}

		val items = (repository.myAssignments() as ApiResult.Success).value

		assertEquals(listOf("a1", "a2"), items.map { it.id })
		assertEquals("/api/v1/me/assignments?limit=100", backend.requests[0].path)
	}

	@Test
	fun assignmentSectionsSplitByStatusAndTaskState() {
		fun item(id: String, status: String, state: String) =
			MyAssignmentView(id, status, task = TaskSummary(id = "t$id", title = "G", priority = "NORMAL", state = state))
		val sections = AssignmentSections.of(
			listOf(item("1", "PENDING", "PUBLISHED"), item("2", "ACCEPTED", "IN_PROGRESS"), item("3", "PENDING", "CANCELLED"), item("4", "REJECTED", "PUBLISHED")),
		)
		assertEquals(listOf("1"), sections.waiting.map { it.id })
		assertEquals(listOf("2"), sections.accepted.map { it.id })
		assertEquals("İptal edilen görevin bekleyen ataması yanıtlanamaz → geçmiş", setOf("3", "4"), sections.past.map { it.id }.toSet())
	}

	@Test
	fun leaderClearsDescriptionWithExplicitNull() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"id":"g1","name":"Ekip","operationId":"o1"}""") }

		repository.updateGroupDescription("g1", "  ")
		repository.updateGroupDescription("g1", " Yeni açıklama ")

		assertEquals("PATCH", backend.requests[0].method)
		assertEquals("""{"description":null}""", backend.requests[0].body.readUtf8())
		assertEquals("""{"description":"Yeni açıklama"}""", backend.requests[1].body.readUtf8())
	}

	@Test
	fun meetingPointSendsOnlyGivenFields() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"address":"Okul bahçesi","time":"2026-11-16T07:00:00Z"}""") }

		repository.updateMeetingPoint("g1", MeetingPointRequest(address = "Okul bahçesi", time = "2026-11-16T07:00:00Z"))

		val request = backend.requests.single()
		assertEquals("PUT", request.method)
		assertEquals("/api/v1/groups/g1/meeting-point", request.path)
		assertEquals("""{"address":"Okul bahçesi","time":"2026-11-16T07:00:00Z"}""", request.body.readUtf8())
	}

	private fun assignment(id: String, status: String, state: String) =
		"""{"id":"$id","status":"$status","respondBy":"2026-11-15T14:00:00Z","overdue":false,
			"task":{"id":"t$id","operationId":"o1","operationName":"Sel","title":"Su dağıtımı","priority":"HIGH","state":"$state",
			"acceptedCount":1,"capacity":5,"createdAt":"2026-11-15T12:00:00Z"}}"""
}
