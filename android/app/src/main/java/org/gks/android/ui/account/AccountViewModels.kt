package org.gks.android.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.gks.android.R
import org.gks.android.data.ApiError
import org.gks.android.data.ApiResult
import org.gks.android.data.AuthRepository
import org.gks.android.data.problem
import org.gks.android.data.remote.MeProfile
import org.gks.android.domain.ProfileForm
import org.gks.android.domain.profileChanges
import org.gks.android.ui.common.LoadViewModel
import org.gks.android.ui.common.UiText
import org.gks.android.ui.common.text
import org.gks.android.ui.common.toUiText

/** Sunucunun `400 VALIDATION_FAILED` alan iletileri (backend Türkçe yazar) → alan adı → ileti. */
fun ApiError.fieldErrors(fields: Set<String>): Map<String, UiText> =
	problem?.errors.orEmpty()
		.filter { it.field in fields && it.message != null }
		.associate { it.field!! to UiText.Raw(it.message!!) }

/** ID-09: Hesap sekmesi. Sekmeye her dönüşte yeniden okunur (profil düzenlemesinden sonra güncel kalsın). */
class AccountViewModel(private val auth: AuthRepository) : LoadViewModel<MeProfile>() {
	override suspend fun fetch() = auth.profile()
}

data class ProfileEditState(
	val profile: MeProfile? = null,
	val form: ProfileForm = ProfileForm("", "", ""),
	val loading: Boolean = true,
	val busy: Boolean = false,
	val fieldErrors: Map<String, UiText> = emptyMap(),
	val error: UiText? = null,
	val saved: Boolean = false,
)

/** ID-09 + ID-10. */
class ProfileEditViewModel(private val auth: AuthRepository) : ViewModel() {

	private val _state = MutableStateFlow(ProfileEditState())
	val state: StateFlow<ProfileEditState> = _state.asStateFlow()

	init { load() }

	fun load() {
		_state.update { it.copy(loading = true, error = null) }
		viewModelScope.launch {
			_state.update {
				when (val result = auth.profile()) {
					is ApiResult.Success -> it.copy(loading = false, profile = result.value, form = ProfileForm.of(result.value))
					is ApiResult.Failure -> it.copy(loading = false, error = result.error.toUiText())
				}
			}
		}
	}

	fun edit(transform: ProfileForm.() -> ProfileForm, field: String) =
		_state.update { it.copy(form = it.form.transform(), fieldErrors = it.fieldErrors - field, error = null, saved = false) }

	/** @param onSaved bağlam yeniden çekilir (görünen ad ana sayfada ve gruplarda kullanılır) */
	fun save(onSaved: () -> Unit) {
		val s = _state.value
		val profile = s.profile ?: return
		if (s.busy) return
		val local = buildMap {
			if (s.form.fullName.trim().length !in 2..100) put("fullName", text(R.string.register_full_name_invalid))
			if (s.form.displayName.trim().length !in 2..40) put("displayName", text(R.string.register_display_name_invalid))
			if (s.form.phone.isNotBlank() && !PHONE.matches(s.form.phone.trim())) put("phone", text(R.string.register_phone_invalid))
		}
		if (local.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = local) }
			return
		}
		val changes = profileChanges(profile, s.form)
		if (changes.isEmpty()) {
			_state.update { it.copy(error = text(R.string.profile_unchanged)) }
			return
		}
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			when (val result = auth.updateProfile(changes)) {
				is ApiResult.Success -> {
					_state.update { it.copy(busy = false, profile = result.value, form = ProfileForm.of(result.value), saved = true) }
					onSaved()
				}
				is ApiResult.Failure -> {
					val fields = result.error.fieldErrors(PROFILE_FIELDS)
					_state.update { it.copy(busy = false, fieldErrors = fields, error = if (fields.isEmpty()) result.error.toUiText() else null) }
				}
			}
		}
	}

	private companion object {
		val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
		val PROFILE_FIELDS = setOf("fullName", "displayName", "phone")
	}
}

data class PasswordChangeState(
	val current: String = "",
	val new: String = "",
	val repeat: String = "",
	val busy: Boolean = false,
	val fieldErrors: Map<String, UiText> = emptyMap(),
	val error: UiText? = null,
	val done: Boolean = false,
)

/** ID-08. Başarıda yeni oturum şifreli depoya yazılır (AuthRepository); diğer cihazlar oturumdan çıkar. */
class PasswordChangeViewModel(private val auth: AuthRepository) : ViewModel() {

	private val _state = MutableStateFlow(PasswordChangeState())
	val state: StateFlow<PasswordChangeState> = _state.asStateFlow()

	fun edit(field: String, transform: PasswordChangeState.() -> PasswordChangeState) =
		_state.update { it.transform().copy(fieldErrors = it.fieldErrors - field, error = null, done = false) }

	/** @param onChanged bağlam yeniden çekilir (`mustChangePassword` artık `false`) */
	fun submit(onChanged: () -> Unit) {
		val s = _state.value
		if (s.busy) return
		val local = buildMap {
			if (s.current.isEmpty()) put("currentPassword", text(R.string.password_current_required))
			if (s.new.length < MIN) put("newPassword", text(R.string.register_password_short))
			if (s.new != s.repeat) put("passwordRepeat", text(R.string.register_password_mismatch))
		}
		if (local.isNotEmpty()) {
			_state.update { it.copy(fieldErrors = local) }
			return
		}
		_state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
		viewModelScope.launch {
			when (val result = auth.changePassword(s.current, s.new)) {
				is ApiResult.Success -> {
					_state.value = PasswordChangeState(done = true)
					onChanged()
				}
				is ApiResult.Failure -> {
					val fields = result.error.fieldErrors(setOf("currentPassword", "newPassword"))
					_state.update {
						it.copy(busy = false, current = "", fieldErrors = fields, error = if (fields.isEmpty()) result.error.toUiText() else null)
					}
				}
			}
		}
	}

	private companion object {
		const val MIN = 10
	}
}
