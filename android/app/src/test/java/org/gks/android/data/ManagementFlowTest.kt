package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.data.remote.ContextSession
import org.gks.android.data.remote.ContextUser
import org.gks.android.data.remote.EffectivePermissions
import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.OperationView
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.domain.managementAccess
import org.gks.android.domain.nextOperationStates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Operasyon yönetimi (OR-01 – OR-06, AC-01 – AC-03) ve Yönetim sekmesi görünürlüğü (ADR-014). */
class ManagementFlowTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("yonetim", "refresh", SESSION_ID, "2026-11-15T12:00:00Z")))
	private val repository = ManagementRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	private val operation = OperationView(id = "o1", name = "Sel", description = "Eski", state = "PLANNED",
		plannedEndAt = "2026-12-01T10:00:00Z")

	@Test
	fun createSendsContractBodyWithIdempotencyKeyAndRetriesWithSameKey() = runBlocking {
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		val draft = OperationDraft("  Deprem tatbikatı ", "", null)
		assertEquals(ApiError.Network, (repository.create(draft, listOf("u1")) as ApiResult.Failure).error)
		backend.handler = { MockResponse().json(201, """{"id":"o9","name":"Deprem tatbikatı","state":"PLANNED","coordinators":[]}""") }
		repository.create(draft, listOf("u1"))

		val (first, retry) = backend.requests
		assertEquals("/api/v1/operations", first.path)
		assertEquals("""{"name":"Deprem tatbikatı","coordinatorIds":["u1"]}""", first.body.readUtf8())
		assertEquals("Bearer yonetim", first.getHeader("Authorization"))
		assertNotNull(first.getHeader("Idempotency-Key"))
		assertEquals("Ağ hatasından sonra ikinci operasyon oluşmaz", first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
	}

	@Test
	fun updateSendsOnlyChangesAndNullToClear() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"id":"o1","name":"Sel 2","state":"PLANNED"}""") }

		repository.update(operation, OperationDraft("Sel 2", "  ", null))
		val unchanged = repository.update(operation, OperationDraft("Sel", "Eski", "2026-12-01T10:00:00Z"))

		val request = backend.requests.single()
		assertEquals("PATCH", request.method)
		assertEquals("/api/v1/operations/o1", request.path)
		assertEquals("""{"name":"Sel 2","description":null,"plannedEndAt":null}""", request.body.readUtf8())
		assertEquals("Değişiklik yoksa istek gitmez", operation, (unchanged as ApiResult.Success).value)
	}

	@Test
	fun stateChangeAndCoordinatorAssignments() = runBlocking {
		backend.handler = { request ->
			when {
				request.path!!.startsWith("/api/v1/role-assignments?") -> MockResponse().json(200,
					"""{"items":[{"id":"r1","role":"KOR","scopeType":"OPERATION","scopeId":"o1","user":{"id":"u1","displayName":"Ayşe"}}]}""")
				request.method == "DELETE" -> MockResponse().setResponseCode(204)
				request.path == "/api/v1/role-assignments" -> MockResponse().json(201,
					"""{"id":"r2","role":"KOR","scopeType":"OPERATION","scopeId":"o1","user":{"id":"u2","displayName":"Ali"}}""")
				else -> MockResponse().json(200, """{"id":"o1","name":"Sel","state":"ACTIVE"}""")
			}
		}

		repository.changeState("o1", "ACTIVE")
		assertEquals("Ayşe", (repository.coordinators("o1") as ApiResult.Success).value.single().user?.displayName)
		repository.grantCoordinator("o1", "u2")
		repository.revoke("r1")

		val (state, list, grant, revoke) = backend.requests
		assertEquals("/api/v1/operations/o1/state", state.path)
		assertEquals("""{"targetState":"ACTIVE"}""", state.body.readUtf8())
		assertEquals("/api/v1/role-assignments?scopeId=o1&role=KOR&active=true&limit=100", list.path)
		assertEquals("""{"userId":"u2","role":"KOR","scopeType":"OPERATION","scopeId":"o1"}""", grant.body.readUtf8())
		assertEquals("/api/v1/role-assignments/r1", revoke.path)
	}

	@Test
	fun candidatesMergeParticipantsAndCoordinatorsWithoutDuplicates() = runBlocking {
		backend.handler = { request ->
			if (request.path!!.contains("/participants")) {
				MockResponse().json(200, """{"items":[{"user":{"id":"u1","displayName":"Zeynep"},"openAssignmentCount":0}]}""")
			} else {
				MockResponse().json(200, """{"items":[{"id":"r1","role":"KOR","scopeType":"OPERATION","scopeId":"o2","user":{"id":"u1","displayName":"Zeynep"}},
					{"id":"r2","role":"KOR","scopeType":"OPERATION","scopeId":"o3","user":{"id":"u3","displayName":"Ahmet"}}]}""")
			}
		}
		val candidates = (repository.coordinatorCandidates("o1") as ApiResult.Success).value
		assertEquals(listOf("Ahmet", "Zeynep"), candidates.map { it.displayName })
		assertEquals(ManagementRepository.Candidate.Source.PARTICIPANT, candidates.single { it.id == "u1" }.source)
	}

	// ---------------------------------------------------------------- görünürlük (MeContext.permissions)

	private fun context(mfa: Boolean, organization: List<String> = emptyList(), operations: Map<String, List<String>> = emptyMap()) =
		MeContext(
			user = ContextUser("u", "Kişi"),
			session = ContextSession("s", "ANDROID", mfa, "2026-11-16T00:00:00Z"),
			permissions = EffectivePermissions(organization, operations),
		)

	@Test
	fun managementTabOnlyInManagementSessionWithPermissions() {
		assertNull("gönüllü oturumu", context(mfa = false, operations = mapOf("o1" to listOf("task.respond"))).managementAccess())
		assertNull("MFA'lı ama yönetim izni yok", context(mfa = true, operations = mapOf("o1" to listOf("task.respond"))).managementAccess())

		val kor = context(mfa = true, operations = mapOf("o1" to listOf("operation.manage", "task.manage")))
			.managementAccess()!!
		assertTrue(kor.canManage("o1"))
		assertFalse(kor.canManage("o2"))
		assertFalse(kor.canCreateOperation)
		assertFalse(kor.canAssignRoles)

		val adm = context(mfa = true, organization = listOf("operation.create", "operation.manage", "role.assign")).managementAccess()!!
		assertTrue(adm.canManage("herhangi"))
		assertTrue(adm.canCreateOperation && adm.canAssignRoles)
	}

	@Test
	fun coreTransitionsExcludeSuspendAndClosedStates() {
		assertEquals(listOf("ACTIVE", "CANCELLED"), nextOperationStates("PLANNED"))
		assertEquals(listOf("COMPLETED", "CANCELLED"), nextOperationStates("ACTIVE"))
		assertTrue(nextOperationStates("COMPLETED").isEmpty())
		assertTrue(nextOperationStates("CANCELLED").isEmpty())
	}
}
