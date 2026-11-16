package org.gks.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.gks.android.GksApplication

/** Bildirimden yanıt, kaydırıp kapatma ve ses zamanlayıcısı (dışa kapalı alıcı). */
class AlarmActionReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		val alertId = intent.getStringExtra(EXTRA_ALERT) ?: return
		val alarms = (context.applicationContext as GksApplication).container.alarms
		val pending = goAsync()
		scope.launch {
			try {
				when (intent.action) {
					ACTION_RESPOND -> intent.getStringExtra(EXTRA_OPTION)?.let { alarms.respondFromNotification(alertId, it) }
					ACTION_DISMISSED -> alarms.onDismissed(alertId)
					ACTION_TICK -> alarms.onTick(alertId)
				}
			}
			finally {
				pending.finish()
			}
		}
	}

	companion object {
		const val ACTION_RESPOND = "org.gks.android.alarm.RESPOND"
		const val ACTION_DISMISSED = "org.gks.android.alarm.DISMISSED"
		const val ACTION_TICK = "org.gks.android.alarm.TICK"
		const val EXTRA_ALERT = "alertId"
		const val EXTRA_OPTION = "option"
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	}
}
