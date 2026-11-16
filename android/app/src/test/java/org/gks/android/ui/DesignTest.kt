package org.gks.android.ui

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.gks.android.GksJson
import org.gks.android.data.ApiCaller
import org.gks.android.data.ApiResult
import org.gks.android.data.FakeBackend
import org.gks.android.data.MemoryStore
import org.gks.android.data.PublicAnnouncementRepository
import org.gks.android.data.guide.GuideContent
import org.gks.android.data.json
import org.gks.android.data.session.SessionManager
import org.gks.android.ui.common.StatusKind
import org.gks.android.ui.common.StatusTone
import org.gks.android.ui.common.statusTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-023: gömülü rehber içeriği (web ile aynı dosyalar), alt gezinme, durum rozetleri, kamu duyuruları. */
class DesignTest {

	/** Derlemede assets/guide altına kopyalanan web içerik dosyaları (internetsiz rehberin kaynağı). */
	private val contentDir = listOf(File("../../web/src/content"), File("../web/src/content")).first { it.isDirectory }

	private fun content() = GuideContent.parse(
		File(contentDir, "hazards.json").readText(),
		File(contentDir, "guide.json").readText(),
		File(contentDir, "emergency.json").readText(),
	)

	@Test
	fun embeddedGuideParsesWebContentWithVerificationNotesAndOnly112() {
		val c = content()
		assertEquals(listOf("deprem", "sel", "heyelan", "yangin", "kis", "diger"), c.hazards.hazards.map { it.slug })
		c.hazards.hazards.forEach { h ->
			assertTrue(h.slug, h.before.isNotEmpty() && h.during.isNotEmpty() && h.after.isNotEmpty())
		}
		assertEquals(listOf("acil-durum-cantasi", "aile-afet-plani", "toplanma-alani"), c.guide.sections.map { it.id })
		assertTrue(c.emergency.callout.contains("112"))
		listOf(c.hazards.verification, c.guide.verification, c.emergency.verification).forEach {
			assertTrue(it, it.startsWith("Yetkili kaynaklarla doğrulanmalı"))
		}
		contentDir.listFiles { f -> f.extension == "json" }!!.forEach { file ->
			val numbers = Regex("\\b\\d{3,}\\b").findAll(file.readText()).map { it.value }.toSet()
			assertTrue("${file.name}: $numbers", numbers.all { it == "112" })
		}
		assertEquals("Deprem", c.hazard("deprem")?.title)
		assertEquals(null, c.hazard("yok"))
	}

	@Test
	fun bottomNavigationHasHomeGuidesAnnouncementsAccountAndManagementOnlyForManagementSession() {
		assertEquals(listOf(Tab.HOME, Tab.GUIDES, Tab.ANNOUNCEMENTS, Tab.ACCOUNT), bottomTabs(management = false))
		assertEquals(listOf(Tab.HOME, Tab.GUIDES, Tab.ANNOUNCEMENTS, Tab.MANAGEMENT, Tab.ACCOUNT), bottomTabs(management = true))
		// Mevcut akışlar korunur: rota olarak duruyorlar (Hızlı Erişim ve bildirim zilinden açılır).
		assertTrue(Tab.entries.containsAll(listOf(Tab.TASKS, Tab.GROUPS, Tab.NOTIFICATIONS)))
		assertFalse(bottomTabs(true).contains(Tab.NOTIFICATIONS))
	}

	@Test
	fun statusTonesMatchWebMapping() {
		assertEquals(StatusTone.SUCCESS, statusTone(StatusKind.OPERATION, "ACTIVE"))
		assertEquals(StatusTone.INFO, statusTone(StatusKind.OPERATION, "PLANNED"))
		assertEquals(StatusTone.NEUTRAL, statusTone(StatusKind.OPERATION, "COMPLETED"))
		assertEquals(StatusTone.DANGER, statusTone(StatusKind.OPERATION, "CANCELLED"))
		assertEquals(StatusTone.WARNING, statusTone(StatusKind.TASK, "IN_PROGRESS"))
		assertEquals(StatusTone.SUCCESS, statusTone(StatusKind.TASK, "DONE"))
	}

	@Test
	fun publicAnnouncementsComeFromPublicEndpoint() = runBlocking {
		val backend = FakeBackend()
		try {
			backend.handler = {
				MockResponse().json(200, """{"items":[{"id":"p1","title":"Tatbikat","body":"Metin","publishedAt":"2026-11-16T09:00:00Z"}]}""")
			}
			val sessions = SessionManager(MemoryStore(null))
			val result = PublicAnnouncementRepository(backend.api(sessions), ApiCaller(GksJson)).page()
			assertTrue(result is ApiResult.Success)
			assertEquals("Tatbikat", (result as ApiResult.Success).value.items.single().title)
			assertEquals("GET", backend.requests[0].method)
			assertEquals("/api/v1/public/announcements?limit=20", backend.requests[0].path)
		}
		finally {
			backend.shutdown()
		}
	}
}
