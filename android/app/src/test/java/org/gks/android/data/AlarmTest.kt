package org.gks.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.alarm.AlarmPhase
import org.gks.android.alarm.AlarmPolicy
import org.gks.android.alarm.DeviceRegistrar
import org.gks.android.alarm.Incoming
import org.gks.android.alarm.RecentIds
import org.gks.android.data.remote.InboxItem
import org.gks.android.data.remote.InboxLink
import org.gks.android.data.session.SessionManager
import org.gks.android.data.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-022: FCM yükü, tekilleştirme, alarm ses kuralları ve cihaz kaydı. */
class AlarmTest {

	private val policy = AlarmPolicy(insistentMillis = 10 * 60_000L, reminderMillis = 3 * 60_000L)

	@Test
	fun fcmDataAndInboxItemMapToSameEventKey() {
		val data = mapOf("type" to "ALERT", "kind" to "ALERT_SENT", "notificationId" to "n1", "inboxItemId" to "d1",
			"sourceType" to "ALERT", "sourceId" to "a1", "title" to "ACİL ALARM: Sel", "body" to "Tahliye", "priority" to "HIGH")
		val fromFcm = Incoming.fromData(data)!!
		val inbox = InboxItem("d1", "ALERT", "ALERT_SENT", "CRITICAL", "ACİL ALARM: Sel", "Tahliye", "2026-11-16T09:00:00Z",
			link = InboxLink("ALERT", "a1"), notificationId = "n1")
		val fromSse = Incoming.fromInbox(inbox)!!
		assertEquals(fromFcm, fromSse)
		assertTrue(fromFcm.isAlert)
		assertNull("notificationId olmayan yük yok sayılır", Incoming.fromData(data - "notificationId"))
		val announcement = Incoming.fromData(mapOf("type" to "ANNOUNCEMENT", "notificationId" to "n2", "title" to "T"))!!
		assertFalse(announcement.isAlert)
	}

	@Test
	fun recentIdsDeduplicateAndSurviveSerialization() {
		val ids = RecentIds(capacity = 3)
		assertTrue(ids.add("a"))
		assertFalse("ikinci yol (SSE/FCM) aynı olayı yeniden göstermez", ids.add("a"))
		val restored = RecentIds(capacity = 3, serialized = ids.serialize())
		assertFalse(restored.add("a"))
		listOf("b", "c", "d").forEach { restored.add(it) }
		assertFalse("en eskisi düşer", "a" in restored)
	}

	@Test
	fun alarmRingsInsistentlyThenRemindsAndStopsOnResponseMuteOrResolve() {
		val start = 1_000L
		val ringing = policy.onSent(null, "a1", "ACİL ALARM", "Tahliye", start)
		assertEquals(AlarmPhase.RINGING, ringing.phase)
		assertTrue(policy.insistent(ringing))
		assertEquals(start + 10 * 60_000L, policy.nextTick(ringing, start))
		assertEquals("ısrarlı süre dolmadan değişmez", ringing, policy.onTick(ringing, start + 60_000L))
		val reminding = policy.onTick(ringing, start + 10 * 60_000L)
		assertEquals(AlarmPhase.REMINDING, reminding.phase)
		assertFalse(policy.insistent(reminding))
		assertEquals(start + 13 * 60_000L, policy.nextTick(reminding, start + 10 * 60_000L))

		val responded = policy.onResponded(reminding, "NEED_HELP")
		assertEquals(AlarmPhase.RESPONDED, responded.phase)
		assertNull("yanıttan sonra ses yok", policy.nextTick(responded, start))
		assertTrue("çözülmedikçe kaydırınca geri gelir", policy.shouldRepostOnDismiss(responded))
		assertEquals("aynı alarm ikinci kez çalmaz", responded, policy.onSent(responded, "a1", "x", null, start + 1))

		assertEquals(AlarmPhase.MUTED, policy.onMuted(ringing).phase)
		val cancelled = policy.onResolved(responded, cancelled = true)
		assertEquals(AlarmPhase.CANCELLED, cancelled.phase)
		assertFalse(policy.shouldRepostOnDismiss(cancelled))
		assertEquals("çözülmüş alarm yanıtla değişmez", cancelled, policy.onResponded(cancelled, "RECEIVED"))
	}

	@Test
	fun deviceRegistrationPutsTokenForInstallationOnlyWithSession() = runBlocking {
		val backend = FakeBackend()
		try {
			backend.handler = { MockResponse().json(201, """{"installationId":"kurulum-1","active":true}""") }
			val sessions = SessionManager(MemoryStore(StoredSession("access", "refresh", SESSION_ID, "2026-11-16T12:00:00Z")))
			val registrar = DeviceRegistrar(backend.api(sessions), ApiCaller(GksJson), sessions, { "kurulum-1" }, { "fcm-belirtec" })
			assertTrue(registrar.sync() is ApiResult.Success)
			assertEquals("PUT", backend.requests[0].method)
			assertEquals("/api/v1/devices/kurulum-1", backend.requests[0].path)
			val body = backend.requests[0].body.readUtf8()
			assertTrue(body.contains("\"fcmToken\":\"fcm-belirtec\"") && body.contains("appVersion"))

			val loggedOut = SessionManager(MemoryStore(null))
			assertNull(DeviceRegistrar(backend.api(loggedOut), ApiCaller(GksJson), loggedOut, { "k" }, { "t" }).sync())
			assertEquals(1, backend.requests.size)
		}
		finally {
			backend.shutdown()
		}
	}
}
