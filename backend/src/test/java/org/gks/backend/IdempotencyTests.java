package org.gks.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.ApiProblems.Code;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency bileşeni (backend-api-design.md §3.6, DB §8.3; LD-42, LD-45; T-IDEM-07, 08, 10). Bileşen, döndürme
 * senaryoları için farklı anahtar kümeleri ve saatlerle doğrudan kurulur; veritabanı gerçek PostgreSQL'dir.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyTests {

	private static final String V1 = randomKey();
	private static final String V2 = randomKey();
	private static final Duration TTL = Duration.ofHours(24);

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	IdempotencyCleanup cleanup;

	@Test
	void headerIsValidatedAsUuid() {
		assertThat(Idempotency.optionalKey(null)).isNull();
		assertThat(Idempotency.requiredKey("0190A000-0000-7000-8000-000000000001"))
			.isEqualTo("0190a000-0000-7000-8000-000000000001");
		assertValidation(() -> Idempotency.requiredKey(null), "REQUIRED");
		assertValidation(() -> Idempotency.requiredKey(""), "REQUIRED");
		assertValidation(() -> Idempotency.requiredKey("anahtar-12345"), "INVALID_FORMAT");
		assertValidation(() -> Idempotency.optionalKey("0190a000-0000-7000-8000-0000000000012"), "INVALID_FORMAT");
	}

	@Test
	void firstRequestIsNewAndSameRequestIsReplayedWithStoredBody() {
		Idempotency idempotency = instance(V1, 1, null, null, Clock.systemUTC());
		Idempotency.Request request = request(idempotency, UUID.randomUUID(), Map.of("b", 2, "a", "ç"));

		IdempotencyResponse first = inTransaction(() -> {
			assertThat(idempotency.begin(request)).isEmpty();
			return idempotency.complete(request, 201, Map.of("id", "x", "ad", "Görev"), "TASK", UUID.randomUUID());
		});
		IdempotencyResponse replay = inTransaction(() -> idempotency.begin(request).orElseThrow());

		assertThat(first.replayed()).isFalse();
		assertThat(replay.replayed()).isTrue();
		assertThat(replay.status()).isEqualTo(201);
		assertThat(replay.body()).isEqualTo(first.body());
		assertThat(replay.resourceId()).isEqualTo(first.resourceId());
		assertThat(replay.toResponseEntity().getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
	}

	@Test
	void bodyIsCanonicalizedButDifferentContentOrRouteIsMismatch() {
		Idempotency idempotency = instance(V1, 1, null, null, Clock.systemUTC());
		UUID user = UUID.randomUUID();
		String key = UUID.randomUUID().toString();
		Idempotency.Request original = idempotency.request(key, user, "POST", "/tasks/{id}/publish", Map.of("id", "1"),
				json.readTree("{\"version\": 1, \"note\": {\"b\": 1, \"a\": [1, 2]}}"));
		inTransaction(() -> {
			idempotency.begin(original);
			return idempotency.complete(original, 200, Map.of("ok", true), null, null);
		});

		Idempotency.Request reordered = idempotency.request(key, user, "POST", "/tasks/{id}/publish",
				Map.of("id", "1"), json.readTree("{\"note\": {\"a\": [1, 2], \"b\": 1}, \"version\": 1}"));
		assertThat(inTransaction(() -> idempotency.begin(reordered))).isPresent();

		Idempotency.Request otherBody = idempotency.request(key, user, "POST", "/tasks/{id}/publish",
				Map.of("id", "1"), json.readTree("{\"version\": 2}"));
		Idempotency.Request otherRoute = idempotency.request(key, user, "POST", "/tasks/{id}/state",
				Map.of("id", "1"), json.readTree("{\"version\": 1, \"note\": {\"b\": 1, \"a\": [1, 2]}}"));
		Idempotency.Request otherPath = idempotency.request(key, user, "POST", "/tasks/{id}/publish",
				Map.of("id", "2"), json.readTree("{\"version\": 1, \"note\": {\"b\": 1, \"a\": [1, 2]}}"));
		for (Idempotency.Request request : new Idempotency.Request[] { otherBody, otherRoute, otherPath }) {
			assertThatThrownBy(() -> inTransaction(() -> idempotency.begin(request)))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.code()).isEqualTo(Code.IDEMPOTENCY_KEY_MISMATCH));
		}

		// Kapsam kullanıcıdır: başka kullanıcı aynı anahtarı bağımsız kullanır.
		Idempotency.Request otherUser = idempotency.request(key, UUID.randomUUID(), "POST", "/tasks/{id}/publish",
				Map.of("id", "2"), json.readTree("{}"));
		assertThat(inTransaction(() -> {
			boolean isNew = idempotency.begin(otherUser).isEmpty();
			idempotency.complete(otherUser, 200, null, null, null);
			return isNew;
		})).isTrue();
	}

	/** T-IDEM-07: özet anahtarlıdır; anahtarsız SHA-256'ya eşit değildir ve başka anahtarla farklı çıkar. */
	@Test
	void requestHashIsKeyedHmac() throws Exception {
		UUID user = UUID.randomUUID();
		String key1 = UUID.randomUUID().toString();
		String key2 = UUID.randomUUID().toString();
		Idempotency withV1 = instance(V1, 1, null, null, Clock.systemUTC());
		Idempotency withV2 = instance(V2, 2, null, null, Clock.systemUTC());
		Idempotency.Request request1 = withV1.request(key1, user, "POST", "/r", Map.of(), json.readTree("{\"a\":1}"));
		Idempotency.Request request2 = withV2.request(key2, user, "POST", "/r", Map.of(), json.readTree("{\"a\":1}"));
		inTransaction(() -> {
			withV1.begin(request1);
			withV1.complete(request1, 200, null, null, null);
			withV2.begin(request2);
			return withV2.complete(request2, 200, null, null, null);
		});

		String hash1 = storedHash(user, key1);
		String hash2 = storedHash(user, key2);
		String plain = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(request1.material().getBytes(StandardCharsets.UTF_8)));
		assertThat(hash1).hasSize(64).isNotEqualTo(plain).isNotEqualTo(hash2);
		assertThat(version(user, key1)).isEqualTo(1);
		assertThat(version(user, key2)).isEqualTo(2);
	}

	/** T-IDEM-08 (a – c, e): döndürme penceresinde eski satırlar doğrulama-yalnız anahtarla tekrar edilir. */
	@Test
	void rotationKeepsReplaysAndUnknownVersionFailsClosed() {
		UUID user = UUID.randomUUID();
		String key = UUID.randomUUID().toString();
		Idempotency before = instance(V1, 1, null, null, Clock.systemUTC());
		Idempotency.Request request = before.request(key, user, "POST", "/r", Map.of(), json.readTree("{\"a\":1}"));
		inTransaction(() -> {
			before.begin(request);
			return before.complete(request, 200, Map.of("sonuç", "ilk"), null, null);
		});

		Idempotency rotated = instance(V2, 2, V1, 1, Clock.systemUTC());
		Idempotency.Request again = rotated.request(key, user, "POST", "/r", Map.of(), json.readTree("{\"a\":1}"));
		assertThat(inTransaction(() -> rotated.begin(again)).orElseThrow().body()).contains("ilk");
		Idempotency.Request mismatch = rotated.request(key, user, "POST", "/r", Map.of(), json.readTree("{\"a\":2}"));
		assertThatThrownBy(() -> inTransaction(() -> rotated.begin(mismatch))).isInstanceOf(ApiException.class);

		String fresh = UUID.randomUUID().toString();
		Idempotency.Request newRequest = rotated.request(fresh, user, "POST", "/r", Map.of(), json.readTree("{}"));
		inTransaction(() -> {
			rotated.begin(newRequest);
			return rotated.complete(newRequest, 200, null, null, null);
		});
		assertThat(version(user, fresh)).isEqualTo(2);

		Idempotency removed = instance(V2, 2, null, null, Clock.systemUTC());
		Idempotency.Request afterRemoval = removed.request(key, user, "POST", "/r", Map.of(), json.readTree("{\"a\":1}"));
		assertThatThrownBy(() -> inTransaction(() -> removed.begin(afterRemoval)))
			.isInstanceOf(IdempotencyKeyUnavailableException.class)
			.hasMessageNotContaining("1").hasMessageNotContaining(V1);
		assertThat(storedHash(user, key)).isNotNull();   // satır yok sayılmadı, yeni istek işlenmedi
	}

	/** T-IDEM-10 (a, b) ve §3.6 "Süre": süresi dolmuş satır sürüm denetimine girmeden yeni istek sayılır. */
	@Test
	void expiredRowIsNewRequestEvenWithUnknownVersion() {
		UUID user = UUID.randomUUID();
		String key = UUID.randomUUID().toString();
		Instant past = Instant.now().minus(Duration.ofHours(30));
		jdbc.sql("""
				insert into platform.idempotency_keys (principal_key, idem_key, method, route_template, request_hash,
				       hash_key_version, response_status, response_body, created_at, expires_at)
				values (?, ?, 'POST', '/r', 'eski', 99, 200, '{}', ?, ?)""")
			.params(user.toString(), key, Timestamp.from(past), Timestamp.from(past.plus(TTL))).update();
		Idempotency idempotency = instance(V1, 1, null, null, Clock.systemUTC());
		Idempotency.Request request = idempotency.request(key, user, "POST", "/r", Map.of(), json.readTree("{}"));

		assertThat(inTransaction(() -> {
			Optional<IdempotencyResponse> replay = idempotency.begin(request);
			idempotency.complete(request, 200, null, null, null);
			return replay;
		})).isEmpty();
		assertThat(version(user, key)).isEqualTo(1);
	}

	@Test
	void unexpiredRowWithUnknownVersionBecomesNewRequestOnlyAfterExpiry() {
		UUID user = UUID.randomUUID();
		String key = UUID.randomUUID().toString();
		Instant now = Instant.now();
		jdbc.sql("""
				insert into platform.idempotency_keys (principal_key, idem_key, method, route_template, request_hash,
				       hash_key_version, response_status, response_body, created_at, expires_at)
				values (?, ?, 'POST', '/r', 'eski', 7, 200, '{}', ?, ?)""")
			.params(user.toString(), key, Timestamp.from(now), Timestamp.from(now.plus(TTL))).update();

		Idempotency today = instance(V1, 1, null, null, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
		Idempotency.Request request = today.request(key, user, "POST", "/r", Map.of(), json.readTree("{}"));
		assertThatThrownBy(() -> inTransaction(() -> today.begin(request)))
			.isInstanceOf(IdempotencyKeyUnavailableException.class);

		Idempotency tomorrow = instance(V1, 1, null, null, Clock.fixed(now.plus(TTL).plusSeconds(1), ZoneOffset.UTC));
		Idempotency.Request later = tomorrow.request(key, user, "POST", "/r", Map.of(), json.readTree("{}"));
		assertThat(inTransaction(() -> {
			Optional<IdempotencyResponse> replay = tomorrow.begin(later);
			tomorrow.complete(later, 200, null, null, null);
			return replay;
		})).isEmpty();
	}

	/** Yalnızca 2xx saklanır; hata fırlatan işlem satırını da geri alır, aynı anahtar yeniden kullanılabilir. */
	@Test
	void onlySuccessIsStoredAndFailureRollsBackRow() {
		Idempotency idempotency = instance(V1, 1, null, null, Clock.systemUTC());
		UUID user = UUID.randomUUID();
		String key = UUID.randomUUID().toString();
		Idempotency.Request request = idempotency.request(key, user, "POST", "/r", Map.of(), json.readTree("{}"));

		assertThatThrownBy(() -> inTransaction(() -> {
			idempotency.begin(request);
			throw ApiException.of(Code.VERSION_CONFLICT);
		})).isInstanceOf(ApiException.class);
		assertThat(storedHash(user, key)).isNull();
		assertThatThrownBy(() -> inTransaction(() -> {
			idempotency.begin(request);
			return idempotency.complete(request, 409, null, null, null);
		})).isInstanceOf(IllegalArgumentException.class);
		assertThat(storedHash(user, key)).isNull();

		inTransaction(() -> {
			idempotency.begin(request);
			idempotency.discard(request);
			return null;
		});
		assertThat(storedHash(user, key)).isNull();
	}

	@Test
	void requiresCallersTransaction() {
		Idempotency idempotency = context();
		Idempotency.Request request = idempotency.request(UUID.randomUUID().toString(), UUID.randomUUID(), "POST", "/r",
				Map.of(), json.readTree("{}"));

		assertThatThrownBy(() -> idempotency.begin(request)).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void cleanupDeletesOnlyExpiredRows() {
		UUID user = UUID.randomUUID();
		Instant now = Instant.now();
		for (int hours : new int[] { -25, 23 }) {
			Instant expires = now.plus(Duration.ofHours(hours));
			jdbc.sql("""
					insert into platform.idempotency_keys (principal_key, idem_key, method, route_template,
					       request_hash, hash_key_version, response_status, created_at, expires_at)
					values (?, ?, 'POST', '/r', 'h', 1, 204, ?, ?)""")
				.params(user.toString(), "anahtar" + hours, Timestamp.from(expires.minus(TTL)), Timestamp.from(expires))
				.update();
		}

		cleanup.deleteExpired();

		assertThat(jdbc.sql("select idem_key from platform.idempotency_keys where principal_key = ?")
			.param(user.toString()).query(String.class).list()).containsExactly("anahtar23");
	}

	// --- yardımcılar ---

	@Autowired
	Idempotency contextInstance;

	private Idempotency context() {
		return contextInstance;
	}

	/** Aynı veritabanında, verilen anahtar kümesi ve saatle bir örnek (işlem {@link #inTransaction} ile açılır). */
	private Idempotency instance(String key, int version, String verifyKey, Integer verifyVersion, Clock clock) {
		return new Idempotency(jdbc, json, clock, new IdempotencyProperties(key, version, verifyKey, verifyVersion, TTL));
	}

	private Idempotency.Request request(Idempotency idempotency, UUID user, Object body) {
		return idempotency.request(UUID.randomUUID().toString(), user, "POST", "/r/{id}", Map.of("id", "1"), body);
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return new TransactionTemplate(transactions).execute(status -> work.get());
	}

	private String storedHash(UUID user, String key) {
		return jdbc.sql("select request_hash from platform.idempotency_keys where principal_key = ? and idem_key = ?")
			.params(user.toString(), key).query(String.class).optional().orElse(null);
	}

	private int version(UUID user, String key) {
		return jdbc.sql("select hash_key_version from platform.idempotency_keys where principal_key = ? and idem_key = ?")
			.params(user.toString(), key).query(Integer.class).single();
	}

	private static void assertValidation(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.code()).isEqualTo(Code.VALIDATION_FAILED);
			assertThat(ex.errors()).singleElement().satisfies(error -> {
				assertThat(error.field()).isEqualTo("Idempotency-Key");
				assertThat(error.code()).isEqualTo(code);
			});
		});
	}

	private static String randomKey() {
		byte[] key = new byte[32];
		new java.security.SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
