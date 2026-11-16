package org.gks.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.gks.android.GksApplication
import org.gks.android.R
import org.gks.android.data.LocationRepository

/**
 * Konum paylaşımının ön plan servisi (AND-06, TD-18). Kalıcı bildirim paylaşımın sürdüğünü gösterir ve "Durdur"
 * düğmesi taşır. Konum yalnızca Android çerçevesinin {@link LocationManager}'ı ile alınır (ek bağımlılık yok);
 * dakikada bir ölçüm gönderilir. Sunucu paylaşımın bittiğini söylerse ({@link LocationRepository.STOP_CODES})
 * servis kendini durdurur.
 */
class LocationSharingService : Service() {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val operations = linkedSetOf<String>()
	private var listener: LocationListener? = null

	private val repository: LocationRepository get() = (application as GksApplication).container.locationRepository
	private var streaming = false

	/** ADR-021: servis çalışırken canlı akış açık kalır; uygulama arka plandayken de alarm/duyuru bildirimi gelir. */
	private fun holdStream(hold: Boolean) {
		val container = (application as GksApplication).container
		if (hold && !streaming) {
			container.liveNotifier.start()
			container.realtime.acquire()
			streaming = true
		}
		else if (!hold && streaming) {
			container.realtime.release()
			streaming = false
		}
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val operationId = intent?.getStringExtra(EXTRA_OPERATION)
		when (intent?.action) {
			ACTION_STOP -> {
				val targets = operationId?.let { listOf(it) } ?: operations.toList()
				targets.forEach { op -> scope.launch { repository.stop(op) } }
				operations.removeAll(targets.toSet())
			}
			else -> if (operationId != null) operations += operationId
		}
		if (operations.isEmpty()) {
			holdStream(false)
			stopUpdates()
			ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}
		ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType())
		startUpdates()
		holdStream(true)
		return START_NOT_STICKY   // süreç ölürse kullanıcı yeniden başlatır; sessizce paylaşım sürmez
	}

	override fun onDestroy() {
		holdStream(false)
		stopUpdates()
		scope.cancel()
		super.onDestroy()
	}

	@SuppressLint("MissingPermission")
	private fun startUpdates() {
		if (listener != null) return
		if (!hasPermission(this)) {
			operations.forEach { op -> scope.launch { repository.reportUnavailable(op, "PERMISSION_DENIED") } }
			return
		}
		val manager = getSystemService(LocationManager::class.java)
		val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
		if (providers.isEmpty()) {
			operations.forEach { op -> scope.launch { repository.reportUnavailable(op, "GPS_OFF") } }
		}
		val l = LocationListener { location -> send(location) }
		providers.forEach { manager.requestLocationUpdates(it, INTERVAL_MILLIS, 0f, l, Looper.getMainLooper()) }
		listener = l
	}

	private fun stopUpdates() {
		listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
		listener = null
	}

	private fun send(location: Location) {
		val measuredAt = Instant.ofEpochMilli(location.time)
		operations.toList().forEach { op ->
			scope.launch {
				val result = repository.send(op, location.latitude, location.longitude, location.accuracy.toDouble(), measuredAt)
				if (LocationRepository.shouldStop(result)) {
					startService(stopIntent(this@LocationSharingService, op))
				}
			}
		}
	}

	private fun notification() = NotificationCompat.Builder(this, ensureChannel())
		.setSmallIcon(android.R.drawable.ic_menu_mylocation)
		.setContentTitle(getString(R.string.location_notification_title))
		.setContentText(getString(R.string.location_notification_body))
		.setOngoing(true)
		.setPriority(NotificationCompat.PRIORITY_LOW)
		.addAction(0, getString(R.string.location_stop), PendingIntent.getService(this, 1, stopIntent(this, null),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
		.build()

	private fun ensureChannel(): String {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val manager = getSystemService(NotificationManager::class.java)
			if (manager.getNotificationChannel(CHANNEL_ID) == null) {
				manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.location_channel),
					NotificationManager.IMPORTANCE_LOW))
			}
		}
		return CHANNEL_ID
	}

	private fun foregroundType(): Int =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

	companion object {
		const val INTERVAL_MILLIS = 60_000L
		private const val NOTIFICATION_ID = 4101
		private const val CHANNEL_ID = "location-sharing"
		private const val EXTRA_OPERATION = "operationId"
		private const val ACTION_STOP = "org.gks.android.location.STOP"

		val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

		fun hasPermission(context: Context) = PERMISSIONS.any {
			ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
		}

		fun start(context: Context, operationId: String) {
			ContextCompat.startForegroundService(context, Intent(context, LocationSharingService::class.java)
				.putExtra(EXTRA_OPERATION, operationId))
		}

		fun stopIntent(context: Context, operationId: String?): Intent =
			Intent(context, LocationSharingService::class.java).setAction(ACTION_STOP).apply {
				operationId?.let { putExtra(EXTRA_OPERATION, it) }
			}
	}
}
