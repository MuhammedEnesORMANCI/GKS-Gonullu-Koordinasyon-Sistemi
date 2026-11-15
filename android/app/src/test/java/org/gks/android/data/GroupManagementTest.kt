package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.remote.ContextSession
import org.gks.android.data.remote.ContextUser
import org.gks.android.data.remote.EffectivePermissions
import org.gks.android.data.remote.MeContext
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.domain.managementAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grup yönetimi (GR-01 – GR-07) ve grup izni görünürlüğü. */
class GroupManagementTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("yonetim", "refresh", SESSION_ID, "2026-11-15T12:00:00Z")))
	private val repository = ManagementRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	private val group = """{"id":"g1","name":"Arama ekibi","operationId":"o1","members":[]}"""

	@Test
	fun createGroupExcludesLeaderFromMembers() = runBlocking {
		backend.handler = { MockResponse().json(201, group) }

		repository.createGroup("o1", " Arama ekibi ", "", "u1", listOf("u1", "u2"))

		val request = backend.requests.single()
		assertEquals("/api/v1/operations/o1/groups", request.path)
		assertEquals("""{"name":"Arama ekibi","leaderUserId":"u1","memberUserIds":["u2"]}""", request.body.readUtf8())
		assertEquals("Bearer yonetim", request.getHeader("Authorization"))
	}

	@Test
	fun membershipLeaderRenameAndListCalls() = runBlocking {
		backend.handler = { request ->
			when {
				request.method == "DELETE" -> MockResponse().setResponseCode(204)
				request.path!!.startsWith("/api/v1/groups?") -> MockResponse().json(200,
					"""{"items":[{"id":"g1","name":"Arama ekibi","operationId":"o1","memberCount":3}]}""")
				request.path!!.contains("/participants") -> MockResponse().json(200, """{"items":[]}""")
				request.path!!.endsWith("/members") -> MockResponse().json(201, """{"user":{"id":"u3","displayName":"Ali"},"role":"MEMBER"}""")
				else -> MockResponse().json(200, group)
			}
		}

		assertEquals(3, (repository.groups("o1") as ApiResult.Success).value.single().memberCount)
		repository.addGroupMember("g1", "u3")
		repository.removeGroupMember("g1", "u3")
		repository.changeGroupLeader("g1", "u2")
		repository.renameGroup("g1", "Yeni ad", "  ")
		repository.participants("o1", notInGroup = "g1")

		val (list, add, remove, leader, rename, candidates) = backend.requests
		assertEquals("/api/v1/groups?limit=100&operationId=o1", list.path)
		assertEquals("/api/v1/groups/g1/members", add.path)
		assertEquals("""{"userId":"u3"}""", add.body.readUtf8())
		assertEquals("/api/v1/groups/g1/members/u3", remove.path)
		assertEquals("PUT", leader.method)
		assertEquals("/api/v1/groups/g1/leader", leader.path)
		assertEquals("""{"userId":"u2"}""", leader.body.readUtf8())
		assertEquals("PATCH", rename.method)
		assertEquals("""{"name":"Yeni ad","description":null}""", rename.body.readUtf8())
		assertEquals("/api/v1/operations/o1/participants?limit=100&notInGroup=g1", candidates.path)
	}

	@Test
	fun groupManagementOnlyForCoordinatorOfOperation() {
		fun context(organization: List<String>, operations: Map<String, List<String>>) = MeContext(
			user = ContextUser("u", "Kişi"),
			session = ContextSession("s", "ANDROID", true, "2026-11-16T00:00:00Z"),
			permissions = EffectivePermissions(organization, operations),
		)
		val kor = context(emptyList(), mapOf("o1" to listOf("operation.manage", "group.create.operational", "membership.add"))).managementAccess()!!
		assertTrue(kor.canManageGroups("o1"))
		assertFalse(kor.canManageGroups("o2"))
		// ADM (KOR değilse) grupları salt-okur görür: GR-01/04/05/06 yalnızca K1 KOR.
		val adm = context(listOf("operation.create", "operation.manage", "role.assign", "group.view"), emptyMap()).managementAccess()!!
		assertTrue(adm.canManage("o1"))
		assertFalse(adm.canManageGroups("o1"))
	}

	private operator fun <T> List<T>.component6(): T = this[5]
}
