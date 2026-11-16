package org.gks.android.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.gks.android.BuildConfig
import org.gks.android.R
import org.gks.android.data.AlertRepository
import org.gks.android.data.ApiResult
import org.gks.android.data.problem
import org.gks.android.data.remote.MyAlertView
import org.gks.android.ui.MainActivity

/**
 * Acil alarm ve duyuru bildirimleri (ADR-022). FCM data mesajı ve SSE aynı [Incoming] olayını verir; olay kimliğiyle
 * tekilleştirilir. Acil alarm:
 * - kalıcıdır (ongoing) ve çözülene/iptal edilene kadar durur; kaydırılıp kapatılırsa yeniden gösterilir;
 * - yanıt gelene kadar ısrarlı çalar (FLAG_INSISTENT, alarm ses akışı: sessiz modda da duyulur), titreşir;
 *   [AlarmPolicy.insistentMillis] sonra ısrarlı ses durur, yanıt yoksa [AlarmPolicy.reminderMillis]'te bir kısa hatırlatma;
 * - bildirimden yanıt verilebilir; yanıt sesi durdurur, bildirim "Yanıtınız iletildi" olur;
 * - kilit ekranında yalnızca başlık görünür (VISIBILITY_PRIVATE + genel sürüm); izin varsa tam ekran açılır;
 * - kapatma/iptalde aynı bildirim (aynı kimlik) güncellenir, ses durur ve bildirim kapatılabilir hale gelir.
 * Uygulamayı açmak tek başına sesi durdurmaz; uygulama içinden [mute] ile susturulur.
 */
