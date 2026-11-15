package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.gks.android.GksJson
import org.gks.android.R
import org.gks.android.data.remote.ContextSession
import org.gks.android.data.remote.ContextUser
import org.gks.android.data.remote.EffectivePermissions
import org.gks.android.data.remote.MeContext
import org.gks.android.data.remote.Participant
import org.gks.android.data.remote.ParticipantUser
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.gks.android.domain.AnnouncementFieldError
import org.gks.android.domain.AudienceKind
import org.gks.android.domain.audienceOf
import org.gks.android.domain.filterPeople
import org.gks.android.domain.managementAccess
import org.gks.android.domain.validateAnnouncement
import org.gks.android.ui.common.UiText
import org.gks.android.ui.management.announcementError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADM/KOR duyuru yönetimi (NT-01 – NT-03, NT-06): istek gövdeleri, zorunlu anahtar, hata eşleme, görünürlük. */
class AnnouncementManagementTest {

	private val backend = FakeBackend()
	private val sessions = SessionManager(MemoryStore(StoredSession("yonetim", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
	private val repository = AnnouncementRepository(backend.api(sessions), ApiCaller(GksJson))

	@After
	fun tearDown() = backend.shutdown()

	private val viewJson = """{"id":"a1","title":"Toplantı","operationId":"o1","recipientCount":2}"""
	private val previewJson = """{"recipientCount":2,"recipients":[{"id":"u1","displayName":"Ali"},{"id":"u2","displayName":"Ayşe"}],"confirmationRequired":false}"""

	@Test
	fun previewBodiesForAllTargetKinds() = runBlocking {
		backend.handler = { MockResponse().json(200, previewJson) }
		val preview = repository.preview(audienceOf("o1", AudienceKind.WHOLE_OPERATION, emptySet(), emptySet()))
		repository.preview(audienceOf("o1", AudienceKind.GROUPS, setOf("g2", "g1"), setOf("u9")))
		repository.preview(audienceOf("o1", AudienceKind.PEOPLE, setOf("g9"), setOf("u2", "u1")))
		repository.preview(audienceOf(null, AudienceKind.PEOPLE, emptySet(), setOf("u1")))

		val bodies = backend.requests.map { it.path to it.body.readUtf8() }
		assertEquals("/api/v1/announcements/preview", bodies[0].first)
		assertEquals("""{"operationId":"o1"}""", bodies[0].second)
		assertEquals("""{"operationId":"o1","groupIds":["g1","g2"]}""", bodies[1].second)   // yalnızca seçili türün listesi
		assertEquals("""{"operationId":"o1","userIds":["u1","u2"]}""", bodies[2].second)
		assertEquals("""{"organization":true}""", bodies[3].second)
		assertEquals(2, (preview as ApiResult.Success).value.recipientCount)
	}

	@Test
	fun sendBodiesForAllTargetKindsWithRequiredKey() = runBlocking {
		backend.handler = { MockResponse().json(202, viewJson) }
		repository.send(AnnouncementAudience.WholeOperation("o1"), "  Toplantı ", " Saat 18:00 ", confirm = false)
		repository.send(AnnouncementAudience.Groups("o1", setOf("g1")), "Toplantı", "Metin", confirm = false)
		repository.send(AnnouncementAudience.People("o1", setOf("u1")), "Toplantı", "Metin", confirm = true)
		repository.send(AnnouncementAudience.Organization, "Kurum", "Metin", confirm = false)

		val requests = backend.requests
		assertTrue(requests.all { it.path == "/api/v1/announcements" && it.method == "POST" })
		assertEquals("""{"operationId":"o1","title":"Toplantı","body":"Saat 18:00"}""", requests[0].body.readUtf8())
		assertEquals("""{"operationId":"o1","groupIds":["g1"],"title":"Toplantı","body":"Metin"}""", requests[1].body.readUtf8())
		assertEquals("""{"operationId":"o1","userIds":["u1"],"title":"Toplantı","body":"Metin","confirm":true}""", requests[2].body.readUtf8())
		assertEquals("""{"organization":true,"title":"Kurum","body":"Metin"}""", requests[3].body.readUtf8())
		requests.forEach { assertNotNull("NT-02 Idempotency-Key zorunlu", it.getHeader("Idempotency-Key")) }
		assertEquals("farklı duyurular farklı anahtar", 4, requests.map { it.getHeader("Idempotency-Key") }.toSet().size)
	}

	@Test
	fun networkFailureRetriesWithSameKeyThenReleasesIt() = runBlocking {
		val audience = AnnouncementAudience.WholeOperation("o1")
		backend.handler = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
		val failed = repository.send(audience, "Toplantı", "Metin", confirm = false)
		assertEquals(ApiError.Network, (failed as ApiResult.Failure).error)
		backend.handler = { MockResponse().json(202, viewJson) }
		repository.send(audience, "Toplantı", "Metin", confirm = false)
		repository.send(audience, "Toplantı", "Metin", confirm = false)   // bilinçli ikinci gönderim

		val (first, retry, again) = backend.requests
		assertEquals("ağ hatasında aynı anahtar", first.getHeader("Idempotency-Key"), retry.getHeader("Idempotency-Key"))
		assertNotEquals("başarıdan sonra yeni anahtar", retry.getHeader("Idempotency-Key"), again.getHeader("Idempotency-Key"))
	}

	@Test
	fun historyLoadsAllPagesAndDetailHasBody() = runBlocking {
		backend.handler = { request ->
			when {
				request.path!!.startsWith("/api/v1/announcements/a2") ->
					MockResponse().json(200, """{"id":"a2","title":"İkinci","body":"Metin","recipientCount":1}""")
				request.path!!.contains("cursor=c1") -> MockResponse().json(200, """{"items":[{"id":"a2","title":"İkinci"}]}""")
				else -> MockResponse().json(200, """{"items":[$viewJson],"nextCursor":"c1"}""")
			}
		}
		val history = repository.history("o1")
		val detail = repository.announcement("a2")

		assertEquals(listOf("a1", "a2"), (history as ApiResult.Success).value.map { it.id })
		assertEquals("/api/v1/announcements?operationId=o1&limit=100", backend.requests[0].path)
		assertEquals("Metin", (detail as ApiResult.Success).value.body)
	}

	@Test
	fun errorCodesMapToTurkishMessages() = runBlocking {
		fun mapped(status: Int, body: String): UiText {
			backend.handler = { MockResponse().json(status, body) }
			val result = runBlocking { repository.send(AnnouncementAudience.WholeOperation("o1"), "Toplantı", "Metin", confirm = false) }
			return announcementError((result as ApiResult.Failure).error)
		}
		assertEquals(UiText.Res(R.string.announcement_error_not_active), mapped(409, problem(409, "OPERATION_NOT_ACTIVE")))
		assertEquals(UiText.Res(R.string.announcement_error_forbidden), mapped(403, problem(403, "FORBIDDEN")))
		assertEquals(UiText.Res(R.string.management_session_required), mapped(403, problem(403, "CLIENT_NOT_ALLOWED")))
		assertEquals(UiText.Res(R.string.announcement_error_confirmation), mapped(422, problem(422, "VALIDATION_FAILED",
			""","errors":[{"field":"confirm","code":"CONFIRMATION_REQUIRED","message":"onay"}]""")))
		assertEquals(UiText.Res(R.string.announcement_error_empty), mapped(422, problem(422, "VALIDATION_FAILED",
			""","errors":[{"field":"targets","code":"EMPTY_AUDIENCE","message":"boş"}]""")))
		assertEquals("400 alan hatası sunucunun Türkçe iletisiyle", UiText.Raw("Başlık zorunludur"), mapped(400, problem(400, "VALIDATION_FAILED",
			""","errors":[{"field":"title","code":"REQUIRED","message":"Başlık zorunludur"}]""")))
	}

	@Test
	fun visibilityComesFromPermissions() {
		fun access(organization: List<String>, operations: Map<String, List<String>>) = MeContext(
			user = ContextUser("u", "Kişi"),
			session = ContextSession("s", "ANDROID", true, "2026-11-16T00:00:00Z"),
			permissions = EffectivePermissions(organization, operations),
		).managementAccess()!!
		val kor = access(emptyList(), mapOf("o1" to listOf("operation.manage", "announcement.send.group")))
		assertTrue(kor.canAnnounce("o1"))
		assertFalse(kor.canAnnounce("o2"))
		assertFalse(kor.canAnnounceOrganization)
		// ADR-015: sunucu ADM'ye her operasyon için announcement.send.group gönderir.
		val adm = access(listOf("operation.create", "operation.manage", "announcement.send.organization"),
			mapOf("o1" to listOf("announcement.send.group"), "o2" to listOf("announcement.send.group")))
		assertTrue(adm.canAnnounce("o2"))
		assertTrue(adm.canAnnounceOrganization)
		val readOnly = access(listOf("operation.manage"), emptyMap())
		assertFalse(readOnly.canAnnounce("o1"))
	}

	@Test
	fun selectionSearchAndValidationRules() {
		assertTrue(audienceOf("o1", AudienceKind.GROUPS, emptySet(), setOf("u1")).isEmpty)
		assertTrue(audienceOf("o1", AudienceKind.PEOPLE, setOf("g1"), emptySet()).isEmpty)
		assertFalse(audienceOf("o1", AudienceKind.WHOLE_OPERATION, emptySet(), emptySet()).isEmpty)
		assertFalse(audienceOf(null, AudienceKind.GROUPS, emptySet(), emptySet()).isEmpty)

		fun person(id: String, name: String, full: String? = null) = Participant(ParticipantUser(id, name, fullName = full))
		val people = listOf(person("1", "Zeynep A."), person("2", "İsmail K.", "İsmail Kaya"), person("3", "Ali", "Ali Işık"))
		assertEquals(listOf("3", "2", "1"), filterPeople(people, "").map { it.user.id })
		assertEquals(listOf("2"), filterPeople(people, "ismail").map { it.user.id })   // Türkçe İ/i
		assertEquals(listOf("3"), filterPeople(people, "IŞIK").map { it.user.id })   // ad soyadda arama
		assertTrue(filterPeople(people, "yok").isEmpty())

		assertEquals(mapOf("title" to AnnouncementFieldError.TITLE_TOO_SHORT, "body" to AnnouncementFieldError.BODY_REQUIRED),
			validateAnnouncement(" ab ", "   "))
		assertEquals(mapOf("title" to AnnouncementFieldError.TITLE_TOO_LONG, "body" to AnnouncementFieldError.BODY_TOO_LONG),
			validateAnnouncement("x".repeat(121), "y".repeat(2001)))
		assertTrue(validateAnnouncement("Toplantı", "Metin").isEmpty())
	}
}
