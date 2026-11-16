package org.gks.android.ui.notify

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.gks.android.alarm.Alarms
import org.gks.android.alarm.Incoming
import org.gks.android.data.ApiResult
import org.gks.android.data.NotificationRepository
import org.gks.android.data.realtime.RealtimeStream
import org.gks.android.data.remote.InboxItem

/**
 * SSE yolu (ADR-021/022): akıştan `inbox.changed` gelince gelen kutusu okunur, yeni acil alarm ve duyuru kayıtları
 * [Alarms]'a verilir. FCM ile aynı olay gelirse [Alarms] olay kimliğiyle tekilleştirir (çift bildirim yok). Akış
 * başlarken mevcut kayıtlar "görüldü" sayılır; açık ve yanıtlanmamış alarmlar ise açık alarm eşitlemesiyle çalar.
 */
class LiveNotifier(
	private val context: Context,
	private val realtime: RealtimeStream,
	private val repository: NotificationRepository,
	private val alarms: Alarms,
	private val scope: CoroutineScope,
) {

	private val mutex = Mutex()
	private var baselined = false
	private var job: Job? = null

	@Synchronized
	fun start() {
		if (job != null) return
		Alarms.ensureChannels(context)
		job = scope.launch {
			realtime.events.collect { event ->
				if (event.type == "inbox.changed" && event.data.contains("\"CREATED\"") || event.type == "resync") check()
			}
		}
		scope.launch { check() }
	}

	private suspend fun check() = mutex.withLock {
		val page = (repository.page(null) as? ApiResult.Success)?.value ?: return@withLock
		val incoming = selectNew(page.items).mapNotNull(Incoming::fromInbox)
		if (!baselined) {
			alarms.markAllSeen(page.items.mapNotNull { it.notificationId })
			baselined = true
			return@withLock
		}
		// Eskiden yeniye: aynı alarmın gönderim ve iptali sırayla işlensin.
		incoming.reversed().forEach { alarms.onIncoming(it) }
	}

	companion object {
		/** Okunmamış acil alarm ve duyuru kayıtları (alarm iptal/kapanışı da okunmamış ALERT kaydıdır). */
		fun selectNew(items: List<InboxItem>): List<InboxItem> =
			items.filter { it.readAt == null && (it.category == "ALERT" || it.category == "ANNOUNCEMENT") }

		fun isUrgent(item: InboxItem) = item.category == "ALERT" && item.kind != "ALERT_CLOSED"
	}
}
