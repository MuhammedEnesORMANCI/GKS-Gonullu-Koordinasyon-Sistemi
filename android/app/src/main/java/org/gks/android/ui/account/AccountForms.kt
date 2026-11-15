package org.gks.android.ui.account

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gks.android.R
import org.gks.android.ui.common.ErrorView
import org.gks.android.ui.common.FormField
import org.gks.android.ui.common.LoadingView
import org.gks.android.ui.common.MessageCard
import org.gks.android.ui.common.resolve

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
	Column(
		modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
	}
}

@Composable
private fun SubmitButton(label: Int, busy: Boolean, onClick: () -> Unit) {
	Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
		if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(label))
	}
}

/** ID-09/10: ad, görünen ad, telefon. E-posta salt okunurdur (`READ_ONLY`). */
@Composable
fun ProfileEditScreen(viewModel: ProfileEditViewModel, onSaved: () -> Unit) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val profile = state.profile
	when {
		state.loading && profile == null -> LoadingView()
		profile == null -> ErrorView(state.error!!, onRetry = viewModel::load)
		else -> FormColumn {
			if (state.saved) MessageCard(stringResource(R.string.profile_saved), error = false)
			state.error?.let { MessageCard(it.resolve(), error = true) }
			OutlinedTextField(
				value = profile.email,
				onValueChange = {},
				enabled = false,
				label = { Text(stringResource(R.string.account_email)) },
				supportingText = { Text(stringResource(R.string.profile_email_readonly)) },
				modifier = Modifier.fillMaxWidth(),
			)
			val enabled = !state.busy
			FormField(state.form.fullName, R.string.register_full_name, state.fieldErrors["fullName"]?.resolve(), enabled,
				KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)) {
				viewModel.edit({ copy(fullName = it) }, "fullName")
			}
			FormField(state.form.displayName, R.string.register_display_name, state.fieldErrors["displayName"]?.resolve(), enabled,
				KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
				supporting = R.string.register_display_name_help) {
				viewModel.edit({ copy(displayName = it) }, "displayName")
			}
			FormField(state.form.phone, R.string.account_phone, state.fieldErrors["phone"]?.resolve(), enabled,
				KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
				supporting = R.string.profile_phone_help) {
				viewModel.edit({ copy(phone = it) }, "phone")
			}
			SubmitButton(R.string.profile_save, state.busy) { viewModel.save(onSaved) }
		}
	}
}

/**
 * ID-08 parola formu: Hesap sekmesinden isteğe bağlı değişiklik ve `mustChangePassword` iken zorunlu değişiklik
 * ([forced]) aynı formu kullanır.
 */
@Composable
fun PasswordChangeScreen(viewModel: PasswordChangeViewModel, forced: Boolean, onChanged: () -> Unit, footer: @Composable () -> Unit = {}) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	FormColumn {
		if (forced) {
			Text(stringResource(R.string.home_password_change_title), style = MaterialTheme.typography.headlineSmall)
		}
		MessageCard(
			stringResource(if (state.done) R.string.password_changed else if (forced) R.string.password_forced_intro else R.string.password_intro),
			error = false,
		)
		state.error?.let { MessageCard(it.resolve(), error = true) }
		val enabled = !state.busy
		val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false, imeAction = ImeAction.Next)
		FormField(state.current, R.string.password_current, state.fieldErrors["currentPassword"]?.resolve(), enabled, keyboard, password = true) {
			viewModel.edit("currentPassword") { copy(current = it) }
		}
		FormField(state.new, R.string.password_new, state.fieldErrors["newPassword"]?.resolve(), enabled, keyboard,
			password = true, supporting = R.string.register_password_help) {
			viewModel.edit("newPassword") { copy(new = it) }
		}
		FormField(state.repeat, R.string.password_repeat, state.fieldErrors["passwordRepeat"]?.resolve(), enabled,
			keyboard.copy(imeAction = ImeAction.Done), password = true) {
			viewModel.edit("passwordRepeat") { copy(repeat = it) }
		}
		SubmitButton(R.string.password_submit, state.busy) { viewModel.submit(onChanged) }
		footer()
	}
}
