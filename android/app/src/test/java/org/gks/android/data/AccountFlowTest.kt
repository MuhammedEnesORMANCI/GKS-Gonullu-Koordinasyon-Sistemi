package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.remote.MeProfile
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.domain.ProfileForm
import org.gks.android.domain.profileChanges
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ID-08 parola değişikliği (LD-58 yeni oturum) ve ID-10 profil güncellemesi. */
class AccountFlowTest {

	private val backend = FakeBackend()
	private val store = MemoryStore(StoredSession("old-access", "old-refresh", SESSION_ID, "2026-11-15T12:00:00Z"))
	private val repository = AuthRepository(backend.api(SessionManager(store)), SessionManager(store), { "test-installation-0001" }, ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun passwordChangeStoresNewSessionBeforeNextRequest() = runBlocking {
		backend.handler = { request ->
			when (request.path) {
				"/api/v1/me/password" -> MockResponse().json(200, """{"accessToken":"new-access","refreshToken":"new-refresh","sessionId":"s2","accessTokenExpiresAt":"2026-11-15T13:00:00Z"}""")
				else -> MockResponse().json(200, PROFILE)
			}
		}

		assertTrue(repository.changePassword("eski-parola-123", "yeni-parola-456") is ApiResult.Success)
		repository.profile()

		val (change, next) = backend.requests
		assertEquals("Bearer old-access", change.getHeader("Authorization"))
		assertEquals("""{"currentPassword":"eski-parola-123","newPassword":"yeni-parola-456"}""", change.body.readUtf8())
		assertEquals(StoredSession("new-access", "new-refresh", "s2", "2026-11-15T13:00:00Z"), store.session)
		assertEquals("Eski oturum iptal edildi; sonraki istek yeni belirteçle gider", "Bearer new-access", next.getHeader("Authorization"))
	}

	@Test
	fun wrongCurrentPasswordKeepsSessionAndReturnsFieldError() = runBlocking {
		backend.handler = {
			MockResponse().json(400, problem(400, "VALIDATION_FAILED", ""","errors":[{"field":"currentPassword","code":"INVALID","message":"Mevcut parola hatalı"}]"""))
		}

		val error = (repository.changePassword("yanlis", "yeni-parola-456") as ApiResult.Failure).error

		assertEquals("Mevcut parola hatalı", error.problem?.errors?.single()?.message)
		assertEquals("old-access", store.session?.accessToken)
	}

	@Test
	fun profileUpdateSendsOnlyChangesAndExplicitNullForPhone() = runBlocking {
		backend.handler = { MockResponse().json(200, PROFILE) }
		val profile = GksJson.decodeFromString(MeProfile.serializer(), PROFILE)

		val changes = profileChanges(profile, ProfileForm(" Ayşe Kaya ", "Ayşe Y.", ""))
		repository.updateProfile(changes)

		val request = backend.requests.single()
		assertEquals("PATCH", request.method)
		assertEquals("/api/v1/me", request.path)
		assertEquals("""{"displayName":"Ayşe Y.","phone":null}""", request.body.readUtf8())
	}

	@Test
	fun unchangedFormProducesNoChanges() {
		val profile = GksJson.decodeFromString(MeProfile.serializer(), PROFILE)
		assertTrue(profileChanges(profile, ProfileForm.of(profile)).isEmpty())
		assertEquals(mapOf("phone" to "+905550000000"), profileChanges(profile, ProfileForm.of(profile).copy(phone = " +905550000000 ")))
	}

	private companion object {
		const val PROFILE = """{"id":"u1","email":"ayse@example.org","fullName":"Ayşe Kaya","displayName":"Ayşe K.","status":"ACTIVE",
			"phone":"+905551112233","mfaEnrolled":false,"createdAt":"2026-11-15T12:00:00Z"}"""
	}
}
