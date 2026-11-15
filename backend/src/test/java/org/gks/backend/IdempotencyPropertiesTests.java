package org.gks.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Özet anahtarı yalnızca yapılandırmadan gelir; eksik, zayıf veya tutarsız anahtarla uygulama açılmaz (LD-42). */
class IdempotencyPropertiesTests {

	private static final Duration TTL = Duration.ofHours(24);
	private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
	private static final String OTHER = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

	@Test
	void missingKeyFailsStartup() {
		assertThatThrownBy(() -> new IdempotencyProperties(null, 1, null, null, TTL))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new IdempotencyProperties("  ", 1, null, null, TTL))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonBase64OrShortKeyFailsStartup() {
		assertThatThrownBy(() -> new IdempotencyProperties("not base64 !!", 1, null, null, TTL))
			.isInstanceOf(IllegalStateException.class);
		String shortKey = Base64.getEncoder().encodeToString(new byte[31]);
		assertThatThrownBy(() -> new IdempotencyProperties(shortKey, 1, null, null, TTL))
			.isInstanceOf(IllegalStateException.class).hasMessageNotContaining(shortKey);
	}

	@Test
	void versionsMustBePositiveSmallintsAndDistinct() {
		assertThatThrownBy(() -> new IdempotencyProperties(KEY, 0, null, null, TTL))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new IdempotencyProperties(KEY, 40_000, null, null, TTL))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new IdempotencyProperties(KEY, 2, OTHER, 2, TTL))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new IdempotencyProperties(KEY, 2, OTHER, null, TTL))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new IdempotencyProperties(KEY, 2, "kısa", 1, TTL))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void activeAndVerifyOnlyKeysAreLoadedByVersion() {
		IdempotencyProperties properties = new IdempotencyProperties(KEY, 2, OTHER, 1, TTL);

		assertThat(properties.keys()).containsOnlyKeys(2, 1);
		assertThat(properties.keys().keySet().iterator().next()).isEqualTo(2);
	}

	@Test
	void keysNeverAppearInText() {
		String text = new IdempotencyProperties(KEY, 2, OTHER, 1, TTL).toString();

		assertThat(text).doesNotContain(KEY).doesNotContain(OTHER).contains("hashKeyVersion=2");
	}

}
