package org.gks.backend;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * İstek özeti anahtarları (backend-api-design.md §3.6 "Özet anahtarı döndürme", LD-42). Anahtarlar yalnızca
 * yapılandırmadan (ortam değişkeni / sır deposu) gelir; veritabanına, depoya ve günlüğe yazılmaz.
 * <ul>
 * <li>{@code hashKey}/{@code hashKeyVersion}: <b>etkin</b> anahtar; yeni satırların özeti bununla üretilir.</li>
 * <li>{@code verifyOnlyKey}/{@code verifyOnlyKeyVersion}: döndürme penceresindeki <b>doğrulama-yalnız</b> anahtar;
 * yalnızca o sürümle yazılmış satırları karşılaştırır, yeni özet üretmez. İsteğe bağlıdır.</li>
 * </ul>
 * Eksik, Base64 olmayan veya 256 bitten kısa anahtarla uygulama açılmaz.
 */
@ConfigurationProperties("gks.idempotency")
public record IdempotencyProperties(String hashKey, @DefaultValue("1") int hashKeyVersion, String verifyOnlyKey,
		Integer verifyOnlyKeyVersion, @DefaultValue("PT24H") Duration ttl) {

	static final int MIN_KEY_BYTES = 32;

	public IdempotencyProperties {
		decode("gks.idempotency.hash-key", hashKey);
		requireVersion("gks.idempotency.hash-key-version", hashKeyVersion);
		boolean verifyKeyGiven = verifyOnlyKey != null && !verifyOnlyKey.isBlank();
		if (verifyKeyGiven != (verifyOnlyKeyVersion != null)) {
			throw new IllegalStateException(
					"gks.idempotency.verify-only-key ve verify-only-key-version birlikte verilmelidir");
		}
		if (verifyKeyGiven) {
			decode("gks.idempotency.verify-only-key", verifyOnlyKey);
			requireVersion("gks.idempotency.verify-only-key-version", verifyOnlyKeyVersion);
			if (verifyOnlyKeyVersion == hashKeyVersion) {
				throw new IllegalStateException("Doğrulama-yalnız anahtarın sürümü etkin anahtarınkinden farklı olmalı");
			}
		}
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalStateException("gks.idempotency.ttl pozitif olmalı");
		}
	}

	/** Sürüm → anahtar; etkin anahtar ilk sıradadır. */
	Map<Integer, SecretKeySpec> keys() {
		Map<Integer, SecretKeySpec> keys = new LinkedHashMap<>();
		keys.put(hashKeyVersion, new SecretKeySpec(Base64.getDecoder().decode(hashKey.strip()), "HmacSHA256"));
		if (verifyOnlyKeyVersion != null) {
			keys.put(verifyOnlyKeyVersion,
					new SecretKeySpec(Base64.getDecoder().decode(verifyOnlyKey.strip()), "HmacSHA256"));
		}
		return keys;
	}

	/** Anahtar değerleri hiçbir zaman metne dönüşmez; yalnızca sürüm numaraları görünür. */
	@Override
	public String toString() {
		return "IdempotencyProperties[hashKeyVersion=" + hashKeyVersion + ", verifyOnlyKeyVersion="
				+ verifyOnlyKeyVersion + ", ttl=" + ttl + "]";
	}

	private static void decode(String property, String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(property + " tanımlı değil");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(value.strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(property + " Base64 değil");
		}
		if (bytes.length < MIN_KEY_BYTES) {
			throw new IllegalStateException(property + " en az 256 bit olmalı");
		}
	}

	/** DB {@code hash_key_version smallint}, {@code ck_idempotency_keys_hash_key_version (> 0)}. */
	private static void requireVersion(String property, int version) {
		if (version <= 0 || version > Short.MAX_VALUE) {
			throw new IllegalStateException(property + " 1 ile " + Short.MAX_VALUE + " arasında olmalı");
		}
	}

}
