package org.gks.backend.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30 sn, 6 hane, ±1 pencere; backend-api-design.md §5 {@code MfaVerifyRequest}). Kod
 * karşılaştırması sabit sürelidir; aynı adım ikinci kez kabul edilmez (çağıran son kullanılan adımı verir).
 */
final class Totp {

	static final int SECRET_BYTES = 20;
	static final long STEP_SECONDS = 30;
	static final int WINDOW = 1;

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

	private Totp() {
	}

	static byte[] newSecret() {
		byte[] secret = new byte[SECRET_BYTES];
		RANDOM.nextBytes(secret);
		return secret;
	}

	/**
	 * @param lastUsedStep son kabul edilen adım; bu adım ve öncekiler reddedilir
	 * @return kabul edilen adım; kod geçersizse boş
	 */
	static OptionalLong verify(byte[] secret, String code, Instant now, Long lastUsedStep) {
		if (code == null || !code.matches("^[0-9]{6}$")) {
			return OptionalLong.empty();
		}
		long current = now.getEpochSecond() / STEP_SECONDS;
		for (long step = current - WINDOW; step <= current + WINDOW; step++) {
			if (lastUsedStep != null && step <= lastUsedStep) {
				continue;
			}
			if (MessageDigest.isEqual(code(secret, step).getBytes(), code.getBytes())) {
				return OptionalLong.of(step);
			}
		}
		return OptionalLong.empty();
	}

	static String code(byte[] secret, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(secret, "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
					| ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
			return String.format("%06d", binary % 1_000_000);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String base32(byte[] bytes) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte b : bytes) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
		}
		return out.toString();
	}

	static byte[] fromBase32(String text) {
		ByteBuffer out = ByteBuffer.allocate(text.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (char c : text.toCharArray()) {
			int value = c >= 'A' && c <= 'Z' ? c - 'A' : c - '2' + 26;
			buffer = (buffer << 5) | value;
			bits += 5;
			if (bits >= 8) {
				out.put((byte) (buffer >> (bits - 8)));
				bits -= 8;
			}
		}
		return out.array();
	}

}
