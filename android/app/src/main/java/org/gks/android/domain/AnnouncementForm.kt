package org.gks.android.domain

import org.gks.android.data.AnnouncementAudience
import org.gks.android.data.AnnouncementRepository
import org.gks.android.data.remote.Participant
import java.util.Locale

/** Operasyon duyurusunun hedef türü (web `AnnouncementsPage` ile aynı + tek tek kişi seçimi). */
enum class AudienceKind { WHOLE_OPERATION, GROUPS, PEOPLE }

/** Formdaki seçimden sunucu hedefi; seçim gerektiren türde seçim yoksa `isEmpty` doğrudur. */
fun audienceOf(operationId: String?, kind: AudienceKind, groupIds: Set<String>, userIds: Set<String>): AnnouncementAudience =
	when {
		operationId == null -> AnnouncementAudience.Organization
		kind == AudienceKind.WHOLE_OPERATION -> AnnouncementAudience.WholeOperation(operationId)
		kind == AudienceKind.GROUPS -> AnnouncementAudience.Groups(operationId, groupIds)
		else -> AnnouncementAudience.People(operationId, userIds)
	}

private val TURKISH = Locale.forLanguageTag("tr-TR")

/** Kişi arama: görünen ad veya ad soyad içinde, Türkçe büyük/küçük harf duyarsız; boş arama hepsini verir. */
fun filterPeople(participants: List<Participant>, query: String): List<Participant> {
	val q = query.trim().lowercase(TURKISH)
	val sorted = participants.sortedBy { it.user.displayName.lowercase(TURKISH) }
	if (q.isEmpty()) return sorted
	return sorted.filter { p ->
		p.user.displayName.lowercase(TURKISH).contains(q) || p.user.fullName?.lowercase(TURKISH)?.contains(q) == true
	}
}

/** İstemci doğrulaması (backend NT-02 ile aynı sınırlar); hatalı alan → hata türü. */
enum class AnnouncementFieldError { TITLE_REQUIRED, TITLE_TOO_SHORT, TITLE_TOO_LONG, BODY_REQUIRED, BODY_TOO_LONG }

fun validateAnnouncement(title: String, body: String): Map<String, AnnouncementFieldError> = buildMap {
	val t = title.trim()
	val b = body.trim()
	when {
		t.isEmpty() -> put("title", AnnouncementFieldError.TITLE_REQUIRED)
		t.length < AnnouncementRepository.TITLE_MIN -> put("title", AnnouncementFieldError.TITLE_TOO_SHORT)
		t.length > AnnouncementRepository.TITLE_MAX -> put("title", AnnouncementFieldError.TITLE_TOO_LONG)
	}
	when {
		b.isEmpty() -> put("body", AnnouncementFieldError.BODY_REQUIRED)
		b.length > AnnouncementRepository.BODY_MAX -> put("body", AnnouncementFieldError.BODY_TOO_LONG)
	}
}
