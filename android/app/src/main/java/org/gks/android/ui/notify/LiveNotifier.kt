package org.gks.android.ui.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.NotificationRepository
import org.gks.android.data.realtime.RealtimeStream
import org.gks.android.data.remote.InboxItem
import org.gks.android.ui.MainActivity

/**
 * FCM gelene kadar ara çözüm (ADR-021): canlı akıştan `inbox.changed` gelince gelen kutusu okunur ve yeni okunmamış
 * **acil alarm** ve **duyuru** kayıtları yerel Android bildirimi olarak gösterilir. Acil alarm yüksek öncelikli
 * kanaldadır. Yalnızca akış açıkken çalışır: uygulama ön plandayken veya konum paylaşımı ön plan servisi
 * çalışırken. **Uygulama tamamen kapalıyken bildirim gelmez.** Akış başlarken mevcut kayıtlar taban alınır
 * (eski kayıtlar için bildirim çıkmaz).
 */
class LiveNotifier(
	private val context: Context,
	private val realtime: RealtimeStream,
	private val repository: NotificationRepository,
	private val scope: CoroutineScope,
) {

	private val shown = mutableSetOf<String>()
	private val mutex = Mutex()
	private var baselined = false
	private var job: Job? = null

	@Synchronized
	fun start() {
		if (job != null) return
		ensureChannels(context)
		job = scope.launch {
			realtime.events.collect { event ->
				if (event.type == "inbox.changed" && event.data.contains("\"CREATED\"") || event.type == "resync") check()
			}
		}
		scope.launch { check() }   // taban: o anki kayıtlar bildirim üretmez
	}

	private suspend fun check() = mutex.withLock {
		val page = (repository.page(null) as? ApiResult.Success)?.value ?: return@withLock
		if (!baselined) {
			page.items.forEach { shown += it.id }
			baselined = true
			return@withLock
		}
		selectNew(page.items, shown).forEach { item ->
			shown += item.id
			post(item)
		}
	}

	private fun post(item: InboxItem) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			return   // izin yok: uygulama içi bant ve gelen kutusu yine gösterir
		}
		val urgent = isUrgent(item)
		val open = PendingIntent.getActivity(context, item.id.hashCode(),
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val notification = NotificationCompat.Builder(context, if (urgent) CHANNEL_ALERTS else CHANNEL_ANNOUNCEMENTS)
			.setSmallIcon(android.R.drawable.ic_dialog_alert.takeIf { urgent } ?: android.R.drawable.ic_dialog_info)
			.setContentTitle(item.title)
			.setContentText(item.body.orEmpty())
			.setStyle(NotificationCompat.BigTextStyle().bigText(item.body.orEmpty()))
			.setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
			.setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.setAutoCancel(true)
			.setContentIntent(open)
			.build()
		NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
	}

	companion object {
		const val CHANNEL_ALERTS = "alerts-critical"
		const val CHANNEL_ANNOUNCEMENTS = "announcements"

		/** Yeni, okunmamış ve bildirim gösterilecek türde (acil alarm, duyuru) olanlar. */
		fun selectNew(items: List<InboxItem>, shown: Set<String>): List<InboxItem> =
			items.filter { it.readAt == null && it.id !in shown && (it.category == "ALERT" || it.category == "ANNOUNCEMENT") }

		/** Acil alarm gönderimi ve iptali yüksek öncelikli kanalda; kapanış bildirimi normal kanalda. */
		fun isUrgent(item: InboxItem) = item.category == "ALERT" && item.kind != "ALERT_CLOSED"

		fun ensureChannels(context: Context) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
			val manager = context.getSystemService(NotificationManager::class.java)
			manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts),
				NotificationManager.IMPORTANCE_HIGH).apply {
				description = context.getString(R.string.channel_alerts_description)
				enableVibration(true)
				lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
			})
			manager.createNotificationChannel(NotificationChannel(CHANNEL_ANNOUNCEMENTS, context.getString(R.string.channel_announcements),
				NotificationManager.IMPORTANCE_DEFAULT))
		}
	}
}
