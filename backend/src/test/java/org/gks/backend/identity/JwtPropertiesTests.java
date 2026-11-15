package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** İmza anahtarı güvenli yapılandırmadan gelir; eksik veya zayıf anahtarla uygulama açılmaz. */
class JwtPropertiesTests {

	private static final Duration TTL = Duration.ofMinutes(15);

	@Test
	void missingKeyFailsStartup() {
		assertThatThrownBy(() -> new JwtProperties(null, "v1", TTL)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new JwtProperties(" ", "v1", TTL)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonBase64OrShortKeyFailsStartup() {
		assertThatThrownBy(() -> new JwtProperties("not base64 !!", "v1", TTL))
			.isInstanceOf(IllegalStateException.class);
		String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
		assertThatThrownBy(() -> new JwtProperties(shortKey, "v1", TTL))
			.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(shortKey);
	}

	@Test
	void keyNeverAppearsInText() {
		String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

		assertThat(new JwtProperties(key, "v1", TTL).toString()).doesNotContain(key);
	}

}
