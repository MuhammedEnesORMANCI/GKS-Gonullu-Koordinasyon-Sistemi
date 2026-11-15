package org.gks.backend;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP idempotency (backend-api-design.md §3.6, DB §8.3; LD-04, LD-42, LD-45). Kayıt iş kaydıyla <b>aynı işlemde</b>
 * yazılır; bu yüzden {@link #begin}, {@link #complete} ve {@link #discard} çağıranın işleminde çalışır.
 * <p>
 * Uç akışı (§3.3): başlık 5. adımda ({@link #requiredKey}/{@link #optionalKey}), {@link #begin} kaynak yükleme (6) ve
 * {@code can()} (7) sonrasında 8. adımda çağrılır. Tekrar veya uyuşmazlık burada döner; ardından kaynak kuralları
 * (9) gelir. Başarılı yanıt {@link #complete} ile satıra yazılır; hata fırlatılırsa işlem geri alınır ve satır da
 * onunla gider (yalnızca 2xx saklanır).
 */
@Component
public class Idempotency {

	public static final String HEADER = "Idempotency-Key";
	public static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private static final Logger log = LoggerFactory.getLogger(Idempotency.class);
	private static final Pattern UUID_FORMAT = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
	private static final HexFormat HEX = HexFormat.of();

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final Clock clock;
	private final IdempotencyProperties properties;
	private final Map<Integer, SecretKeySpec> keys;

	Idempotency(JdbcClient jdbc, JsonMapper json, Clock clock, IdempotencyProperties properties) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.properties = properties;
		this.keys = properties.keys();
	}

	/** {@code x-idempotency: required}: eksik veya biçimsiz başlık {@code 400 VALIDATION_FAILED} (§3.3 adım 5). */
	public static String requiredKey(String header) {
		if (header == null || header.isBlank()) {
			throw ApiException.validation(List.of(new FieldError(HEADER, "REQUIRED", "Idempotency-Key başlığı zorunludur")));
		}
		return validKey(header);
	}

	/** {@code x-idempotency: supported} ({@code d}): başlık yoksa {@code null}; varsa aynı kurallar. */
	public static String optionalKey(String header) {
		return header == null || header.isEmpty() ? null : validKey(header);
	}

	private static String validKey(String header) {
		String key = header.strip();
		if (key.length() != 36 || !UUID_FORMAT.matcher(key).matches()) {
			throw ApiException.validation(List.of(new FieldError(HEADER, "INVALID_FORMAT",
					"Idempotency-Key 36 karakterlik UUID olmalıdır")));
		}
		return key.toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * @param key           {@link #requiredKey}/{@link #optionalKey} sonucu; {@code null} ise kayıt yapılmaz
	 * @param principal     kimlikli istekte kullanıcı (§3.6 "Kapsam anahtarı")
	 * @param routeTemplate yol şablonu, ör. {@code /assignments/{id}/accept}
	 * @param pathParams    yol parametreleri
	 * @param body          gövde ({@link JsonNode} veya istek kaydı); kanonik JSON'a çevrilir, saklanmaz
	 */
	public Request request(String key, UUID principal, String method, String routeTemplate,
			Map<String, ?> pathParams, Object body) {
		return request(key, principal == null ? null : principal.toString(), method, routeTemplate, pathParams, body);
	}

	/**
	 * Kimliksiz uç (ID-16) için kapsam anahtarı çağırandan gelir: {@code anon:} + davet kodunun anahtarlı özeti; kod
	 * açık hâliyle yazılmaz (§3.6 "Kapsam anahtarı", LD-42).
	 */
	public Request request(String key, String principalKey, String method, String routeTemplate,
			Map<String, ?> pathParams, Object body) {
		if (key == null) {
			return new Request(null, null, method, routeTemplate, null);
		}
		StringBuilder material = new StringBuilder();
		material.append(method).append('\n').append(routeTemplate).append('\n');
		new TreeMap<>(pathParams).forEach((name, value) -> material.append(name).append('=').append(value).append('\n'));
		material.append(canonical(body instanceof JsonNode node ? node : json.valueToTree(body)));
		return new Request(key, principalKey, method, routeTemplate, material.toString());
	}

	/**
	 * İşlemin başında anahtar satırını ekler. Aynı kapsam ve anahtarla eşzamanlı istek birincil anahtarda bekler
	 * (ilk işlem commit ederse tekrar döner, geri alınırsa bu istek işlenir).
	 *
	 * @return saklı yanıt (aynı özet) veya boş (yeni istek)
	 * @throws ApiException                          {@code 422 IDEMPOTENCY_KEY_MISMATCH}
	 * @throws IdempotencyKeyUnavailableException    satırın sürüm anahtarı yüklü değil (fail-closed, {@code 500})
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<IdempotencyResponse> begin(Request request) {
		if (request.key() == null) {
			return Optional.empty();
		}
		for (int attempt = 0; attempt < 3; attempt++) {
			Instant now = clock.instant();
			// Süresi dolmuş satır temizlik işini beklemeden yok sayılır; sürüm denetimine girmez (LD-42 (7)).
			jdbc.sql("""
					delete from platform.idempotency_keys
					 where principal_key = :principal and idem_key = :key and expires_at <= :now""")
				.param("principal", request.principalKey()).param("key", request.key())
				.param("now", Timestamp.from(now))
				.update();
			int version = properties.hashKeyVersion();
			int inserted = jdbc.sql("""
					insert into platform.idempotency_keys
					       (principal_key, idem_key, method, route_template, request_hash, hash_key_version,
					        created_at, expires_at)
					values (:principal, :key, :method, :route, :hash, :version, :now, :expires)
					on conflict on constraint pk_idempotency_keys do nothing""")
				.param("principal", request.principalKey()).param("key", request.key())
				.param("method", request.method()).param("route", request.routeTemplate())
				.param("hash", hash(version, request.material())).param("version", (short) version)
				.param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(properties.ttl())))
				.update();
			if (inserted == 1) {
				return Optional.empty();
			}
			Optional<StoredRow> stored = jdbc.sql("""
					select request_hash, hash_key_version, response_status, response_body, body_redacted,
					       resource_id, expires_at
					  from platform.idempotency_keys
					 where principal_key = :principal and idem_key = :key""")
				.param("principal", request.principalKey()).param("key", request.key())
				.query((rs, row) -> new StoredRow(rs.getString("request_hash"), rs.getInt("hash_key_version"),
						(Integer) rs.getObject("response_status"), rs.getString("response_body"),
						rs.getBoolean("body_redacted"), rs.getObject("resource_id", UUID.class),
						rs.getTimestamp("expires_at").toInstant()))
				.optional();
			if (stored.isEmpty() || !stored.get().expiresAt().isAfter(now)) {
				continue;   // arada temizlendi veya süresi tam şimdi doldu: yeniden dene
			}
			return Optional.of(replay(request, stored.get()));
		}
		throw new IllegalStateException("Idempotency kaydı eklenemedi");
	}

	/** Başarılı yanıtı JSON'a çevirir ve (anahtar varsa) satıra yazar; yalnızca 2xx (§3.6 "Saklanan yanıt"). */
	@Transactional(propagation = Propagation.MANDATORY)
	public IdempotencyResponse complete(Request request, int status, Object body, String resourceType,
			UUID resourceId) {
		if (status < 200 || status > 299) {
			throw new IllegalArgumentException("Yalnızca 2xx yanıt saklanır");
		}
		String text = body == null ? null : json.writeValueAsString(body);
		if (request.key() != null) {
			int updated = jdbc.sql("""
					update platform.idempotency_keys
					   set response_status = :status, response_body = :body, resource_type = :type, resource_id = :id
					 where principal_key = :principal and idem_key = :key and response_status is null""")
				.param("status", status).param("body", text).param("type", resourceType).param("id", resourceId)
				.param("principal", request.principalKey()).param("key", request.key())
				.update();
			if (updated != 1) {
				throw new IllegalStateException("Idempotency satırı bu işlemde eklenmemiş");
			}
		}
		return new IdempotencyResponse(status, text, false, resourceId, null);
	}

	/**
	 * {@code x-idempotency: required-redacted} (ID-16, LD-42): yanıt gövdesi sır içerdiği için <b>saklanmaz</b>;
	 * yalnızca durum ve kaynak kimliği yazılır. Tekrar {@code 409 ALREADY_COMPLETED} alır.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void completeRedacted(Request request, int status, String resourceType, UUID resourceId) {
		if (status < 200 || status > 299) {
			throw new IllegalArgumentException("Yalnızca 2xx yanıt saklanır");
		}
		if (request.key() == null) {
			return;
		}
		int updated = jdbc.sql("""
				update platform.idempotency_keys
				   set response_status = :status, response_body = null, body_redacted = true,
				       resource_type = :type, resource_id = :id
				 where principal_key = :principal and idem_key = :key and response_status is null""")
			.param("status", status).param("type", resourceType).param("id", resourceId)
			.param("principal", request.principalKey()).param("key", request.key())
			.update();
		if (updated != 1) {
			throw new IllegalStateException("Idempotency satırı bu işlemde eklenmemiş");
		}
	}

	/**
	 * Durum değiştirip 4xx dönen yol (§3.6, LD-45; MVP'de yalnızca TK-11 savunma yolu): işlem commit edilecek ama
	 * yanıt 2xx değil; işlemin başında eklenen kendi satırı commit'ten önce silinir.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void discard(Request request) {
		if (request.key() == null) {
			return;
		}
		jdbc.sql("""
				delete from platform.idempotency_keys
				 where principal_key = :principal and idem_key = :key and response_status is null""")
			.param("principal", request.principalKey()).param("key", request.key())
			.update();
	}

	private IdempotencyResponse replay(Request request, StoredRow row) {
		SecretKeySpec key = keys.get(row.hashKeyVersion());
		if (key == null) {
			// Alarm: işletim günlüğüne yalnızca sürüm numarası yazılır; anahtar değeri hiçbir yerde görünmez.
			log.error("ALARM idempotency: süresi dolmamış satırın özet anahtarı sürümü {} yüklü değil; istek reddedildi",
					row.hashKeyVersion());
			throw new IdempotencyKeyUnavailableException();
		}
		byte[] expected = hash(key, request.material()).getBytes(StandardCharsets.US_ASCII);
		if (!MessageDigest.isEqual(expected, row.requestHash().getBytes(StandardCharsets.US_ASCII))) {
			throw ApiException.of(Code.IDEMPOTENCY_KEY_MISMATCH);
		}
		if (row.bodyRedacted()) {
			// Sır içeren ilk yanıt yeniden üretilmez ve döndürülmez; istemci normal girişe yönelir (§3.6 (1)).
			throw ApiException.alreadyCompleted(row.resourceId(), "LOGIN");
		}
		return new IdempotencyResponse(row.responseStatus(), row.responseBody(), true, row.resourceId(), null);
	}

	private String hash(int version, String material) {
		return hash(keys.get(version), material);
	}

	private static String hash(SecretKeySpec key, String material) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HEX.formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HMAC-SHA-256 kullanılamıyor", ex);
		}
	}

	/** Kanonik JSON: nesne alanları ada göre sıralı, boşluksuz. */
	private String canonical(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return "null";
		}
		if (node.isObject()) {
			StringBuilder out = new StringBuilder("{");
			boolean first = true;
			for (String name : new TreeSet<>(node.propertyNames())) {
				if (!first) {
					out.append(',');
				}
				first = false;
				out.append(json.writeValueAsString(name)).append(':').append(canonical(node.get(name)));
			}
			return out.append('}').toString();
		}
		if (node.isArray()) {
			StringBuilder out = new StringBuilder("[");
			for (int i = 0; i < node.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				out.append(canonical(node.get(i)));
			}
			return out.append(']').toString();
		}
		return json.writeValueAsString(node);
	}

	/**
	 * Bir isteğin idempotency bağlamı. {@code material} özetin girdisidir (yöntem + şablon + yol parametreleri +
	 * kanonik gövde); bellekte kalır, hiçbir yere yazılmaz.
	 */
	public record Request(String key, String principalKey, String method, String routeTemplate, String material) {

		@Override
		public String toString() {
			return "Idempotency.Request[" + method + " " + routeTemplate + "]";
		}

	}

	private record StoredRow(String requestHash, int hashKeyVersion, Integer responseStatus, String responseBody,
			boolean bodyRedacted, UUID resourceId, Instant expiresAt) {
	}

}
