package org.gks.android.ui.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Form tarih/saat girişi: kullanıcı cihaz saat diliminde `gg.aa.yyyy ss:dd` yazar, sunucuya ISO-8601 UTC gider. */
object DateInput {
	val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

	/** @return geçersiz biçimde `null` */
	fun parse(text: String): Instant? = try {
		LocalDateTime.parse(text.trim(), FORMAT).atZone(ZoneId.systemDefault()).toInstant()
	} catch (e: DateTimeParseException) {
		null
	}

	fun format(instant: Instant): String = FORMAT.format(instant.atZone(ZoneId.systemDefault()))

	/** ISO metni form biçimine; boş veya bozuksa boş metin. */
	fun formatIso(iso: String?): String = iso?.let { runCatching { format(Instant.parse(it)) }.getOrNull() }.orEmpty()
}
