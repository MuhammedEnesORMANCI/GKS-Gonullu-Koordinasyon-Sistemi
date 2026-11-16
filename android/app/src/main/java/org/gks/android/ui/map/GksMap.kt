package org.gks.android.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import org.gks.android.BuildConfig
import org.gks.android.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val POINTS = "gks-points"
private const val AREAS = "gks-areas"

/**
 * MapLibre haritası (ADR-025). Karo adresi `BuildConfig.MAP_TILE_URL`; OSM atfı her haritanın altında. Harita
 * kurulamazsa (eski cihaz, GL hatası) yalnızca bilgi metni gösterilir; çağıran ekranlar koordinatları ve
 * buluşma noktasını her zaman metin olarak da verir (çevrimdışı karolar yüklenmese de bilgi kaybolmaz).
 */
@Composable
fun GksMap(
	points: List<MapPoint>,
	circles: List<MapCircle> = emptyList(),
	height: Dp = 240.dp,
	description: String,
	onPointClick: ((String) -> Unit)? = null,
	onPick: ((Double, Double) -> Unit)? = null,
	showMyLocation: Boolean = false,
) {
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	var failed by remember { mutableStateOf(false) }
	val mapView = remember {
		runCatching {
			MapLibre.getInstance(context)
			MapView(context).apply { onCreate(null) }
		}.onFailure { failed = true }.getOrNull()
	}
	var map by remember { mutableStateOf<MapLibreMap?>(null) }
	var style by remember { mutableStateOf<Style?>(null) }
	var fitted by remember { mutableStateOf(false) }
	val currentPointClick by rememberUpdatedState(onPointClick)
	val currentPick by rememberUpdatedState(onPick)

	if (failed || mapView == null) {
		Text(stringResource(R.string.map_unavailable), style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant)
		return
	}

	DisposableEffect(lifecycle, mapView) {
		val observer = LifecycleEventObserver { _, event ->
			when (event) {
				Lifecycle.Event.ON_START -> mapView.onStart()
				Lifecycle.Event.ON_RESUME -> mapView.onResume()
				Lifecycle.Event.ON_PAUSE -> mapView.onPause()
				Lifecycle.Event.ON_STOP -> mapView.onStop()
				else -> Unit
			}
		}
		lifecycle.addObserver(observer)
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
		if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
		mapView.getMapAsync { m ->
			map = m
			m.uiSettings.isLogoEnabled = false
			m.uiSettings.isRotateGesturesEnabled = false
			m.setStyle(Style.Builder().fromJson(rasterStyleJson(BuildConfig.MAP_TILE_URL))) { s ->
				s.addSource(GeoJsonSource(AREAS, FeatureCollection.fromFeatures(emptyList())))
				s.addLayer(FillLayer("$AREAS-fill", AREAS).withProperties(
					PropertyFactory.fillColor(Expression.get("color")), PropertyFactory.fillOpacity(0.15f)))
				s.addLayer(LineLayer("$AREAS-line", AREAS).withProperties(
					PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(2f)))
				s.addSource(GeoJsonSource(POINTS, FeatureCollection.fromFeatures(emptyList())))
				s.addLayer(CircleLayer(POINTS, POINTS).withProperties(
					PropertyFactory.circleColor(Expression.get("color")), PropertyFactory.circleRadius(9f),
					PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
				style = s
			}
			m.addOnMapClickListener { latLng ->
				val screen = m.projection.toScreenLocation(latLng)
				val hit = m.queryRenderedFeatures(screen, POINTS).firstOrNull()?.getStringProperty("id")
				when {
					hit != null && currentPointClick != null -> { currentPointClick?.invoke(hit); true }
					currentPick != null -> { currentPick?.invoke(latLng.latitude, latLng.longitude); true }
					else -> false
				}
			}
		}
		onDispose {
			lifecycle.removeObserver(observer)
			mapView.onPause()
			mapView.onStop()
			mapView.onDestroy()
		}
	}

	LaunchedEffect(style, points, circles) {
		val s = style ?: return@LaunchedEffect
		val m = map ?: return@LaunchedEffect
		s.getSourceAs<GeoJsonSource>(POINTS)?.setGeoJson(FeatureCollection.fromFeatures(points.map { p ->
			Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
				addStringProperty("id", p.id)
				addStringProperty("color", p.colorHex)
			}
		}))
		s.getSourceAs<GeoJsonSource>(AREAS)?.setGeoJson(FeatureCollection.fromFeatures(circles.map { c ->
			val ring = circlePolygon(c.lat, c.lon, c.radiusM).map { (lon, lat) -> Point.fromLngLat(lon, lat) }
			Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply { addStringProperty("color", c.colorHex) }
		}))
		if (!fitted) {
			val all = points.map { LatLng(it.lat, it.lon) } +
				circles.flatMap { c -> circlePolygon(c.lat, c.lon, c.radiusM, 8).map { (lon, lat) -> LatLng(lat, lon) } }
			when {
				all.isEmpty() -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.0, 35.2), 4.5))
				all.size == 1 -> { m.moveCamera(CameraUpdateFactory.newLatLngZoom(all.first(), 14.0)); fitted = true }
				else -> {
					runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(all).build(), 64)) }
					fitted = true
				}
			}
		}
	}

	LaunchedEffect(style, showMyLocation) {
		val s = style ?: return@LaunchedEffect
		val m = map ?: return@LaunchedEffect
		if (showMyLocation && hasLocationPermission(context)) {
			runCatching {
				m.locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(context, s).build())
				@Suppress("MissingPermission")
				m.locationComponent.isLocationComponentEnabled = true
				m.locationComponent.renderMode = RenderMode.NORMAL
			}
		}
	}

	Column {
		AndroidView(
			factory = { mapView },
			modifier = Modifier.fillMaxWidth().height(height).semantics { contentDescription = description },
		)
		Text(
			OSM_ATTRIBUTION + if (onPick != null) " · " + stringResource(R.string.map_pick_hint) else "",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

private fun hasLocationPermission(context: Context) =
	ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
		ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** "Yol tarifi": telefondaki harita uygulamasını `geo:` ile açar (ek bağımlılık yok, ağsız da çalışır). */
fun openDirections(context: Context, lat: Double, lon: Double, label: String) {
	try {
		context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(lat, lon, label))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	}
	catch (e: ActivityNotFoundException) {
		Toast.makeText(context, R.string.map_no_app, Toast.LENGTH_LONG).show()
	}
}
