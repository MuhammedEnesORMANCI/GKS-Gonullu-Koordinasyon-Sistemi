package org.gks.android.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.gks.android.R
import org.gks.android.data.ApiError
import org.gks.android.data.remote.ErrorCodes

/** Ekranda gösterilecek metin: kaynak kimliği veya sunucunun Türkçe alan iletisi. */
sealed interface UiText {
	data class Res(@StringRes val id: Int) : UiText
	data class Raw(val text: String) : UiText
	/** Biçimli kaynak metni (ör. "%1$d kişi eklendi: %2$s"). */
	data class Fmt(@StringRes val id: Int, val args: List<Any>) : UiText
}

@Composable
fun UiText.resolve(): String = when (this) {
	is UiText.Res -> stringResource(id)
	is UiText.Raw -> text
	is UiText.Fmt -> stringResource(id, *args.toTypedArray())
}

fun text(@StringRes id: Int): UiText = UiText.Res(id)

/**
 * Hata → kullanıcı iletisi. Önce eyleme özgü [specific] eşlemesi denenir, sonra ortak kodlar.
 * `400 VALIDATION_FAILED` için sunucunun alan iletisi (backend Türkçe yazar) gösterilir.
 */
fun ApiError.toUiText(specific: Map<String, Int> = emptyMap()): UiText = when (this) {
	ApiError.Network -> text(R.string.error_network)
	ApiError.RefreshUnavailable -> text(R.string.error_refresh_unavailable)
	ApiError.Unexpected -> text(R.string.error_unexpected)
	is ApiError.Http -> {
		val code = problem.code
		when {
			code != null && code in specific -> text(specific.getValue(code))
			code == ErrorCodes.VALIDATION_FAILED ->
				problem.errors.firstOrNull()?.message?.let { UiText.Raw(it) } ?: text(R.string.error_validation)
			code == "NOT_FOUND" -> text(R.string.error_not_found)
			code == "FORBIDDEN" -> text(R.string.error_forbidden)
			code == "CLIENT_NOT_ALLOWED" -> text(R.string.error_client_not_allowed)
			code == "OPERATION_CLOSED" -> text(R.string.error_operation_closed)
			code == "AUTH_PASSWORD_CHANGE_REQUIRED" -> text(R.string.home_password_change_title)
			problem.status == 429 -> text(R.string.error_too_many_requests)
			problem.status >= 500 -> text(R.string.error_service_unavailable)
			else -> text(R.string.error_unexpected)
		}
	}
}

/** Giriş ekranına özgü kodlar. */
val LOGIN_ERRORS = mapOf(
	ErrorCodes.AUTH_INVALID_CREDENTIALS to R.string.error_invalid_credentials,
	ErrorCodes.ACCOUNT_LOCKED to R.string.error_account_locked,
	ErrorCodes.ACCOUNT_DISABLED to R.string.error_account_disabled,
)
