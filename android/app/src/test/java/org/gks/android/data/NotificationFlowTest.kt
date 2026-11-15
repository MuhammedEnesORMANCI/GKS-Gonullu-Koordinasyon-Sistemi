package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NT-04/05/07/08 (LD-60): gövdeler backend NotificationApiTests'teki biçimde. */
class NotificationFlowTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-15T12:00:00Z")))
	private val repository = NotificationRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	@Test
	fun pagesFollowCursorAndParseLinks() = runBlocking {
		backend.handler = { MockResponse().json(200, """{"items":[$ITEM],"nextCursor":"c2"}""") }

		val first = (repository.page(null) as ApiResult.Success).value
		repository.page(first.nextCursor)

		assertEquals("/api/v1/me/notifications?limit=30", backend.requests[0].path)
		assertEquals("/api/v1/me/notifications?cursor=c2&limit=30", backend.requests[1].path)
		val item = first.items.single()
		assertEquals("TASK", item.link?.type)
		assertEquals("t1", item.link?.id)
		assertNull(item.readAt)
		assertEquals("Bearer access", backend.requests[0].getHeader("Authorization"))
	}

	@Test
	fun markReadPostsAndUnreadCountParses() = runBlocking {
		backend.handler = { request ->
			if (request.path!!.endsWith("/unread-count")) MockResponse().json(200, """{"unreadCount":3}""")
			else MockResponse().setResponseCode(204)
		}

		assertTrue(repository.markRead("d1") is ApiResult.Success)
		assertEquals(3, (repository.unreadCount() as ApiResult.Success).value)

		assertEquals("POST", backend.requests[0].method)
		assertEquals("/api/v1/me/notifications/d1/read", backend.requests[0].path)
		assertEquals("/api/v1/me/notifications/unread-count", backend.requests[1].path)
	}

	@Test
	fun othersNotificationIsNotFound() = runBlocking {
		backend.handler = { MockResponse().json(404, problem(404, "NOT_FOUND")) }
		assertEquals("NOT_FOUND", (repository.item("baskasinin") as ApiResult.Failure).error.problem?.code)
	}

	private companion object {
		const val ITEM = """{"id":"d1","category":"TASK","kind":"TASK_ASSIGNED","priority":"HIGH","title":"Yeni görev: Su dağıtımı",
			"body":"Deprem müdahalesi operasyonunda size görev atandı.","createdAt":"2026-11-15T14:00:00Z",
			"link":{"type":"TASK","id":"t1"},"operationId":"o1"}"""
	}
}
