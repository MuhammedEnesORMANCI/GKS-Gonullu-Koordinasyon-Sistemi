package org.gks.android.ui.login

import org.gks.android.ui.theme.GksPalette
import org.gks.android.ui.theme.BrandLogo
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.gks.android.R
import org.gks.android.ui.LoginForm
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.resolve

@Composable
fun LoginScreen(
	form: LoginForm,
	notice: UiText?,
	serverLabel: String?,
	onEmailChange: (String) -> Unit,
	onPasswordChange: (String) -> Unit,
	onSubmit: () -> Unit,
	onRegister: () -> Unit,
	onOpenGuide: () -> Unit = {},
) {
	var passwordVisible by rememberSaveable { mutableStateOf(false) }
	Column(
		modifier = Modifier
			.fillMaxSize()
			.imePadding()
			.verticalScroll(rememberScrollState())
			.padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
			// ADR-023: lacivert başlık (logo + kurum), turuncu internetsiz rehber düğmesi.
			Column(
				Modifier
					.fillMaxWidth()
					.background(GksPalette.Navy, RoundedCornerShape(20.dp))
					.padding(20.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				BrandLogo(height = 48.dp, onDark = true)
				Text(stringResource(R.string.brand_organization), color = Color.White, style = MaterialTheme.typography.bodySmall)
			}
			Spacer(Modifier.height(12.dp))
			EmergencyGuideButton(onOpenGuide)
			Spacer(Modifier.height(24.dp))
			Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)
			Spacer(Modifier.height(4.dp))
			Text(
				stringResource(R.string.login_subtitle),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(Modifier.height(24.dp))

			if (notice != null) {
				MessageCard(notice.resolve(), error = false)
				Spacer(Modifier.height(16.dp))
			}

			OutlinedTextField(
				value = form.email,
				onValueChange = onEmailChange,
				label = { Text(stringResource(R.string.login_email)) },
				singleLine = true,
				enabled = !form.submitting,
				isError = form.emailError != null,
				supportingText = form.emailError?.let { { Text(it.resolve()) } },
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrect = false),
				modifier = Modifier.fillMaxWidth(),
			)
			Spacer(Modifier.height(8.dp))
			OutlinedTextField(
				value = form.password,
				onValueChange = onPasswordChange,
				label = { Text(stringResource(R.string.login_password)) },
				singleLine = true,
				enabled = !form.submitting,
				isError = form.passwordError != null,
				supportingText = form.passwordError?.let { { Text(it.resolve()) } },
				visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
				trailingIcon = {
					TextButton(onClick = { passwordVisible = !passwordVisible }) {
						Text(stringResource(if (passwordVisible) R.string.login_hide_password else R.string.login_show_password))
					}
				},
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrect = false),
				keyboardActions = KeyboardActions(onDone = { onSubmit() }),
				modifier = Modifier.fillMaxWidth(),
			)

			if (form.error != null) {
				Spacer(Modifier.height(8.dp))
				MessageCard(form.error.resolve(), error = true)
			}

			Spacer(Modifier.height(16.dp))
			Button(onClick = onSubmit, enabled = !form.submitting, modifier = Modifier.fillMaxWidth().height(48.dp)) {
				if (form.submitting) {
					CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
				} else {
					Text(stringResource(R.string.login_submit))
				}
			}

			Spacer(Modifier.height(8.dp))
			OutlinedButton(onClick = onRegister, enabled = !form.submitting, modifier = Modifier.fillMaxWidth().height(48.dp)) {
				Text(stringResource(R.string.login_have_code))
			}

			if (serverLabel != null) {
				Spacer(Modifier.height(24.dp))
				Text(
					stringResource(R.string.login_server, serverLabel),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/** Girişten önce internetsiz rehber (afette hesap ya da ağ olmayabilir). */
@Composable
private fun EmergencyGuideButton(onClick: () -> Unit) {
	Button(
		onClick = onClick,
		colors = ButtonDefaults.buttonColors(containerColor = GksPalette.Orange, contentColor = Color.White),
		modifier = Modifier.fillMaxWidth().height(56.dp),
	) {
		Text(stringResource(R.string.login_open_guide), style = MaterialTheme.typography.titleMedium)
	}
}
