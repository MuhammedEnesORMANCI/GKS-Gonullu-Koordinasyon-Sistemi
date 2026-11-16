package org.gks.android.ui.location

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.location.LocationSharingService
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve

/**
 * AND-06: konum paylaşımı kartı (yalnızca katılımcı, yalnızca aktif operasyon). İzin akışı: konum (ince/kaba) ve
 * Android 13+'ta bildirim; izin verilmezse paylaşım başlatılmaz. Kimin gördüğü ve saklama süresi kartta yazılıdır.
 */
@Composable
fun LocationSharingCard(viewModel: LocationSharingViewModel, operationId: String) {
	val context = LocalContext.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
		val location = LocationSharingService.PERMISSIONS.any { granted[it] == true } || LocationSharingService.hasPermission(context)
		if (location) viewModel.start { LocationSharingService.start(context, operationId) } else viewModel.permissionDenied()
	}
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleMedium)
			Text(stringResource(R.string.location_explanation), style = MaterialTheme.typography.bodyMedium)
			if (state.sharing) {
				Text(stringResource(if (state.unavailable) R.string.location_state_unavailable else R.string.location_state_active),
					color = MaterialTheme.colorScheme.primary)
				state.lastSentAt?.let { Text(stringResource(R.string.location_last_sent, formatDateTime(it)), style = MaterialTheme.typography.bodySmall) }
				OutlinedButton(
					onClick = { viewModel.stop { context.startService(LocationSharingService.stopIntent(context, operationId)) } },
					enabled = !state.busy,
					modifier = Modifier.fillMaxWidth().height(48.dp),
				) { Text(stringResource(R.string.location_stop)) }
			} else {
				Button(
					onClick = {
						val permissions = LocationSharingService.PERMISSIONS.toMutableList()
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
						launcher.launch(permissions.toTypedArray())
					},
					enabled = !state.busy,
					modifier = Modifier.fillMaxWidth().height(48.dp),
				) { Text(stringResource(R.string.location_start)) }
			}
			state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
		}
	}
}
