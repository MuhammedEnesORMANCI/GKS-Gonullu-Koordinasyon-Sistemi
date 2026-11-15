package org.gks.android.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Tarih-saat seçici dönüşümleri (saf, Compose'suz; birim testli). Backend tarih alanları (`plannedEndAt`,
 * `timeWindow.start/end`) `OffsetDateTime` olarak çözülüp `Instant` saklanır; istemci her zaman UTC `Instant`
 * metni gönderir (`2026-12-01T05:30:00Z`). Kullanıcı yerel saat diliminde (cihaz; Türkiye'de Europe/Istanbul) seçer.
 */
object DateTimes {

	private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("tr-TR"))

	/** Yerel tarih + saat → UTC ISO metni. Yaz saati boşluğunda `ZonedDateTime` bir sonraki geçerli ana kaydırır. */
	fun toIso(date: LocalDate, time: LocalTime, zone: ZoneId): String =
		ZonedDateTime.of(date, time.withSecond(0).withNano(0), zone).toInstant().toString()

	fun parse(iso: String?): Instant? = iso?.let {
		try { Instant.parse(it) } catch (e: DateTimeParseException) { null }
	}

	/** Sunucu değerinin yerel tarihi (düzenleme ekranında seçiciyi doğru günle açmak için). */
	fun localDate(iso: String?, zone: ZoneId): LocalDate? = parse(iso)?.atZone(zone)?.toLocalDate()

	fun localTime(iso: String?, zone: ZoneId): LocalTime? = parse(iso)?.atZone(zone)?.toLocalTime()?.withSecond(0)?.withNano(0)

	/** Ekran metni, 24 saat biçiminde Türkçe (ör. "1 Aralık 2026, 08:30"); boşsa boş metin. */
	fun display(iso: String?, zone: ZoneId): String = parse(iso)?.atZone(zone)?.let(DISPLAY::format).orEmpty()

	/** Material3 `DatePicker` seçimi UTC gece yarısı milisaniyesidir; saat dilimine göre kaydırılmadan tarihe çevrilir. */
	fun pickerMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

	fun dateToPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

	/** Seçicide gösterilecek başlangıç: mevcut değer, yoksa bugün. */
	fun initialDate(iso: String?, zone: ZoneId, now: Instant): LocalDate = localDate(iso, zone) ?: now.atZone(zone).toLocalDate()

	/** Mevcut değerin saati, yoksa bir sonraki tam saat. */
	fun initialTime(iso: String?, zone: ZoneId, now: Instant): LocalTime =
		localTime(iso, zone) ?: now.atZone(zone).toLocalTime().withMinute(0).withSecond(0).withNano(0).plusHours(1)
}
