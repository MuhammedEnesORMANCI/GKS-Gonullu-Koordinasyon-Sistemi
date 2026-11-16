package org.gks.android.ui.notify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import org.gks.android.R

/**
 * Android 13+ bildirim izni akışı (ADR-021): izin yoksa giriş sonrası bir kez açıklama gösterilir, kullanıcı
 * isterse sistem izni istenir. Reddedilirse uygulama içi bant ve gelen kutusu çalışmaya devam eder; Ayarlar'dan
 * sonradan açılabilir. Android 12 ve öncesinde izin gerekmez.
 */
@Composable
fun NotificationPermissionPrompt() {
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
	val context = LocalContext.current
	var asked by rememberSaveable { mutableStateOf(false) }
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked = true }
	val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
	if (granted || asked) return
	AlertDialog(
		onDismissRequest = { asked = true },
		title = { Text(stringResource(R.string.notification_permission_title)) },
		text = { Text(stringResource(R.string.notification_permission_body)) },
		confirmButton = {
			TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
				Text(stringResource(R.string.notification_permission_allow))
			}
		},
		dismissButton = { TextButton(onClick = { asked = true }) { Text(stringResource(R.string.notification_permission_later)) } },
	)
}
