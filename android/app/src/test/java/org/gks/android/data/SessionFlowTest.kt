package org.gks.android.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gks.android.GksJson
import org.gks.android.createApis
import org.gks.android.data.session.SessionEndReason
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.SessionStore
import org.gks.android.data.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Oturum sözleşmesi (backend §2.2, ID-02/05/06/11) sahte sunucuya karşı; yanıt gövdeleri gerçek backend biçiminde. */
class SessionFlowTest {

	private val server = MockWebServer()
	private val requests = CopyOnWriteArrayList<RecordedRequest>()
	private val refreshCount = AtomicInteger()
	private lateinit var store: MemoryStore
	private lateinit var sessions: SessionManager
	private lateinit var repository: AuthRepository

	/** Yol + belirteç → yanıt; testler doldurur. */
	private var handler: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

	@Before
	fun setUp() {
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				requests += request
				if (request.path == "/api/v1/auth/refresh") refreshCount.incrementAndGet()
				return handler(request)
			}
		}
		server.start()
		store = MemoryStore(StoredSession("old-access", "old-refresh", SESSION_ID, "2026-11-15T12:00:00Z"))
		sessions = SessionManager(store)
		val (api, _) = createApis(server.url("/").toString(), GksJson, sessions)
		repository = AuthRepository(api, sessions, { "test-installation-0001" }, ApiCaller(GksJson))
	}

	@After
	fun tearDown() = server.shutdown()

	@Test
	fun loginSendsOnlyContractFieldsAndStoresTokens() = runBlocking {
		store.session = null
		handler = { MockResponse().json(200, LOGIN_BODY) }

		val result = repository.login("  gonullu@example.org ", "parola-123")

		assertTrue(result is ApiResult.Success)
		val request = requests.single()
		assertEquals("/api/v1/auth/login", request.path)
		assertNull("Girişte Authorization gönderilmez", request.getHeader("Authorization"))
		assertNull(request.getHeader("X-Gks-No-Auth"))
		assertEquals(
			"""{"email":"gonullu@example.org","password":"parola-123","clientType":"ANDROID","installationId":"test-installation-0001"}""",
			request.body.readUtf8(),
		)
		assertEquals(StoredSession("login-access", "login-refresh", SESSION_ID, "2026-11-15T12:53:33.253580Z"), store.session)
	}

	@Test
	fun invalidCredentialsAreReportedWithoutSession() = runBlocking {
		store.session = null
		handler = { MockResponse().json(401, problem(401, "AUTH_INVALID_CREDENTIALS")) }

		val result = repository.login("gonullu@example.org", "yanlis")

		assertEquals("AUTH_INVALID_CREDENTIALS", ((result as ApiResult.Failure).error as ApiError.Http).problem.code)
		assertNull(store.session)
	}

	@Test
	fun expiredAccessTokenIsRefreshedSavedAndRequestRetried() = runBlocking {
		handler = { request ->
			when {
				request.path == "/api/v1/auth/refresh" -> MockResponse().json(200, TOKEN_BODY)
				request.getHeader("Authorization") == "Bearer new-access" -> MockResponse().json(200, CONTEXT_BODY)
				else -> MockResponse().json(401, problem(401, "AUTH_TOKEN_EXPIRED"))
			}
		}

		val result = repository.loadContext()

		assertEquals("Gokhan D.", (result as ApiResult.Success).value.user.displayName)
		val refresh = requests.single { it.path == "/api/v1/auth/refresh" }
		assertEquals("""{"refreshToken":"old-refresh"}""", refresh.body.readUtf8())
		assertNull("Yenileme isteği Bearer taşımaz", refresh.getHeader("Authorization"))
		assertEquals(StoredSession("new-access", "new-refresh", SESSION_ID, "2026-11-15T13:08:33Z"), store.session)
	}

	@Test
	fun concurrentExpiredRequestsShareOneRefresh() = runBlocking {
		handler = { request ->
			when {
				request.path == "/api/v1/auth/refresh" -> MockResponse().json(200, TOKEN_BODY).setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
				request.getHeader("Authorization") == "Bearer new-access" -> MockResponse().json(200, CONTEXT_BODY)
				else -> MockResponse().json(401, problem(401, "AUTH_TOKEN_EXPIRED"))
			}
		}

		val results = (1..4).map { async(Dispatchers.IO) { repository.loadContext() } }.awaitAll()

		assertTrue(results.all { it is ApiResult.Success })
		assertEquals("Eski yenileme belirteci bir kez gönderilir (tekrar kullanım zinciri iptal eder)", 1, refreshCount.get())
	}

	@Test
	fun rejectedRefreshEndsSession() = runBlocking {
		val ended = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { sessions.ended.first() } }
		handler = { request ->
			if (request.path == "/api/v1/auth/refresh") {
				MockResponse().json(401, problem(401, "AUTH_SESSION_REVOKED"))
			} else {
				MockResponse().json(401, problem(401, "AUTH_TOKEN_EXPIRED"))
			}
		}

		val result = repository.loadContext()

		assertTrue(result is ApiResult.Failure)
		assertNull(store.session)
		assertEquals(SessionEndReason.REVOKED, ended.await())
	}

	@Test
	fun unavailableRefreshKeepsSession() = runBlocking {
		handler = { request ->
			if (request.path == "/api/v1/auth/refresh") {
				MockResponse().json(503, problem(503, "SERVICE_UNAVAILABLE"))
			} else {
				MockResponse().json(401, problem(401, "AUTH_TOKEN_EXPIRED"))
			}
		}

		val result = repository.loadContext()

		assertEquals(ApiError.RefreshUnavailable, (result as ApiResult.Failure).error)
		assertEquals("old-access", store.session?.accessToken)
	}

	@Test
	fun serviceUnavailableIsNotSessionEnd() = runBlocking {
		handler = { MockResponse().json(503, problem(503, "SERVICE_UNAVAILABLE")) }

		val result = repository.loadContext()

		assertEquals(503, ((result as ApiResult.Failure).error as ApiError.Http).problem.status)
		assertEquals(0, refreshCount.get())
		assertEquals("old-access", store.session?.accessToken)
	}

	@Test
	fun revokedSessionEndsWithoutRefresh() = runBlocking {
		val ended = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { sessions.ended.first() } }
		handler = { MockResponse().json(401, problem(401, "AUTH_SESSION_REVOKED")) }

		repository.loadContext()

		assertEquals(0, refreshCount.get())
		assertNull(store.session)
		assertEquals(SessionEndReason.REVOKED, ended.await())
	}

	@Test
	fun disabledAccountEndsSession() = runBlocking {
		val ended = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { sessions.ended.first() } }
		handler = { MockResponse().json(403, problem(403, "ACCOUNT_DISABLED")) }

		repository.loadContext()

		assertNull(store.session)
		assertEquals(SessionEndReason.DISABLED, ended.await())
	}

	@Test
	fun logoutRevokesOnServerAndClearsLocally() = runBlocking {
		handler = { MockResponse().setResponseCode(204) }

		val informed = repository.logout()

		assertTrue(informed)
		val request = requests.single()
		assertEquals("POST", request.method)
		assertEquals("/api/v1/auth/logout", request.path)
		assertEquals("Bearer old-access", request.getHeader("Authorization"))
		assertNull(store.session)
	}

	@Test
	fun logoutWithoutServerStillClearsLocally() = runBlocking {
		server.shutdown()

		val informed = repository.logout()

		assertEquals(false, informed)
		assertNull(store.session)
	}

	private companion object {
		val LOGIN_BODY = """{"mfaRequired":false,"mustChangePassword":false,"accessToken":"login-access",
			"refreshToken":"login-refresh","accessTokenExpiresAt":"2026-11-15T12:53:33.253580Z",
			"session":{"sessionId":"$SESSION_ID","clientType":"ANDROID","mfaVerified":false,"expiresAt":"2026-12-15T12:38:33.253580Z"}}"""

		val TOKEN_BODY = """{"accessToken":"new-access","refreshToken":"new-refresh","sessionId":"$SESSION_ID",
			"accessTokenExpiresAt":"2026-11-15T13:08:33Z"}"""

		// Gerçek backend yanıtından (GET /api/v1/me/context, gönüllü hesabı) alınmıştır.
		val CONTEXT_BODY = """{"user":{"id":"u1","displayName":"Gokhan D.","fullName":"Gokhan Demo"},
			"session":{"sessionId":"$SESSION_ID","clientType":"ANDROID","mfaVerified":false,"expiresAt":"2026-12-15T12:38:33.253580Z","mustChangePassword":false},
			"roles":[{"id":"r1","user":{"id":"u1","displayName":"Gokhan D."},"role":"GON","scopeType":"OPERATION","scopeId":"o1","scopeName":"numan","grantedBy":{"id":"a1","displayName":"Yönetici"},"grantedAt":"2026-11-15T12:31:11.614916Z"}],
			"operations":[{"id":"o1","name":"numan","state":"PLANNED","myRoles":["GON"]}],
			"groups":[{"id":"g1","name":"Arama Kurtarma Ekibi 1","operationId":"o1","role":"MEMBER"}],
			"pendingInvitations":[],
			"permissions":{"organization":["invitation.respond"],"operations":{"o1":["task.respond","location.share","alert.respond"]},"groups":{"g1":["group.view"]}}}"""
	}
}
