package org.gks.android.ui.notify

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.gks.android.R

/** Acil alarm için gereken izinlerin durumu (ADR-022). */
data class AlarmPermissions(val notifications: Boolean, val fullScreen: Boolean, val dndAccess: Boolean) {
	val allGranted get() = notifications && fullScreen && dndAccess

	companion object {
		fun of(context: Context): AlarmPermissions {
			val manager = context.getSystemService(NotificationManager::class.java)
			return AlarmPermissions(
				notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
				fullScreen = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent(),
				dndAccess = manager.isNotificationPolicyAccessGranted,
			)
		}

		fun fullScreenSettings(context: Context): Intent =
			if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
			else appNotificationSettings(context)

		fun dndSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

		fun appNotificationSettings(context: Context): Intent =
			Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
	}
}

/**
 * Android 14+: tam ekran alarm ve Rahatsız Etmeyin erişimi için bir kez açıklama (ADR-022). Reddedilirse uygulama
 * çalışır; durum "Hesap" sekmesinde görünür ve oradan ayarlar açılabilir.
 */
@Composable
fun AlarmPermissionPrompt() {
	if (Build.VERSION.SDK_INT < 34) return
	val context = LocalContext.current
	val prefs = remember { context.getSharedPreferences("gks_alarm_permissions", Context.MODE_PRIVATE) }
	var asked by remember { mutableStateOf(prefs.getBoolean("asked", false)) }
	val permissions = remember { AlarmPermissions.of(context) }
	if (asked || (permissions.fullScreen && permissions.dndAccess) || !permissions.notifications) return
	fun done() {
		prefs.edit().putBoolean("asked", true).apply()
		asked = true
	}
	AlertDialog(
		onDismissRequest = ::done,
		title = { Text(stringResource(R.string.alarm_permissions_title)) },
		text = { Text(stringResource(R.string.alarm_permissions_body)) },
		confirmButton = {
			TextButton(onClick = {
				done()
				if (!permissions.fullScreen) context.startActivity(AlarmPermissions.fullScreenSettings(context))
				else context.startActivity(AlarmPermissions.dndSettings())
			}) { Text(stringResource(R.string.alarm_permissions_open)) }
		},
		dismissButton = { TextButton(onClick = ::done) { Text(stringResource(R.string.notification_permission_later)) } },
	)
}

/** "Hesap" sekmesindeki izin durumu; ekrana her dönüşte yenilenir. */
@Composable
fun AlarmPermissionStatus() {
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	var tick by remember { mutableIntStateOf(0) }
	LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ } }
	val status = remember(tick) { AlarmPermissions.of(context) }
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.alarm_permissions_title), style = MaterialTheme.typography.titleMedium)
			PermissionRow(R.string.alarm_permission_notifications, status.notifications) {
				context.startActivity(AlarmPermissions.appNotificationSettings(context))
			}
			if (Build.VERSION.SDK_INT >= 34) {
				PermissionRow(R.string.alarm_permission_full_screen, status.fullScreen) {
					context.startActivity(AlarmPermissions.fullScreenSettings(context))
				}
			}
			PermissionRow(R.string.alarm_permission_dnd, status.dndAccess) { context.startActivity(AlarmPermissions.dndSettings()) }
			Text(stringResource(R.string.alarm_permissions_note), style = MaterialTheme.typography.bodySmall)
		}
	}
}

@Composable
private fun PermissionRow(label: Int, granted: Boolean, open: () -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
		Text(stringResource(label) + ": " + stringResource(if (granted) R.string.alarm_permission_on else R.string.alarm_permission_off),
			modifier = Modifier.weight(1f))
		TextButton(onClick = open) { Text(stringResource(R.string.alarm_permissions_settings)) }
	}
}
