package org.gks.android.data.session

import android.content.Context
import java.util.UUID

/**
 * Uygulama kurulumunun kalıcı kimliği (§5.2 `installationId`: 8..64, `[A-Za-z0-9-]`). Sır değildir; giriş ve
 * cihaz kaydını aynı kuruluma bağlar. Kaldırıp yeniden kurulunca değişir.
 */
class InstallationId(context: Context) {

	private val prefs = context.getSharedPreferences("gks_device", Context.MODE_PRIVATE)

	@Synchronized
	fun get(): String = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
		prefs.edit().putString(KEY, it).commit()
	}

	private companion object {
		const val KEY = "installation_id"
	}
}
