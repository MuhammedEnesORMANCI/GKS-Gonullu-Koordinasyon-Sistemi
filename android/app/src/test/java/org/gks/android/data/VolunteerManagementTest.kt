package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Gönüllü yönetimi: OR-06 katılımcılar, ID-12 davet (Z), ID-13 bekleyenler, ID-14 iptal. */
class VolunteerManagementTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("yonetim", "refresh", SESSION_ID, "2026-11-15T12:00:00Z")))
	private val repository = ManagementRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	private val invitation = """{"id":"i1","kind":"OPERATION","status":"PENDING","scopeType":"OPERATION","scopeId":"o1",
		"email":"ayse@example.org","expiresAt":"2026-11-22T10:00:00Z"}"""

	@Test
	fun inviteSendsNormalizedEmailWithKeyAndRetriesWithSameKey() = runBlocking {
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		assertEquals(ApiError.Network, (repository.invite("o1", " Ayse@Example.org ") as ApiResult.Failure).error)
		backend.handler = { MockResponse().json(201, invitation) }
		repository.invite("o1", "ayse@example.org")

		val (first, retry) = backend.requests
		assertEquals("POST", first.method)
		assertEquals("/api/v1/invitations", first.path)
		assertEquals("""{"email":"ayse@example.org","scopeType":"OPERATION","scopeId":"o1"}""", first.body.readUtf8())
		assertEquals("Bearer yonetim", first.getHeader("Authorization"))
		assertNotNull("ID-12 Idempotency-Key zorunlu", first.getHeader("Idempotency-Key"))
		assertEquals("Ağ hatasından sonra ikinci davet oluşmaz", first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
	}

	/** RAPOR_7 madde 4: aday araması ve doğrudan ekleme; ağ tekrarında aynı anahtar; zaten katılımcı hatası adlı. */
	@Test
	fun directAddSearchesCandidatesAndAddsWithIdempotencyKey() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"items":[{"id":"u1","displayName":"Ayşe"}],"nextCursor":"c2"}""") }
		val page = (repository.directCandidates("o1", " ayşe ", cursor = "c1") as ApiResult.Success).value
		assertEquals("u1", page.items.single().id)
		assertEquals("/api/v1/operations/o1/participant-candidates?q=ay%C5%9Fe&cursor=c1&limit=100", backend.requests.last().path)

		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		assertEquals(ApiError.Network, (repository.addParticipants("o1", listOf("u2", "u1")) as ApiResult.Failure).error)
		backend.handler = { MockResponse().json(201, """{"added":[{"user":{"id":"u1","displayName":"Ayşe"}},{"user":{"id":"u2","displayName":"Burak"}}]}""") }
		val added = (repository.addParticipants("o1", listOf("u2", "u1")) as ApiResult.Success).value
		assertEquals(listOf("Ayşe", "Burak"), added.added.map { it.user.displayName })
		val (first, retry) = backend.requests.takeLast(2)
		assertEquals("/api/v1/operations/o1/participants", first.path)
		assertEquals("""{"userIds":["u2","u1"]}""", first.body.readUtf8())
		assertNotNull(first.getHeader("Idempotency-Key"))
		assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))

		backend.handler = { MockResponse().setResponseCode(409).setHeader("Content-Type", "application/problem+json")
			.setBody("""{"status":409,"code":"ALREADY_EXISTS","conflict":"ALREADY_PARTICIPANT","userIds":["u3"],"displayNames":["Cem"]}""") }
		val problem = ((repository.addParticipants("o1", listOf("u3")) as ApiResult.Failure).error as ApiError.Http).problem
		assertEquals("ALREADY_PARTICIPANT", problem.conflict)
		assertEquals(listOf("Cem"), problem.displayNames)
	}

	@Test
	fun pendingInvitationsRevokeAndParticipants() = runBlocking {
		backend.handler = { request ->
			when {
				request.path!!.contains("/participants") -> MockResponse().json(200,
					"""{"items":[{"user":{"id":"u1","displayName":"Ali K.","status":"ACTIVE"},"joinedAt":"2026-11-15T10:00:00Z",
					"groups":[{"id":"g1","name":"Arama ekibi"}],"openAssignmentCount":2}]}""")
				request.path!!.endsWith("/revoke") -> MockResponse().json(200, invitation.replace("PENDING", "REVOKED"))
				else -> MockResponse().json(200, """{"items":[$invitation]}""")
			}
		}

		assertEquals("ayse@example.org", (repository.pendingInvitations("o1") as ApiResult.Success).value.single().email)
		repository.revokeInvitation("i1")
		val participant = (repository.participants("o1") as ApiResult.Success).value.single()

		val (list, revoke) = backend.requests
		assertEquals("/api/v1/invitations?scopeType=OPERATION&scopeId=o1&status=PENDING&limit=100", list.path)
		assertEquals("/api/v1/invitations/i1/revoke", revoke.path)
		assertEquals("POST", revoke.method)
		assertEquals("Arama ekibi", participant.groups.single().name)
		assertEquals(2, participant.openAssignmentCount)
	}

	@Test
	fun duplicatePendingInvitationReportsConflict() = runBlocking {
		backend.handler = { MockResponse().json(409, problem(409, "ALREADY_EXISTS", ""","conflict":"INVITATION_PENDING"""")) }
		val error = (repository.invite("o1", "ayse@example.org") as ApiResult.Failure).error
		assertEquals("INVITATION_PENDING", error.problem?.conflict)
	}
}
