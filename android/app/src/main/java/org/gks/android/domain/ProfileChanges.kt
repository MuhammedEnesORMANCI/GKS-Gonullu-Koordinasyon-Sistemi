package org.gks.android.domain

import org.gks.android.data.remote.MeProfile

data class ProfileForm(val fullName: String, val displayName: String, val phone: String) {
	companion object {
		fun of(profile: MeProfile) = ProfileForm(profile.fullName, profile.displayName, profile.phone.orEmpty())
	}
}

/**
 * ID-10 kısmi güncelleme (web `profileUpdate` ile aynı kural): yalnızca değişen alanlar gönderilir,
 * boş telefon `null` olarak gider ve telefonu siler (LD-52). Boş bırakılan zorunlu alan sunucuda `REQUIRED` alır.
 */
fun profileChanges(profile: MeProfile, form: ProfileForm): Map<String, String?> = buildMap {
	val fullName = form.fullName.trim()
	val displayName = form.displayName.trim()
	val phone = form.phone.trim()
	if (fullName != profile.fullName) put("fullName", fullName)
	if (displayName != profile.displayName) put("displayName", displayName)
	if (phone != profile.phone.orEmpty()) put("phone", phone.ifEmpty { null })
}
