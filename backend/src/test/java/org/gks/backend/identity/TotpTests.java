package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** TOTP (RFC 6238) ve anahtarın AES-GCM ile saklanması; Spring bağlamı gerekmez. */
class TotpTests {

	/** RFC 6238 Ek B, SHA1 vektörleri (8 hanenin son 6 hanesi). */
	@Test
	void matchesRfc6238Vectors() {
		byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		assertThat(Totp.code(secret, 59 / 30)).isEqualTo("287082");
		assertThat(Totp.code(secret, 1111111109L / 30)).isEqualTo("081804");
		assertThat(Totp.code(secret, 2000000000L / 30)).isEqualTo("279037");
	}

	@Test
	void acceptsOnlyOneStepWindowAndNeverAnAlreadyUsedStep() {
		byte[] secret = Totp.newSecret();
		Instant now = Instant.ofEpochSecond(1_800_000_000L);
		long step = now.getEpochSecond() / 30;

		assertThat(Totp.verify(secret, Totp.code(secret, step - 1), now, null)).hasValue(step - 1);
		assertThat(Totp.verify(secret, Totp.code(secret, step + 1), now, null)).hasValue(step + 1);
		assertThat(Totp.verify(secret, Totp.code(secret, step + 2), now, null)).isEmpty();
		assertThat(Totp.verify(secret, Totp.code(secret, step), now, step)).isEmpty();
		assertThat(Totp.verify(secret, Totp.code(secret, step), now, step - 1)).hasValue(step);
		assertThat(Totp.verify(secret, "12345", now, null)).isEmpty();
	}

	@Test
	void base32RoundTrip() {
		byte[] secret = Totp.newSecret();
		assertThat(Totp.fromBase32(Totp.base32(secret))).isEqualTo(secret);
	}

	@Test
	void secretIsEncryptedWithFreshIvAndTamperingIsDetected() {
		TotpSecretCipher cipher = cipher(1);
		byte[] secret = Totp.newSecret();

		String first = cipher.encrypt(secret);
		String second = cipher.encrypt(secret);
		assertThat(first).startsWith("v1:").isNotEqualTo(second);
		assertThat(cipher.decrypt(first)).isEqualTo(secret);

		byte[] raw = Base64.getDecoder().decode(first.substring(3));
		raw[raw.length - 1] ^= 1;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher(1).decrypt(first)).isInstanceOf(IllegalStateException.class);   // başka anahtar
		assertThatThrownBy(() -> cipher(2).decrypt(first)).isInstanceOf(IllegalStateException.class);   // başka sürüm
	}

	@Test
	void keyMustBe32BytesBase64() {
		assertThatThrownBy(() -> new TotpSecretCipher.Properties("", 1)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new TotpSecretCipher.Properties(Base64.getEncoder().encodeToString(new byte[16]), 1))
			.isInstanceOf(IllegalStateException.class);
	}

	private static TotpSecretCipher cipher(int version) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return new TotpSecretCipher(new TotpSecretCipher.Properties(Base64.getEncoder().encodeToString(key), version));
	}

}
