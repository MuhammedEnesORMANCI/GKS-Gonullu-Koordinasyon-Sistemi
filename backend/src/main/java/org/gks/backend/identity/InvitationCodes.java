package org.gks.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * Davet kodu ve bağlantı belirteci (TD-02, DB §3.2). Değerler yalnızca anahtarlı özetle (HMAC-SHA-256) saklanır;
 * {@code GET}/{@code POST} aramasında deterministik özet gerekir. Kod 8 karakterdir, karışabilecek harfler
 * ({@code I}, {@code L}, {@code O}, {@code U}, {@code 0}, {@code 1}) kullanılmaz.
 */
@Component
@EnableConfigurationProperties(InvitationCodes.Properties.class)
class InvitationCodes {

	static final int CODE_LENGTH = 8;
	private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SecretKeySpec key;
	private final Properties properties;

	InvitationCodes(Properties properties) {
		this.properties = properties;
		this.key = new SecretKeySpec(properties.keyBytes(), "HmacSHA256");
	}

	@ConfigurationProperties("gks.security.invitation")
	record Properties(String hashKey, @DefaultValue("http://localhost:5173/davet") String linkBase,
			@DefaultValue("gks@localhost") String mailFrom) {

		Properties {
			if (hashKey == null || hashKey.isBlank()) {
				throw new IllegalStateException("gks.security.invitation.hash-key tanımlı değil (GKS_INVITATION_HASH_KEY)");
			}
			try {
				if (Base64.getDecoder().decode(hashKey.strip()).length < 32) {
					throw new IllegalStateException("gks.security.invitation.hash-key en az 256 bit olmalı");
				}
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalStateException("gks.security.invitation.hash-key Base64 değil");
			}
		}

		byte[] keyBytes() {
			return Base64.getDecoder().decode(hashKey.strip());
		}

		/** Anahtar değeri metne dönüşmez. */
		@Override
		public String toString() {
			return "InvitationProperties[linkBase=" + linkBase + "]";
		}
	}

	String newCode() {
		char[] code = new char[CODE_LENGTH];
		for (int i = 0; i < code.length; i++) {
			code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
		}
		return new String(code);
	}

	String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Kullanıcı girişi: boşluk ve tire yok sayılır, büyük harfe çevrilir; biçim dışıysa {@code null}. */
	static String normalizeCode(String code) {
		if (code == null) {
			return null;
		}
		String normalized = code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
		if (normalized.length() != CODE_LENGTH) {
			return null;
		}
		for (char c : normalized.toCharArray()) {
			if (new String(ALPHABET).indexOf(c) < 0) {
				return null;
			}
		}
		return normalized;
	}

	String hash(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA-256 kullanılamıyor", ex);
		}
	}

	String linkBase() {
		return properties.linkBase();
	}

	String mailFrom() {
		return properties.mailFrom();
	}

}
