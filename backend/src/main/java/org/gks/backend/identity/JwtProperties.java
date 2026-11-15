package org.gks.backend.identity;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Erişim belirteci imzası (HS256). Anahtar yapılandırmadan (ortam değişkeni {@code GKS_JWT_SIGNING_KEY}) gelir;
 * depoya ve günlüğe yazılmaz. Eksik veya 256 bitten kısa anahtarla uygulama açılmaz.
 */
@ConfigurationProperties("gks.security.jwt")
record JwtProperties(String signingKey, @DefaultValue("v1") String keyId,
		@DefaultValue("PT15M") Duration accessTokenTtl) {

	static final int MIN_KEY_BYTES = 32;

	JwtProperties {
		if (signingKey == null || signingKey.isBlank()) {
			throw new IllegalStateException("gks.security.jwt.signing-key tanımlı değil (GKS_JWT_SIGNING_KEY)");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(signingKey.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("gks.security.jwt.signing-key Base64 değil");
		}
		if (bytes.length < MIN_KEY_BYTES) {
			throw new IllegalStateException("gks.security.jwt.signing-key en az 256 bit olmalı");
		}
		if (keyId == null || keyId.isBlank()) {
			throw new IllegalStateException("gks.security.jwt.key-id boş olamaz");
		}
	}

	SecretKey secretKey() {
		return new SecretKeySpec(Base64.getDecoder().decode(signingKey.strip()), "HmacSHA256");
	}

	/** Anahtar değeri hiçbir zaman metne dönüşmez. */
	@Override
	public String toString() {
		return "JwtProperties[keyId=" + keyId + ", accessTokenTtl=" + accessTokenTtl + "]";
	}

}
