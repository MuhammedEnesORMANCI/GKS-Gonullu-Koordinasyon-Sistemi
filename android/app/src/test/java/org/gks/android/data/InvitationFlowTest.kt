package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.data.session.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ID-15/16 davet koduyla kayıt ve ID-17/18 operasyon daveti yanıtı (gerçek backend gövde biçimleriyle). */
class InvitationFlowTest {

	private val backend = FakeBackend()
	private val store = MemoryStore()
	private val sessions = SessionManager(store)
	private val repository = InvitationRepository(backend.api(sessions), { "test-installation-0001" }, ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	private val form = Registration("abcd-2345", " Ayşe Kaya ", "Ayşe K.", "", "guclu-parola-2026")

	@Test
	fun lookupSendsNormalizedCodeInBodyWithoutAuth() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"kind":"NEW_USER","scopeName":"Sel","organizationName":"GKS","expiresAt":"2026-11-22T10:00:00Z"}""") }

		val result = repository.lookup(" abcd-2345 ")

		assertEquals("NEW_USER", (result as ApiResult.Success).value.kind)
		val request = backend.requests.single()
		assertEquals("/api/v1/invitations/lookup", request.path)
		assertEquals("""{"code":"ABCD2345"}""", request.body.readUtf8())
		assertNull(request.getHeader("Authorization"))
	}

	@Test
	fun registrationSendsContractFieldsThenClosesReturnedSession() = runBlocking {
		backend.handler = { request ->
			when (request.path) {
				"/api/v1/invitations/accept" -> MockResponse().json(201, LOGIN_BODY)
				"/api/v1/auth/logout" -> MockResponse().setResponseCode(204)
				else -> MockResponse().setResponseCode(404)
			}
		}

		val result = repository.register(form)

		assertTrue(result is ApiResult.Success)
		val accept = backend.requests[0]
		assertNotNull("ID-16 Idempotency-Key zorunlu", accept.getHeader("Idempotency-Key"))
		assertEquals(
			"""{"code":"ABCD2345","password":"guclu-parola-2026","fullName":"Ayşe Kaya","displayName":"Ayşe K.","installationId":"test-installation-0001"}""",
			accept.body.readUtf8(),
		)
		val logout = backend.requests[1]
		assertEquals("/api/v1/auth/logout", logout.path)
		assertEquals("Bearer reg-access", logout.getHeader("Authorization"))
		assertNull("Kayıt oturumu cihaza yazılmaz; kullanıcı giriş ekranına döner", store.session)
	}

	@Test
	fun networkFailureRetriesWithSameKeyAndCompletedReplayIsSuccess() = runBlocking {
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		assertEquals(ApiError.Network, (repository.register(form) as ApiResult.Failure).error)

		backend.handler = { MockResponse().json(409, problem(409, "ALREADY_COMPLETED")) }
		val retry = repository.register(form)

		assertTrue("Aynı anahtarla tekrar: hesap zaten açılmış", retry is ApiResult.Success)
		val keys = backend.requests.map { it.getHeader("Idempotency-Key") }
		assertEquals(2, keys.size)
		assertEquals("Ağ hatasından sonra aynı anahtar", keys[0], keys[1])
	}

	@Test
	fun fieldErrorsComeBackFromServer() = runBlocking {
		backend.handler = {
			MockResponse().json(400, problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"password","code":"NOT_ALLOWED_VALUE","message":"Bu parola çok yaygın"}]"""))
		}

		val error = (repository.register(form) as ApiResult.Failure).error

		assertEquals("Bu parola çok yaygın", error.problem?.errors?.single()?.message)
		assertEquals(1, backend.requests.size)
	}

	@Test
	fun expiredInvitationReportsStatus() = runBlocking {
		backend.handler = { MockResponse().json(410, problem(410, "INVITATION_INVALID", ""","invitationStatus":"EXPIRED"""")) }

		val error = (repository.lookup("ABCD2345") as ApiResult.Failure).error

		assertEquals("EXPIRED", error.problem?.invitationStatus)
	}

	@Test
	fun operationInvitationResponseUsesIdempotencyKey() = runBlocking {
		backend.handler = { MockResponse().json(200, "{}") }

		repository.respond("inv-1", accept = true)
		repository.respond("inv-2", accept = false)

		assertEquals("/api/v1/invitations/inv-1/accept", backend.requests[0].path)
		assertEquals("/api/v1/invitations/inv-2/decline", backend.requests[1].path)
		assertTrue(backend.requests.all { it.method == "POST" && it.getHeader("Idempotency-Key") != null })
	}

	private companion object {
		val LOGIN_BODY = """{"mfaRequired":false,"mustChangePassword":false,"accessToken":"reg-access","refreshToken":"reg-refresh",
			"accessTokenExpiresAt":"2026-11-15T12:53:33Z","session":{"sessionId":"$SESSION_ID","clientType":"ANDROID","mfaVerified":false,"expiresAt":"2026-12-15T12:38:33Z"}}"""
	}
}
