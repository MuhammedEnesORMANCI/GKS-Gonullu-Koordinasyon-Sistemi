package org.gks.android.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Belirteçler Android Keystore'daki AES-256-GCM anahtarıyla şifrelenip uygulamaya özel SharedPreferences'ta
 * tutulur (backend §2.2: "Keystore destekli şifreli depo"). Anahtar cihazdan dışarı çıkarılamaz; yedekten dönen
 * veya çözülemeyen kayıt silinir ve kullanıcı yeniden giriş yapar. Belirteçler günlüğe yazılmaz.
 */
class KeystoreSessionStore(context: Context, private val json: Json) : SessionStore {

	private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	@Volatile
	private var cached: StoredSession? = null

	@Volatile
	private var loaded = false

	@Synchronized
	override fun read(): StoredSession? {
		if (!loaded) {
			cached = decrypt(prefs.getString(KEY_BLOB, null))
			loaded = true
		}
		return cached
	}

	@Synchronized
	override fun save(session: StoredSession) {
		val plain = json.encodeToString(StoredSession.serializer(), session).toByteArray(Charsets.UTF_8)
		val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
		val blob = cipher.iv + cipher.doFinal(plain)
		// commit(): yenilenen belirteç, eskisiyle yeniden istek yapılmadan önce diske yazılmış olmalı (§2.2, BS-01).
		check(prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
			"Oturum kaydedilemedi"
		}
		cached = session
		loaded = true
	}

	@Synchronized
	override fun clear() {
		prefs.edit().remove(KEY_BLOB).commit()
		cached = null
		loaded = true
	}

	private fun decrypt(encoded: String?): StoredSession? {
		if (encoded == null) return null
		return try {
			val blob = Base64.decode(encoded, Base64.NO_WRAP)
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
			val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
			json.decodeFromString(StoredSession.serializer(), String(plain, Charsets.UTF_8))
		} catch (e: GeneralSecurityException) {
			prefs.edit().remove(KEY_BLOB).commit()
			null
		} catch (e: IllegalArgumentException) {
			prefs.edit().remove(KEY_BLOB).commit()
			null
		}
	}

	private fun key(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
		generator.init(
			KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build(),
		)
		return generator.generateKey()
	}

	private companion object {
		const val PREFS = "gks_session"
		const val KEY_BLOB = "session.v1"
		const val KEY_ALIAS = "gks.session.v1"
		const val ANDROID_KEYSTORE = "AndroidKeyStore"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val IV_BYTES = 12
		const val TAG_BITS = 128
	}
}
