package org.gks.android.ui.map

import java.util.Locale
import kotlin.math.abs
import org.gks.android.data.remote.MapAlert
import org.gks.android.data.remote.MapMeetingPoint
import org.gks.android.data.remote.MapOperationView
import org.gks.android.data.remote.OperationArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-025: harita modeli (renk, yol tarifi adresi, bölge doğrulaması, stil). */
class MapModelTest {

	@Test
	fun geoUriIsLocaleIndependentAndEncoded() {
		val previous = Locale.getDefault()
		Locale.setDefault(Locale.forLanguageTag("tr-TR"))
		try {
			assertEquals("geo:40.981000,29.051000?q=40.981000,29.051000(Okul%20bah%C3%A7esi)", geoUri(40.981, 29.051, "Okul bahçesi"))
			assertEquals("40.98100, 29.05100", formatLatLon(40.981, 29.051))
		}
		finally {
			Locale.setDefault(previous)
		}
	}

	@Test
	fun markerColorsFollowStateAndOpenAlertIsRed() {
		val base = MapOperationView(id = "a", name = "A", state = "ACTIVE", area = OperationArea(40.0, 29.0, 1000))
		val items = listOf(
			base,
			base.copy(id = "b", state = "PLANNED", openAlert = MapAlert("x")),
			base.copy(id = "c", area = null),
			base.copy(id = "d", area = null, meetingPoints = listOf(MapMeetingPoint("g", "G", 41.0, 28.0))),
		)
		assertEquals(listOf("a" to MapColors.ACTIVE, "b" to MapColors.ALERT, "d" to MapColors.ACTIVE),
			operationPoints(items).map { it.id to it.colorHex })
		assertEquals(listOf("a", "b"), operationCircles(items).map { it.id })
		assertNull(items[2].anchor())
	}

	@Test
	fun areaValidation() {
		assertEquals(AreaParse(null, emptyMap()), parseArea("", "", ""))
		assertEquals(OperationArea(40.5, 29.1, 1500), parseArea("40,5", "29.1", "1500").area)
		assertEquals(AreaError.LAT_RANGE, parseArea("95", "29", "").errors["area.lat"])
		assertEquals(AreaError.LON_RANGE, parseArea("40", "181", "").errors["area.lon"])
		assertEquals(AreaError.BOTH_REQUIRED, parseArea("40", "", "").errors["area.lat"])
		assertEquals(AreaError.RADIUS_RANGE, parseArea("40", "29", "10").errors["area.radiusM"])
		assertEquals(AreaError.RADIUS_WITHOUT_CENTER, parseArea("", "", "500").errors["area.radiusM"])
		assertEquals(AreaError.LAT_RANGE, parseArea("abc", "29", "").errors["area.lat"])
	}

	@Test
	fun circleRingIsClosedAndAtRadius() {
		val ring = circlePolygon(40.0, 29.0, 2000, 16)
		assertEquals(17, ring.size)
		assertEquals(ring.first(), ring.last())
		ring.forEach { (lon, lat) ->
			val dLat = Math.toRadians(lat - 40.0)
			val dLon = Math.toRadians(lon - 29.0)
			val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(40.0)) * Math.cos(Math.toRadians(lat)) * Math.sin(dLon / 2).let { it * it }
			val d = 2 * 6_371_008.8 * Math.asin(Math.sqrt(h))
			assertTrue("mesafe $d", abs(d - 2000) < 1)
		}
	}

	@Test
	fun styleCarriesTileUrlAndOsmAttribution() {
		val style = rasterStyleJson("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
		assertTrue(style.contains("\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"]"))
		assertTrue(style.contains("© OpenStreetMap katkıcıları"))
	}
}
