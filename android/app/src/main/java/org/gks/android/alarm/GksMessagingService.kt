package org.gks.android.alarm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.gks.android.GksApplication

/**
 * FCM alıcısı (ADR-022). Sunucu yalnızca data mesajı gönderir; bildirimi [Alarms] kendisi oluşturur. Uygulama
 * kapalıyken ve telefon kilitliyken de çalışır (yüksek öncelikli mesaj süreci uyandırır).
 */
class GksMessagingService : FirebaseMessagingService() {

	private val container get() = (application as GksApplication).container

	override fun onMessageReceived(message: RemoteMessage) {
		MessagePush.fromData(message.data)?.let { push ->
			Alarms.ensureChannels(this)
			runBlocking { container.messageNotifier.onPush(push) }
			return
		}
		val incoming = Incoming.fromData(message.data) ?: return
		Alarms.ensureChannels(this)
		// onMessageReceived kendi arka plan iş parçacığındadır; bildirim çizilene kadar beklenir (süreç erken ölmesin).
		runBlocking { container.alarms.onIncoming(incoming) }
	}

	override fun onNewToken(token: String) {
		runBlocking { container.devices.sync(token) }
	}
}
