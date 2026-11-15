package org.gks.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.gks.android.AppContainer
import org.gks.android.BuildConfig
import org.gks.android.GksApplication
import org.gks.android.R
import org.gks.android.ui.common.Centered
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.resolve
import org.gks.android.ui.login.LoginScreen
import org.gks.android.ui.mfa.MfaScreen
import org.gks.android.ui.mfa.MfaViewModel
import org.gks.android.ui.register.RegistrationScreen
import org.gks.android.ui.register.RegistrationViewModel
import org.gks.android.ui.theme.GksTheme

class MainActivity : ComponentActivity() {

	private val container: AppContainer get() = (application as GksApplication).container

	private val viewModel: AppViewModel by viewModels {
		viewModelFactory { initializer { AppViewModel(container.authRepository, container.sessions) } }
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// Sunucu adresi yalnızca geliştirme derlemesinde gösterilir (emülatör / gerçek cihaz ayarını görmek için).
		val serverLabel = if (BuildConfig.DEBUG) BuildConfig.API_BASE_URL else null
		setContent {
			GksTheme {
				Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
					AppRoot(viewModel, container, serverLabel)
				}
			}
		}
	}
}

@Composable
private fun AppRoot(viewModel: AppViewModel, container: AppContainer, serverLabel: String?) {
	val screen by viewModel.screen.collectAsStateWithLifecycle()
	val form by viewModel.form.collectAsStateWithLifecycle()
	when (val current = screen) {
		Screen.Starting -> Unit
		is Screen.Login -> LoginScreen(
			form = form,
			notice = current.notice,
			serverLabel = serverLabel,
			onEmailChange = viewModel::onEmailChange,
			onPasswordChange = viewModel::onPasswordChange,
			onSubmit = viewModel::login,
			onRegister = viewModel::openRegistration,
		)
		Screen.Register -> {
			val registration: RegistrationViewModel = viewModel(
				factory = viewModelFactory { initializer { RegistrationViewModel(container.invitationRepository) } },
			)
			RegistrationScreen(registration, onClose = viewModel::closeRegistration)
		}
		is Screen.Mfa -> {
			val mfa: MfaViewModel = viewModel(
				key = "mfa-" + current.ticket.hashCode(),
				factory = viewModelFactory { initializer { MfaViewModel(container.authRepository, current.ticket, current.enrollmentRequired) } },
			)
			MfaScreen(
				mfa,
				onVerified = viewModel::mfaVerified,
				onExpired = { viewModel.mfaCancelled(expired = true) },
				onCancel = { viewModel.mfaCancelled(expired = false) },
			)
		}
		Screen.LoadingContext -> LoadingView(R.string.loading_context)
		is Screen.ContextError -> Centered {
			Text(stringResource(R.string.context_error_title), style = MaterialTheme.typography.titleMedium)
			Spacer(Modifier.height(8.dp))
			Text(current.message.resolve(), textAlign = TextAlign.Center)
			Spacer(Modifier.height(24.dp))
			Button(onClick = viewModel::retryContext) { Text(stringResource(R.string.action_retry)) }
			Spacer(Modifier.height(8.dp))
			OutlinedButton(onClick = viewModel::logout) { Text(stringResource(R.string.action_logout)) }
		}
		is Screen.Main -> MainShell(viewModel, current)
	}
}
