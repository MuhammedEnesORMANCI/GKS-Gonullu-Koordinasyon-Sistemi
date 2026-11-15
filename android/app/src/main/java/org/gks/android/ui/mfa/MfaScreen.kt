package org.gks.android.ui.mfa

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.resolve

/** Gizli anahtar panoya hassas olarak işaretlenip kopyalanır (Android 13+ önizlemede gizler). */
private fun Context.copySensitive(secret: String) {
	val clip = ClipData.newPlainText("TOTP", secret)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
	}
	getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

/** Ekran kilidi (PIN/desen/biyometri) var mı: yönetim oturumu kilitsiz cihazda açılmaz (ADR-014). */
fun Context.isDeviceSecure(): Boolean = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

@Composable
fun MfaScreen(viewModel: MfaViewModel, onVerified: () -> Unit, onExpired: () -> Unit, onCancel: () -> Unit) {
	val context = LocalContext.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	BackHandler(onBack = onCancel)
	Column(
		modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) content@{
			Text(stringResource(R.string.mfa_title), style = MaterialTheme.typography.headlineSmall)
			if (!context.isDeviceSecure()) {
				// Kilitsiz cihazda telefonu alan kişi 12 saat yönetim yetkisine sahip olurdu.
				MessageCard(stringResource(R.string.mfa_device_not_secure), error = true)
				OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.register_back_to_login)) }
				return@content
			}
			Text(stringResource(R.string.mfa_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			state.error?.let { MessageCard(it.resolve(), error = true) }

			if (state.enrolling) {
				CircularProgressIndicator()
			}
			state.enrollment?.let { enrollment -> EnrollmentCard(enrollment.secret, enrollment.otpauthUri, context) }

			OutlinedTextField(
				value = state.code,
				onValueChange = viewModel::onCode,
				label = { Text(stringResource(R.string.mfa_code)) },
				singleLine = true,
				enabled = !state.busy,
				isError = state.codeError != null,
				supportingText = { Text(state.codeError?.resolve() ?: stringResource(R.string.mfa_code_help)) },
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
				keyboardActions = KeyboardActions(onDone = { viewModel.verify(onVerified, onExpired) }),
				modifier = Modifier.fillMaxWidth(),
			)
			Button(
				onClick = { viewModel.verify(onVerified, onExpired) },
				enabled = !state.busy && !state.enrolling,
				modifier = Modifier.fillMaxWidth().height(48.dp),
			) {
				if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.mfa_verify))
			}
			Text(stringResource(R.string.mfa_session_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			TextButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.register_back_to_login))
			}
		}
	}
}

@Composable
private fun EnrollmentCard(secret: String, otpauthUri: String, context: Context) {
	var noApp by remember { mutableStateOf(false) }
	ElevatedCard(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(R.string.mfa_enroll_title), style = MaterialTheme.typography.titleMedium)
			Text(stringResource(R.string.mfa_enroll_body), style = MaterialTheme.typography.bodyMedium)
			SelectionContainer {
				Text(secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
			}
			OutlinedButton(onClick = {
				try {
					context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(otpauthUri)))
				} catch (e: ActivityNotFoundException) {
					noApp = true
				}
			}, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mfa_open_authenticator)) }
			OutlinedButton(onClick = { context.copySensitive(secret) }, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.mfa_copy_secret))
			}
			if (noApp) MessageCard(stringResource(R.string.mfa_no_authenticator), error = true)
			MessageCard(stringResource(R.string.mfa_same_device_warning), error = false)
		}
	}
}
