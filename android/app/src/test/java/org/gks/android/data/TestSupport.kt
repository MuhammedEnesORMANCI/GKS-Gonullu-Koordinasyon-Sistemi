package org.gks.android.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.gks.android.GksJson
import org.gks.android.createApis
import org.gks.android.data.remote.GksApi
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.SessionStore
import org.gks.android.data.session.StoredSession
import java.util.concurrent.CopyOnWriteArrayList

class MemoryStore(var session: StoredSession? = null) : SessionStore {
	override fun read() = session
	override fun save(session: StoredSession) { this.session = session }
	override fun clear() { session = null }
}

/** Yanıtları test içinden verilen sahte backend; gelen istekler kaydedilir. */
class FakeBackend {
	val server = MockWebServer()
	val requests = CopyOnWriteArrayList<RecordedRequest>()
	var handler: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

	init {
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				requests += request
				return handler(request)
			}
		}
		server.start()
	}

	fun api(sessions: SessionManager): GksApi = createApis(server.url("/").toString(), GksJson, sessions).first

	fun shutdown() = server.shutdown()
}

fun MockResponse.json(code: Int, body: String): MockResponse =
	setResponseCode(code).setHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").setBody(body)

fun problem(status: Int, code: String, extra: String = "") =
	"""{"type":"/problems/x","title":"x","status":$status,"code":"$code","traceId":"t"$extra}"""

const val SESSION_ID = "01a1df37-6ba9-7ffd-90df-33fc78d5e238"
