package org.gks.android.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.StateFlow
import org.gks.android.R
import org.gks.android.data.ApiResult
import org.gks.android.data.messaging.MessagingRepository
import org.gks.android.ui.MainActivity

/** FCM mesaj yükü (ADR-024): yalnızca kanal ve mesaj kimliği; içerik uygulamada çekilir. */
data class MessagePush(val channelId: String, val messageId: String?) {
	companion object {
		fun fromData(data: Map<String, String>): MessagePush? {
			if (data["type"] != "MESSAGE") return null
			val channel = data["channelId"] ?: return null
			return MessagePush(channel, data["messageId"])
		}

		/** Açık olan kanal için bildirim gösterilmez. */
		fun shouldNotify(openChannelId: String?, channelId: String): Boolean = openChannelId != channelId
	}
}

/**
 * Mesaj bildirimi: aynı kanaldan gelenler tek bildirimde toplanır (kanal başına sabit kimlik, satırlar birikir).
 * İçerik çekilemezse "Yeni mesaj" yazar. Sessize alınan kanallara sunucu push göndermez.
 */
class MessageNotifier(
	private val context: Context,
	private val repository: MessagingRepository,
	private val openChannel: StateFlow<String?>,
) {
	private val lines = mutableMapOf<String, MutableList<String>>()

	suspend fun onPush(push: MessagePush) {
		if (!MessagePush.shouldNotify(openChannel.value, push.channelId)) return
		val notifications = NotificationManagerCompat.from(context)
		if (!notifications.areNotificationsEnabled()) return
		val line = when (val page = repository.messages(push.channelId, limit = 5)) {
			is ApiResult.Success -> page.value.items.firstOrNull { it.id == push.messageId }
				?: page.value.items.firstOrNull { !it.mine && it.state == "VISIBLE" }
			is ApiResult.Failure -> null
		}?.takeIf { it.state == "VISIBLE" && it.body != null }?.let { m ->
			listOfNotNull(m.sender?.displayName?.takeIf { it.isNotBlank() }, m.body).joinToString(": ")
		} ?: context.getString(R.string.message_new)
		val history = synchronized(lines) { lines.getOrPut(push.channelId) { mutableListOf() }.apply { add(line); while (size > 5) removeAt(0) }.toList() }
		val style = NotificationCompat.InboxStyle()
		history.forEach { style.addLine(it) }
		val notification = NotificationCompat.Builder(context, Alarms.CHANNEL_MESSAGES)
			.setSmallIcon(android.R.drawable.sym_action_chat)
			.setContentTitle(context.getString(R.string.message_new))
			.setContentText(line)
			.setStyle(style)
			.setNumber(history.size)
			.setGroup(GROUP)
			.setCategory(NotificationCompat.CATEGORY_MESSAGE)
			.setAutoCancel(true)
			.setContentIntent(PendingIntent.getActivity(context, push.channelId.hashCode(),
				Intent(context, MainActivity::class.java).putExtra(Alarms.EXTRA_OPEN, "channel:${push.channelId}")
					.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
				PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
			.build()
		try {
			@Suppress("MissingPermission")
			notifications.notify(push.channelId.hashCode(), notification)
		}
		catch (ignored: SecurityException) {
		}
	}

	/** Kanal açılınca o kanalın bildirimi ve biriken satırlar temizlenir. */
	fun clear(channelId: String) {
		synchronized(lines) { lines.remove(channelId) }
		NotificationManagerCompat.from(context).cancel(channelId.hashCode())
	}

	companion object {
		const val GROUP = "gks.messages"
	}
}
