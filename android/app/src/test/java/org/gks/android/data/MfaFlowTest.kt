package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.session.SessionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ADR-014: ADM/KOR Android girişi TOTP ister; oturum yalnızca ID-04'ten sonra açılır. */
class MfaFlowTest {

	private val backend = FakeBackend()
	private val store = MemoryStore()
	private val sessions = SessionManager(store)
	private val repository = AuthRepository(backend.api(sessions), sessions, { "test-installation-0001" }, ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun managementLoginReturnsTicketWithoutSession() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"mfaRequired":true,"mfaEnrollmentRequired":true,"mfaTicket":"bilet-1","mustChangePassword":false}""") }

		val outcome = (repository.login("kor@example.org", "parola") as ApiResult.Success).value

		assertEquals(LoginOutcome.MfaRequired("bilet-1", enrollmentRequired = true), outcome)
		assertNull("TOTP öncesi oturum yazılmaz", store.session)
		assertEquals(
			"""{"email":"kor@example.org","password":"parola","clientType":"ANDROID","installationId":"test-installation-0001"}""",
			backend.requests.single().body.readUtf8(),
		)
	}

	@Test
	fun enrollThenVerifyStoresManagementSession() = runBlocking {
		backend.handler = { request ->
			when (request.path) {
				"/api/v1/auth/mfa/enroll" -> MockResponse().json(200, """{"otpauthUri":"otpauth://totp/GKS:kor?secret=ABC","secret":"ABCDEFGHIJKLMNOP"}""")
				else -> MockResponse().json(200, """{"mfaRequired":false,"mustChangePassword":false,"accessToken":"yonetim-access",
					"refreshToken":"yonetim-refresh","accessTokenExpiresAt":"2026-11-15T13:00:00Z",
					"session":{"sessionId":"s9","clientType":"ANDROID","mfaVerified":true,"expiresAt":"2026-11-16T00:45:00Z"}}""")
			}
		}

		assertEquals("ABCDEFGHIJKLMNOP", (repository.enrollMfa("bilet-1") as ApiResult.Success).value.secret)
		repository.verifyMfa("bilet-1", "123456")

		val (enroll, verify) = backend.requests
		assertEquals("""{"mfaTicket":"bilet-1"}""", enroll.body.readUtf8())
		assertEquals("""{"mfaTicket":"bilet-1","code":"123456"}""", verify.body.readUtf8())
		assertNull("Bilet istekleri Bearer taşımaz", verify.getHeader("Authorization"))
		assertEquals("yonetim-access", store.session?.accessToken)
		assertEquals("s9", store.session?.sessionId)
	}

	@Test
	fun wrongCodeKeepsNoSession() = runBlocking {
		backend.handler = {
			MockResponse().json(400, problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"code","code":"INVALID_CODE","message":"Doğrulama kodu hatalı"}]"""))
		}
		val error = (repository.verifyMfa("bilet-1", "000000") as ApiResult.Failure).error
		assertEquals("INVALID_CODE", error.problem?.errors?.single()?.code)
		assertNull(store.session)
	}
}
