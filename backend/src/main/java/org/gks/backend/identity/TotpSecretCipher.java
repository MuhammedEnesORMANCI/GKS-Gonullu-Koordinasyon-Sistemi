package org.gks.backend.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * TOTP gizli anahtarının uygulama düzeyinde şifrelenmesi (DB §3.1 {@code totp_secret}: "Base64 şifreli metin + anahtar
 * sürümü", K-19; LD-57). AES-256-GCM; saklanan biçim {@code v<sürüm>:<base64(iv || şifreli metin)>}. Anahtar
 * yapılandırmadan ({@code GKS_TOTP_ENCRYPTION_KEY}) gelir; eksik veya 256 bitten kısaysa uygulama açılmaz.
 */
@Component
@EnableConfigurationProperties(TotpSecretCipher.Properties.class)
class TotpSecretCipher {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int IV_BYTES = 12;

	private final SecretKeySpec key;
	private final int version;

	TotpSecretCipher(Properties properties) {
		this.key = new SecretKeySpec(properties.keyBytes(), "AES");
		this.version = properties.keyVersion();
	}

	@ConfigurationProperties("gks.security.totp")
	record Properties(String encryptionKey, @DefaultValue("1") int keyVersion) {

		Properties {
			if (encryptionKey == null || encryptionKey.isBlank()) {
				throw new IllegalStateException("gks.security.totp.encryption-key tanımlı değil (GKS_TOTP_ENCRYPTION_KEY)");
			}
			byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(encryptionKey.strip());
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalStateException("gks.security.totp.encryption-key Base64 değil");
			}
			if (bytes.length != 32) {
				throw new IllegalStateException("gks.security.totp.encryption-key 256 bit olmalı");
			}
		}

		byte[] keyBytes() {
			return Base64.getDecoder().decode(encryptionKey.strip());
		}

		/** Anahtar değeri metne dönüşmez. */
		@Override
		public String toString() {
			return "TotpCipherProperties[keyVersion=" + keyVersion + "]";
		}

	}

	String encrypt(byte[] secret) {
		try {
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			byte[] encrypted = cipher.doFinal(secret);
			return "v" + version + ":" + Base64.getEncoder()
				.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("TOTP anahtarı şifrelenemedi", ex);
		}
	}

	/** Yalnızca etkin anahtar sürümü çözülür; başka sürüm yapılandırma hatasıdır (sessizce geçilmez). */
	byte[] decrypt(String stored) {
		int colon = stored.indexOf(':');
		if (colon < 0 || !stored.substring(0, colon).equals("v" + version)) {
			throw new IllegalStateException("TOTP anahtar sürümü yüklü değil");
		}
		try {
			byte[] data = Base64.getDecoder().decode(stored.substring(colon + 1));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, IV_BYTES));
			return cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("TOTP anahtarı çözülemedi", ex);
		}
	}

}
