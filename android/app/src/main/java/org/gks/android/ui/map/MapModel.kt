package org.gks.android.ui.map

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.gks.android.data.remote.MapOperationView
import org.gks.android.data.remote.OperationArea

/** Haritadaki nokta; [colorHex] durum rengi, anlam ayrıca [label] ve listede de verilir (ADR-025). */
data class MapPoint(val id: String, val lat: Double, val lon: Double, val colorHex: String, val label: String)

data class MapCircle(val id: String, val lat: Double, val lon: Double, val radiusM: Int, val colorHex: String)

object MapColors {
	const val ACTIVE = "#0B2D57"
	const val PLANNED = "#6B7280"
	const val SUSPENDED = "#C2410C"
	const val ALERT = "#B91C1C"
	const val MEETING_POINT = "#0369A1"
	const val PERSON = "#15803D"

	fun forState(state: String, openAlert: Boolean): String = when {
		openAlert -> ALERT
		state == "ACTIVE" -> ACTIVE
		state == "SUSPENDED" -> SUSPENDED
		else -> PLANNED
	}
}

const val OSM_ATTRIBUTION = "© OpenStreetMap katkıcıları"
const val RADIUS_MIN = 50
const val RADIUS_MAX = 200_000

/** Bölge merkezi, yoksa ilk buluşma noktası; ikisi de yoksa haritada yer almaz. */
fun MapOperationView.anchor(): Pair<Double, Double>? =
	area?.let { it.lat to it.lon } ?: meetingPoints.firstOrNull()?.let { it.lat to it.lon }

fun operationPoints(items: List<MapOperationView>): List<MapPoint> = items.mapNotNull { op ->
	op.anchor()?.let { (lat, lon) -> MapPoint(op.id, lat, lon, MapColors.forState(op.state, op.openAlert != null), op.name) }
}

fun operationCircles(items: List<MapOperationView>): List<MapCircle> = items.mapNotNull { op ->
	val a = op.area ?: return@mapNotNull null
	a.radiusM?.let { MapCircle(op.id, a.lat, a.lon, it, MapColors.forState(op.state, op.openAlert != null)) }
}

/**
 * "Yol tarifi" için `geo:` adresi (ek bağımlılık yok; telefondaki harita uygulaması açılır). Sayılar yerel ayardan
 * bağımsız noktayla yazılır: Türkçe yerel ayarda `%f` virgül üretir ve adres bozulur.
 */
fun geoUri(lat: Double, lon: Double, label: String): String {
	val ll = "${coord(lat)},${coord(lon)}"
	val q = URLEncoder.encode(label, "UTF-8").replace("+", "%20")
	return "geo:$ll?q=$ll($q)"
}

fun coord(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

fun formatLatLon(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)

/** Daireyi çokgene çevirir: [boylam, enlem] çiftleri, kapalı halka. */
fun circlePolygon(lat: Double, lon: Double, radiusM: Int, steps: Int = 64): List<Pair<Double, Double>> {
	val earth = 6_371_008.8
	val lat1 = Math.toRadians(lat)
	val lon1 = Math.toRadians(lon)
	val d = radiusM / earth
	return (0..steps).map { i ->
		val bearing = 2 * Math.PI * (i % steps) / steps
		val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearing))
		val lon2 = lon1 + atan2(sin(bearing) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
		Math.toDegrees(lon2) to Math.toDegrees(lat2)
	}
}

/** Bölge taslağı doğrulaması; hata anahtarları sunucununkilerle aynı (`area.lat`, `area.lon`, `area.radiusM`). */
data class AreaParse(val area: OperationArea?, val errors: Map<String, AreaError>)

enum class AreaError { BOTH_REQUIRED, LAT_RANGE, LON_RANGE, RADIUS_RANGE, RADIUS_WITHOUT_CENTER }

fun parseCoordinate(value: String): Double? = value.trim().takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()
	?: if (value.isBlank()) null else Double.NaN

fun parseArea(lat: String, lon: String, radius: String): AreaParse {
	val la = parseCoordinate(lat)
	val lo = parseCoordinate(lon)
	val r = radius.trim().takeIf { it.isNotEmpty() }
	val errors = linkedMapOf<String, AreaError>()
	if (la == null && lo == null) {
		if (r != null) errors["area.radiusM"] = AreaError.RADIUS_WITHOUT_CENTER
		return AreaParse(null, errors)
	}
	if (la == null || lo == null) errors["area.lat"] = AreaError.BOTH_REQUIRED
	else {
		if (la.isNaN() || la < -90 || la > 90) errors["area.lat"] = AreaError.LAT_RANGE
		if (lo.isNaN() || lo < -180 || lo > 180) errors["area.lon"] = AreaError.LON_RANGE
	}
	val radiusM = r?.toIntOrNull()
	if (r != null && (radiusM == null || radiusM !in RADIUS_MIN..RADIUS_MAX)) errors["area.radiusM"] = AreaError.RADIUS_RANGE
	if (errors.isNotEmpty() || la == null || lo == null) return AreaParse(null, errors)
	return AreaParse(OperationArea(la, lo, radiusM), errors)
}

/** Raster karo stili (MapLibre style JSON); atıf metni stilin kaynağında da yer alır. */
fun rasterStyleJson(tileUrl: String, attribution: String = OSM_ATTRIBUTION): String {
	fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
	return """{"version":8,"sources":{"base":{"type":"raster","tiles":["${esc(tileUrl)}"],"tileSize":256,"maxzoom":19,""" +
		""""attribution":"${esc(attribution)}"}},"layers":[{"id":"base","type":"raster","source":"base"}]}"""
}
