package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.data.remote.ContextSession
import org.gks.android.data.remote.ContextUser
import org.gks.android.data.remote.EffectivePermissions
import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.TaskTargets
import org.gks.android.data.remote.TaskView
import org.gks.android.data.remote.TimeWindow
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.domain.managementAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KOR görev yönetimi (TK-01 – TK-09) ve görev izni görünürlüğü. */
class TaskManagementTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("yonetim", "refresh", SESSION_ID, "2026-11-15T12:00:00Z")))
	private val repository = TaskManagementRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	private val taskJson = """{"id":"t1","title":"Su dağıtımı","priority":"NORMAL","state":"DRAFT","version":3}"""

	private val draftTask = TaskView(id = "t1", title = "Su dağıtımı", priority = "NORMAL", state = "DRAFT", description = "Eski",
		capacity = 5, responseWindowMinutes = 60, timeWindow = TimeWindow("2026-12-01T08:00:00Z"), version = 3,
		targets = TaskTargets(userIds = listOf("u1"), groupIds = emptyList()))

	private fun draft(
		description: String = "Eski",
		capacity: Int? = 5,
		start: String? = "2026-12-01T08:00:00Z",
		userIds: List<String> = listOf("u1"),
		groupIds: List<String> = emptyList(),
	) = TaskDraft("Su dağıtımı", description, "NORMAL", "", start, null, capacity, 60, userIds, groupIds)

	@Test
	fun createSendsContractBodyWithKey() = runBlocking {
		backend.handler = { MockResponse().json(201, taskJson) }
		repository.create("o1", TaskDraft(" Su dağıtımı ", "", "HIGH", "", null, null, null, null, listOf("u1"), listOf("g1")))

		val request = backend.requests.single()
		assertEquals("/api/v1/operations/o1/tasks", request.path)
		assertEquals("""{"title":"Su dağıtımı","priority":"HIGH","targets":{"userIds":["u1"],"groupIds":["g1"]}}""", request.body.readUtf8())
		assertNotNull(request.getHeader("Idempotency-Key"))
	}

	@Test
	fun updateSendsOnlyChangesWithVersionAndNullToClear() = runBlocking {
		backend.handler = { MockResponse().json(200, taskJson) }

		repository.update(draftTask, draft(description = "", capacity = null, start = null, groupIds = listOf("g1")))
		val unchanged = repository.update(draftTask, draft())

		val request = backend.requests.single()
		assertEquals("PATCH", request.method)
		assertEquals("/api/v1/tasks/t1", request.path)
		assertEquals(
			"""{"description":null,"timeWindow":null,"capacity":null,"targets":{"userIds":["u1"],"groupIds":["g1"]},"version":3}""",
			request.body.readUtf8(),
		)
		assertEquals("Değişiklik yoksa istek gitmez", draftTask, (unchanged as ApiResult.Success).value)
	}

	@Test
	fun targetsAreNotSentAfterPublish() = runBlocking {
		backend.handler = { MockResponse().json(200, taskJson) }
		repository.update(draftTask.copy(state = "PUBLISHED"), draft(description = "Yeni", groupIds = listOf("g1")))
		assertEquals("""{"description":"Yeni","version":3}""", backend.requests.single().body.readUtf8())
	}

	@Test
	fun publishUsesRequiredKeyAndRetriesWithSameKey() = runBlocking {
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		repository.publish("t1", 3)
		backend.handler = { MockResponse().json(200, taskJson.replace("DRAFT", "PUBLISHED")) }
		repository.publish("t1", 3)

		val (first, retry) = backend.requests
		assertEquals("/api/v1/tasks/t1/publish", first.path)
		assertEquals("""{"version":3}""", first.body.readUtf8())
		assertNotNull("TK-03 Idempotency-Key zorunlu", first.getHeader("Idempotency-Key"))
		assertEquals(first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
	}

	@Test
	fun stateAndAssignmentCalls() = runBlocking {
		backend.handler = { request ->
			when {
				request.method == "DELETE" -> MockResponse().setResponseCode(204)
				request.path!!.startsWith("/api/v1/tasks/t1/assignments?") -> MockResponse().json(200,
					"""{"items":[{"id":"a1","taskId":"t1","user":{"id":"u1","displayName":"Ali"},"status":"PENDING","respondBy":"2026-12-01T09:00:00Z","overdue":true}]}""")
				request.path!!.endsWith("/assignments") -> MockResponse().json(201,
					"""{"id":"a2","taskId":"t1","user":{"id":"u2","displayName":"Ayşe"},"status":"PENDING","overdue":false}""")
				else -> MockResponse().json(200, taskJson.replace("DRAFT", "IN_PROGRESS"))
			}
		}

		repository.changeState("t1", "IN_PROGRESS")
		val list = (repository.assignments("t1") as ApiResult.Success).value
		repository.addAssignment("t1", "u2")
		repository.removeAssignment("t1", "a1")

		val (state, listCall, add, remove) = backend.requests
		assertEquals("/api/v1/tasks/t1/state", state.path)
		assertEquals("""{"targetState":"IN_PROGRESS"}""", state.body.readUtf8())
		assertTrue(list.single().overdue)
		assertEquals("/api/v1/tasks/t1/assignments?limit=100", listCall.path)
		assertEquals("""{"userId":"u2"}""", add.body.readUtf8())
		assertNotNull(add.getHeader("Idempotency-Key"))
		assertEquals("/api/v1/tasks/t1/assignments/a1", remove.path)
	}

	@Test
	fun transitionsAndVisibility() {
		assertEquals(listOf("CANCELLED"), nextTaskStates("DRAFT"))
		assertEquals(listOf("IN_PROGRESS", "CANCELLED"), nextTaskStates("PUBLISHED"))
		assertEquals(listOf("DONE", "CANCELLED"), nextTaskStates("IN_PROGRESS"))
		assertTrue(nextTaskStates("DONE").isEmpty())

		fun access(organization: List<String>, operations: Map<String, List<String>>) = MeContext(
			user = ContextUser("u", "Kişi"),
			session = ContextSession("s", "ANDROID", true, "2026-11-16T00:00:00Z"),
			permissions = EffectivePermissions(organization, operations),
		).managementAccess()!!
		assertTrue(access(emptyList(), mapOf("o1" to listOf("operation.manage", "task.manage"))).canManageTasks("o1"))
		// Sunucu operasyon izni göndermezse salt-okur; ADR-015 ile ADM'ye her operasyonda task.manage gelir.
		assertFalse(access(listOf("operation.create", "operation.manage"), emptyMap()).canManageTasks("o1"))
		assertTrue(access(listOf("operation.create", "operation.manage"), mapOf("o1" to listOf("task.manage"))).canManageTasks("o1"))
	}
}
