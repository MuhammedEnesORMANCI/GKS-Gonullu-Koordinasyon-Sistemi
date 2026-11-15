package org.gks.android.ui.register

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.ui.common.FormField
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.formatDateTime
import org.gks.android.ui.common.resolve

@Composable
fun RegistrationScreen(viewModel: RegistrationViewModel, onClose: (registered: Boolean) -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val close = { registered: Boolean ->
		viewModel.reset()
		onClose(registered)
	}
	LaunchedEffect(state.step) {
		if (state.step == RegistrationStep.DONE) close(true)
	}
	BackHandler {
		if (state.step == RegistrationStep.FORM) viewModel.back() else close(false)
	}
	Column(
		modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(stringResource(R.string.register_title), style = MaterialTheme.typography.headlineSmall)

			when (state.step) {
				RegistrationStep.CODE -> CodeStep(state, viewModel)
				RegistrationStep.FORM -> FormStep(state, viewModel)
				RegistrationStep.DONE -> Unit
			}

			state.error?.let { MessageCard(it.resolve(), error = true) }

			val primaryLabel = if (state.step == RegistrationStep.CODE) R.string.action_continue else R.string.register_submit
			if (!state.isOperationInvitation) {
				Button(
					onClick = if (state.step == RegistrationStep.CODE) viewModel::lookup else viewModel::submit,
					enabled = !state.busy,
					modifier = Modifier.fillMaxWidth().height(48.dp),
				) {
					if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(primaryLabel))
				}
			}
			TextButton(onClick = { close(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(R.string.register_back_to_login))
			}
		}
	}
}

@Composable
private fun CodeStep(state: RegistrationState, viewModel: RegistrationViewModel) {
	Text(
		stringResource(R.string.register_code_help),
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	FormField(
		value = state.code,
		label = R.string.register_code,
		error = state.fieldErrors["code"]?.resolve(),
		enabled = !state.busy,
		keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false, imeAction = ImeAction.Done),
		onChange = { value -> viewModel.clearFieldError("code"); viewModel.update { copy(code = value.uppercase().take(9), invitation = null) } },
	)
	if (state.isOperationInvitation) {
		MessageCard(stringResource(R.string.register_operation_invitation, state.invitation?.scopeName.orEmpty()), error = false)
	}
}

@Composable
private fun FormStep(state: RegistrationState, viewModel: RegistrationViewModel) {
	state.invitation?.let { invitation ->
		ElevatedCard(Modifier.fillMaxWidth()) {
			Column(Modifier.padding(16.dp)) {
				Text(invitation.organizationName.orEmpty(), style = MaterialTheme.typography.titleMedium)
				invitation.scopeName?.takeIf { it != invitation.organizationName }?.let {
					Text(it, style = MaterialTheme.typography.bodyMedium)
				}
				Spacer(Modifier.height(4.dp))
				Text(
					stringResource(R.string.home_invitation_expires, formatDateTime(invitation.expiresAt)),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
	val enabled = !state.busy
	FormField(state.fullName, R.string.register_full_name, state.fieldErrors["fullName"]?.resolve(), enabled,
		KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)) {
		viewModel.clearFieldError("fullName"); viewModel.update { copy(fullName = it) }
	}
	FormField(state.displayName, R.string.register_display_name, state.fieldErrors["displayName"]?.resolve(), enabled,
		KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
		supporting = R.string.register_display_name_help) {
		viewModel.clearFieldError("displayName"); viewModel.update { copy(displayName = it) }
	}
	FormField(state.phone, R.string.register_phone, state.fieldErrors["phone"]?.resolve(), enabled,
		KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
		supporting = R.string.register_phone_help) {
		viewModel.clearFieldError("phone"); viewModel.update { copy(phone = it) }
	}
	FormField(state.password, R.string.register_password, state.fieldErrors["password"]?.resolve(), enabled,
		KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Next),
		password = true, supporting = R.string.register_password_help) {
		viewModel.clearFieldError("password"); viewModel.update { copy(password = it) }
	}
	FormField(state.passwordRepeat, R.string.register_password_repeat, state.fieldErrors["passwordRepeat"]?.resolve(), enabled,
		KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Done),
		password = true) {
		viewModel.clearFieldError("passwordRepeat"); viewModel.update { copy(passwordRepeat = it) }
	}
}