class Alarms(
	private val context: Context,
	private val alerts: AlertRepository,
	val policy: AlarmPolicy = AlarmPolicy(BuildConfig.ALERT_INSISTENT_MINUTES * 60_000L, BuildConfig.ALERT_REMINDER_MINUTES * 60_000L),
	private val clock: () -> Long = System::currentTimeMillis,
) {

	private val prefs = context.getSharedPreferences("gks_alarms", Context.MODE_PRIVATE)
	private val mutex = Mutex()
	private val notifications get() = NotificationManagerCompat.from(context)

	// ------------------------------------------------------------------ olaylar

	/** @return olay yeni olduysa `true` (aynı olay ikinci yoldan gelirse `false`, bildirim üretilmez) */
	suspend fun onIncoming(incoming: Incoming): Boolean = mutex.withLock {
		if (!markSeen(incoming.notificationId)) return@withLock false
		when {
			incoming.isAlert && incoming.kind == "ALERT_SENT" -> {
				val state = policy.onSent(load(incoming.alertId!!), incoming.alertId, incoming.title, incoming.body, clock())
				save(state)
				render(state, if (state.phase == AlarmPhase.RINGING) Sound.INSISTENT else Sound.SILENT, fullScreen = true)
				schedule(state)
			}
			incoming.isAlert && (incoming.kind == "ALERT_CANCELLED" || incoming.kind == "ALERT_CLOSED") ->
				resolveLocked(incoming.alertId!!, cancelled = incoming.kind == "ALERT_CANCELLED", fallbackTitle = incoming.title)
			incoming.category == "ANNOUNCEMENT" -> showAnnouncement(incoming)
		}
		true
	}

	/** Gelen kutusunda zaten olan kayıtları "görüldü" say (akış başlarken eski kayıtlar bildirim üretmez). */
	suspend fun markAllSeen(ids: Collection<String>) = mutex.withLock { ids.forEach { markSeen(it) } }

	/** Uygulamadan verilen yanıt: ses durur, bildirim kalır. */
	suspend fun responded(alertId: String, option: String) = mutex.withLock {
		val state = load(alertId) ?: return@withLock
		update(policy.onResponded(state, option))
	}

	/** Uygulama içi "sesi sustur". */
	suspend fun mute(alertId: String) = mutex.withLock {
		val state = load(alertId) ?: return@withLock
		update(policy.onMuted(state))
	}

	fun isSounding(alertId: String) = load(alertId)?.phase?.sounding == true

	/** Bildirimdeki yanıt düğmesi (AL-15): alıcı kaydı sunucudan okunur, yanıt gönderilir. */
	suspend fun respondFromNotification(alertId: String, option: String) {
		val alert = (alerts.alert(alertId) as? ApiResult.Success)?.value
		if (alert == null || alert.state != "OPEN") {
			alert?.let { mutex.withLock { resolveLocked(alertId, it.state == "CANCELLED", null) } }
			return
		}
		when (val result = alerts.respond(alert.recipient.id, option)) {
			is ApiResult.Success -> responded(alertId, option)
			is ApiResult.Failure -> mutex.withLock {
				val state = load(alertId) ?: return@withLock
				render(state, Sound.SILENT, note = context.getString(R.string.alarm_response_failed))
				if (result.error.problem?.code == "ALERT_NOT_OPEN") resolveLocked(alertId, cancelled = false, fallbackTitle = null)
			}
		}
	}

	/** Kaydırıp kapatma (Android 14+): alarm çözülmediyse aynı bildirim sessizce yeniden gösterilir. */
	suspend fun onDismissed(alertId: String) = mutex.withLock {
		val state = load(alertId)
		if (policy.shouldRepostOnDismiss(state)) render(state!!, if (state.phase == AlarmPhase.RINGING) Sound.INSISTENT else Sound.SILENT)
	}

	/** Zamanlayıcı: ısrarlı süre dolunca ses durur; hatırlatma aşamasında kısa bir kez çalar. */
	suspend fun onTick(alertId: String) = mutex.withLock {
		val state = load(alertId) ?: return@withLock
		val next = policy.onTick(state, clock())
		save(next)
		when {
			state.phase == AlarmPhase.RINGING && next.phase == AlarmPhase.REMINDING -> render(next, Sound.SILENT)
			next.phase == AlarmPhase.REMINDING -> render(next, Sound.ONCE)
		}
		schedule(next)
	}

	/**
	 * Sunucuyla eşitleme (uygulama açılınca / bağlantı gelince, açık alarm listesi her yenilendiğinde): çevrimdışıyken
	 * kaçan kapanış/iptal uygulanır; bildirimi hiç gelmemiş açık alarm (yanıtlanmamışsa) çalmaya başlar.
	 */
	suspend fun reconcile(open: List<MyAlertView>) {
		val openIds = open.map { it.id }.toSet()
		mutex.withLock {
			for (alert in open) {
				val existing = load(alert.id)
				if (existing == null) {
					val title = if (alert.scope == "GENERAL") context.getString(R.string.general_alert_title)
					else context.getString(R.string.alert_title, alert.operationName.orEmpty())
					val responded = alert.recipient.responseOption
					val state = AlarmState(alert.id, title, alert.message,
						if (responded == null) AlarmPhase.RINGING else AlarmPhase.RESPONDED, clock(), responded)
					save(state)
					render(state, if (responded == null) Sound.INSISTENT else Sound.SILENT, fullScreen = responded == null)
					schedule(state)
				}
				else if (alert.recipient.responseOption != null && existing.phase.sounding) {
					update(policy.onResponded(existing, alert.recipient.responseOption))
				}
			}
		}
		for (alertId in activeIds() - openIds) {
			val current = alerts.alert(alertId)
			mutex.withLock {
				when (current) {
					is ApiResult.Success -> if (current.value.state != "OPEN") resolveLocked(alertId, current.value.state == "CANCELLED", null)
					is ApiResult.Failure -> if (current.error.problem?.status in setOf(403, 404)) forget(alertId)
				}
			}
		}
	}

	// ------------------------------------------------------------------ çizim

	private enum class Sound { INSISTENT, ONCE, SILENT }

	private fun update(state: AlarmState) {
		save(state)
		render(state, Sound.SILENT)
		cancelTick(state.alertId)
	}

	private fun resolveLocked(alertId: String, cancelled: Boolean, fallbackTitle: String?) {
		val state = load(alertId) ?: AlarmState(alertId, fallbackTitle.orEmpty(), null, AlarmPhase.RESPONDED, clock())
		val resolved = policy.onResolved(state, cancelled)
		save(resolved)
		render(resolved, Sound.SILENT)
		cancelTick(alertId)
	}

	private fun render(state: AlarmState, sound: Sound, fullScreen: Boolean = false, note: String? = null) {
		if (!notifications.areNotificationsEnabled()) return
		val resolved = state.phase.resolved
		val title = when (state.phase) {
			AlarmPhase.CANCELLED -> context.getString(R.string.alarm_cancelled_title)
			AlarmPhase.CLOSED -> context.getString(R.string.alarm_closed_title)
			else -> state.title.ifBlank { context.getString(R.string.alarm_public_title) }
		}
		val text = note ?: when (state.phase) {
			AlarmPhase.RESPONDED -> context.getString(R.string.alarm_responded_text, responseLabel(state.response))
			AlarmPhase.CANCELLED, AlarmPhase.CLOSED -> state.body.orEmpty()
			else -> state.body.orEmpty()
		}
		val open = openIntent("alert:${state.alertId}", state.alertId.hashCode())
		val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
			.setSmallIcon(android.R.drawable.ic_dialog_alert)
			.setContentTitle(title)
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setCategory(NotificationCompat.CATEGORY_ALARM)
			.setPriority(NotificationCompat.PRIORITY_MAX)
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ALERTS)
				.setSmallIcon(android.R.drawable.ic_dialog_alert)
				.setContentTitle(if (resolved) title else context.getString(R.string.alarm_public_title))
				.setContentText(context.getString(R.string.alarm_public_text))
				.build())
			.setContentIntent(open)
			.setOngoing(!resolved)
			.setAutoCancel(resolved)
			.setOnlyAlertOnce(false)
			.setSilent(sound == Sound.SILENT)
			.setDeleteIntent(actionIntent(AlarmActionReceiver.ACTION_DISMISSED, state.alertId, null))
		if (!resolved && state.phase != AlarmPhase.RESPONDED) {
			RESPONSE_OPTIONS.forEach { option ->
				builder.addAction(0, responseLabel(option), actionIntent(AlarmActionReceiver.ACTION_RESPOND, state.alertId, option))
			}
		}
		if (fullScreen && state.phase == AlarmPhase.RINGING && canUseFullScreen()) builder.setFullScreenIntent(open, true)
		val notification = builder.build()
		if (sound == Sound.INSISTENT) notification.flags = notification.flags or Notification.FLAG_INSISTENT
		post(state.alertId.hashCode(), notification)
	}

	private fun showAnnouncement(incoming: Incoming) {
		if (!notifications.areNotificationsEnabled()) return
		val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setContentTitle(incoming.title)
			.setContentText(incoming.body.orEmpty())
			.setStyle(NotificationCompat.BigTextStyle().bigText(incoming.body.orEmpty()))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setCategory(NotificationCompat.CATEGORY_MESSAGE)
			.setAutoCancel(true)
			.setContentIntent(openIntent(incoming.inboxItemId?.let { "inbox:$it" } ?: "inbox", incoming.notificationId.hashCode()))
			.build()
		post(incoming.notificationId.hashCode(), notification)
	}

	@android.annotation.SuppressLint("MissingPermission")   // areNotificationsEnabled denetlendi
	private fun post(id: Int, notification: Notification) {
		try {
			notifications.notify(id, notification)
		}
		catch (ignored: SecurityException) {
			// Android 13+ izin yok: uygulama içi bant ve gelen kutusu yine gösterir.
		}
	}

	private fun responseLabel(option: String?) = when (option) {
		"NEED_HELP" -> context.getString(R.string.alert_response_need_help)
		"ON_MY_WAY" -> context.getString(R.string.alert_response_on_my_way)
		else -> context.getString(R.string.alert_response_received)
	}

	private fun canUseFullScreen(): Boolean =
		Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

	private fun openIntent(target: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(context, requestCode,
		Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, target)
			.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
		PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

	private fun actionIntent(action: String, alertId: String, option: String?): PendingIntent {
		val intent = Intent(context, AlarmActionReceiver::class.java).setAction(action)
			.putExtra(AlarmActionReceiver.EXTRA_ALERT, alertId).putExtra(AlarmActionReceiver.EXTRA_OPTION, option)
		return PendingIntent.getBroadcast(context, (action + alertId + option).hashCode(), intent,
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
	}

	// ------------------------------------------------------------------ zamanlayıcı

	private fun schedule(state: AlarmState) {
		val at = policy.nextTick(state, clock()) ?: return cancelTick(state.alertId)
		val manager = context.getSystemService(AlarmManager::class.java)
		manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, actionIntent(AlarmActionReceiver.ACTION_TICK, state.alertId, null))
	}

	private fun cancelTick(alertId: String) {
		context.getSystemService(AlarmManager::class.java).cancel(actionIntent(AlarmActionReceiver.ACTION_TICK, alertId, null))
	}

	// ------------------------------------------------------------------ kalıcı durum

	private fun markSeen(id: String): Boolean {
		val seen = RecentIds(serialized = prefs.getString(KEY_SEEN, null))
		val added = seen.add(id)
		if (added) prefs.edit().putString(KEY_SEEN, seen.serialize()).apply()
		return added
	}

	private fun activeIds(): Set<String> = prefs.all.keys.filter { it.startsWith(KEY_STATE) }
		.map { it.removePrefix(KEY_STATE) }.filter { load(it)?.phase?.resolved == false }.toSet()

	private fun load(alertId: String): AlarmState? = prefs.getString(KEY_STATE + alertId, null)?.let { raw ->
		runCatching {
			val o = JSONObject(raw)
			AlarmState(alertId, o.optString("title"), o.optString("body").ifEmpty { null }, AlarmPhase.valueOf(o.getString("phase")),
				o.getLong("startedAt"), o.optString("response").ifEmpty { null })
		}.getOrNull()
	}

	private fun save(state: AlarmState) {
		val o = JSONObject().put("title", state.title).put("body", state.body ?: "").put("phase", state.phase.name)
			.put("startedAt", state.startedAt).put("response", state.response ?: "")
		prefs.edit().putString(KEY_STATE + state.alertId, o.toString()).apply()
	}

	private fun forget(alertId: String) {
		prefs.edit().remove(KEY_STATE + alertId).apply()
		cancelTick(alertId)
		notifications.cancel(alertId.hashCode())
	}

	companion object {
		/** Kanal ayarları sonradan değiştirilemediği için yeni kimlik (alarm sesi + Rahatsız Etmeyin); eski kanal silinir. */
		const val CHANNEL_ALERTS = "alerts-critical-v2"
		const val CHANNEL_ANNOUNCEMENTS = "announcements"
		const val CHANNEL_MESSAGES = "messages"
		private const val OLD_CHANNEL_ALERTS = "alerts-critical"
		const val EXTRA_OPEN = "gks.open"
		private const val KEY_SEEN = "seen"
		private const val KEY_STATE = "state:"
		val RESPONSE_OPTIONS = listOf("RECEIVED", "NEED_HELP", "ON_MY_WAY")

		fun ensureChannels(context: Context) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
			val manager = context.getSystemService(NotificationManager::class.java)
			manager.deleteNotificationChannel(OLD_CHANNEL_ALERTS)
			manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts),
				NotificationManager.IMPORTANCE_HIGH).apply {
				description = context.getString(R.string.channel_alerts_description)
				setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
				enableVibration(true)
				vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
				setBypassDnd(true)   // yalnızca kullanıcı Rahatsız Etmeyin erişimi verdiyse geçerlidir
				lockscreenVisibility = Notification.VISIBILITY_PRIVATE
			})
			manager.createNotificationChannel(NotificationChannel(CHANNEL_ANNOUNCEMENTS, context.getString(R.string.channel_announcements),
				NotificationManager.IMPORTANCE_DEFAULT))
			// ADR-024: mesajlar normal öncelik; kanal başına gruplanır.
			manager.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages),
				NotificationManager.IMPORTANCE_DEFAULT))
		}
	}
}
