package org.gks.android.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Tek satırlı form alanı: hata varsa hata, yoksa yardım metni alanın altında (kayıt, profil, parola formları). */
@Composable
fun FormField(
	value: String,
	@StringRes label: Int,
	error: String?,
	enabled: Boolean,
	keyboard: KeyboardOptions,
	password: Boolean = false,
	@StringRes supporting: Int? = null,
	onChange: (String) -> Unit,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onChange,
		label = { Text(stringResource(label)) },
		singleLine = true,
		enabled = enabled,
		isError = error != null,
		supportingText = (error ?: supporting?.let { stringResource(it) })?.let { { Text(it) } },
		visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
		keyboardOptions = keyboard,
		modifier = Modifier.fillMaxWidth(),
	)
}
