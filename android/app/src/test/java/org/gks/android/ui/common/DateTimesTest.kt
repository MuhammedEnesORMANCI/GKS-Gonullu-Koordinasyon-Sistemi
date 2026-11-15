package org.gks.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Tarih-saat seçici dönüşümleri: yerel seçim ↔ UTC ISO, gün/yıl dönümü, yaz saati ve seçici milisaniyesi. */
class DateTimesTest {

	private val istanbul = ZoneId.of("Europe/Istanbul")   // UTC+3, yaz saati yok

	@Test
	fun localSelectionBecomesUtcIso() {
		assertEquals("2026-12-01T05:30:00Z", DateTimes.toIso(LocalDate.of(2026, 12, 1), LocalTime.of(8, 30), istanbul))
		assertEquals("saniye ve nano atılır", "2026-12-01T05:30:00Z",
			DateTimes.toIso(LocalDate.of(2026, 12, 1), LocalTime.of(8, 30, 59, 999), istanbul))
		assertEquals("2026-12-01T08:30:00Z", DateTimes.toIso(LocalDate.of(2026, 12, 1), LocalTime.of(8, 30), ZoneOffset.UTC))
	}

	@Test
	fun dayAndYearBoundaries() {
		// Yerel gece 01:30 → UTC'de önceki gün
		assertEquals("2026-11-30T22:30:00Z", DateTimes.toIso(LocalDate.of(2026, 12, 1), LocalTime.of(1, 30), istanbul))
		assertEquals("2026-12-31T21:15:00Z", DateTimes.toIso(LocalDate.of(2027, 1, 1), LocalTime.of(0, 15), istanbul))
		assertEquals("2027-01-01T20:59:00Z", DateTimes.toIso(LocalDate.of(2027, 1, 1), LocalTime.of(23, 59), istanbul))
		// Düzenlemede sunucu değeri doğru yerel gün ve saatle geri yüklenir
		assertEquals(LocalDate.of(2026, 12, 1), DateTimes.localDate("2026-11-30T22:30:00Z", istanbul))
		assertEquals(LocalTime.of(1, 30), DateTimes.localTime("2026-11-30T22:30:00Z", istanbul))
		assertEquals(LocalDate.of(2026, 11, 30), DateTimes.localDate("2026-11-30T22:30:00Z", ZoneOffset.UTC))
		assertEquals(LocalTime.of(0, 0), DateTimes.localTime("2026-12-31T21:00:00Z", istanbul))
	}

	@Test
	fun roundTripKeepsServerValue() {
		val iso = "2026-12-01T05:30:00Z"
		val back = DateTimes.toIso(DateTimes.localDate(iso, istanbul)!!, DateTimes.localTime(iso, istanbul)!!, istanbul)
		assertEquals(iso, back)
	}

	@Test
	fun daylightSavingGapAndOverlapInOtherZones() {
		val berlin = ZoneId.of("Europe/Berlin")
		// 29.03.2026 02:30 yoktur (ileri alma) → 03:30 CEST = 01:30Z
		assertEquals("2026-03-29T01:30:00Z", DateTimes.toIso(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), berlin))
		// 25.10.2026 02:30 iki kez yaşanır → önceki (yaz) ofset +02:00 = 00:30Z
		assertEquals("2026-10-25T00:30:00Z", DateTimes.toIso(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), berlin))
	}

	@Test
	fun pickerMillisAreUtcMidnightWithoutZoneShift() {
		val date = LocalDate.of(2026, 12, 1)
		val millis = DateTimes.dateToPickerMillis(date)
		assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), millis)
		assertEquals(date, DateTimes.pickerMillisToDate(millis))
		assertEquals(LocalDate.of(2027, 1, 1), DateTimes.pickerMillisToDate(DateTimes.dateToPickerMillis(LocalDate.of(2027, 1, 1))))
	}

	@Test
	fun displayAndInitialValues() {
		assertEquals("1 Aralık 2026, 08:30", DateTimes.display("2026-12-01T05:30:00Z", istanbul))
		assertEquals("24 saat biçimi", "1 Aralık 2026, 23:05", DateTimes.display("2026-12-01T20:05:00Z", istanbul))
		assertEquals("", DateTimes.display(null, istanbul))
		assertEquals("", DateTimes.display("bozuk", istanbul))
		assertNull(DateTimes.parse("bozuk"))

		val now = Instant.parse("2026-11-16T07:20:00Z")   // İstanbul 10:20
		assertEquals(LocalDate.of(2026, 11, 16), DateTimes.initialDate(null, istanbul, now))
		assertEquals(LocalTime.of(11, 0), DateTimes.initialTime(null, istanbul, now))
		assertEquals(LocalTime.of(8, 30), DateTimes.initialTime("2026-12-01T05:30:00Z", istanbul, now))
		// 23:20'de bir sonraki tam saat gece yarısı
		assertEquals(LocalTime.MIDNIGHT, DateTimes.initialTime(null, istanbul, Instant.parse("2026-11-16T20:20:00Z")))
	}
}
