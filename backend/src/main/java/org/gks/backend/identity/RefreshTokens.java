package org.gks.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Yenileme belirteci (ADR-012, TD-04, DB §3.4): 256 bit rastgele, opak. Veritabanında yalnızca SHA-256 özeti
 * saklanır; açık değer yalnızca istemciye bir kez döner. Yeni oturumun {@code family_id}'si ilk belirtecin kimliğidir
 * ve JWT {@code sid} olur.
 */
@Component
class RefreshTokens {

	static final Duration WEB_LIFETIME = Duration.ofHours(12);       // LD-19: mutlak
	static final Duration ANDROID_LIFETIME = Duration.ofDays(30);    // TD-04: kayan

	/**
	 * Mutlak (12 sa, uzamaz) ömürlü zincirler: web ve MFA'lı her oturum (ADR-014: Android yönetim oturumu dahil).
	 * Yalnızca MFA'sız Android (gönüllü) oturumu 30 gün kayar.
	 */
	static boolean absolute(ClientType client, boolean mfaVerified) {
		return client == ClientType.WEB || mfaVerified;
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	RefreshTokens(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Yeni oturum (döndürme zincirinin ilk belirteci). Android'de kurulumun cihaz satırı varsa bağlanır. */
	Issued startSession(UUID userId, ClientType client, String installationId, boolean mfaVerified, Instant now) {
		String token = newToken();
		UUID id = Ids.newId();
		Instant expiresAt = now.plus(absolute(client, mfaVerified) ? WEB_LIFETIME : ANDROID_LIFETIME);
		UUID deviceId = client == ClientType.ANDROID ? activeDevice(userId, installationId) : null;
		jdbc.sql("""
				insert into identity.refresh_tokens
				    (id, user_id, device_id, family_id, client, token_hash, mfa_verified, issued_at, expires_at)
				values (:id, :userId, :deviceId, :id, :client, :hash, :mfa, :now, :expiresAt)""")
			.param("id", id)
			.param("userId", userId)
			.param("deviceId", deviceId)
			.param("client", client.name())
			.param("hash", hash(token))
			.param("mfa", mfaVerified)
			.param("now", java.sql.Timestamp.from(now))
			.param("expiresAt", java.sql.Timestamp.from(expiresAt))
			.update();
		return new Issued(token, id, expiresAt);
	}

	/** Yenileme belirteci satırı; özet ve değer metne dönüşmez. */
	record TokenRow(UUID id, UUID userId, UUID deviceId, UUID familyId, ClientType client, boolean mfaVerified,
			Instant issuedAt, Instant expiresAt, Instant lastUsedAt, UUID replacedBy, Instant revokedAt) {
	}

	private static final String TOKEN_COLUMNS = """
			id, user_id, device_id, family_id, client, mfa_verified, issued_at, expires_at, last_used_at, replaced_by,
			revoked_at""";

	private static TokenRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new TokenRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("device_id", UUID.class), rs.getObject("family_id", UUID.class),
				ClientType.valueOf(rs.getString("client")), rs.getBoolean("mfa_verified"),
				instant(rs.getTimestamp("issued_at")), instant(rs.getTimestamp("expires_at")),
				instant(rs.getTimestamp("last_used_at")), rs.getObject("replaced_by", UUID.class),
				instant(rs.getTimestamp("revoked_at")));
	}

	/** ID-05: belirteç özetiyle bulunur ve satır kilitlenir; aynı belirteçle eşzamanlı yenilemeler sıraya girer. */
	Optional<TokenRow> lockByToken(String token) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		return jdbc.sql("select " + TOKEN_COLUMNS + " from identity.refresh_tokens where token_hash = :hash for update")
			.param("hash", hash(token)).query((rs, n) -> row(rs)).optional();
	}

	Optional<TokenRow> lock(UUID id) {
		return jdbc.sql("select " + TOKEN_COLUMNS + " from identity.refresh_tokens where id = :id for update")
			.param("id", id).query((rs, n) -> row(rs)).optional();
	}

	/**
	 * Döndürme (ADR-012, TD-04): halef eklenir ve eskisi {@code replaced_by} ile değiştirilir; ikisi çağıranın
	 * işlemindedir (LD-47 atomiklik kuralı, DB E-15). Web'de bitiş zincir başlangıcı + 12 sa'dir, döndürmeyle uzamaz
	 * (LD-19); Android'de 30 gün kayar.
	 */
	Issued rotate(TokenRow old, Instant now) {
		String token = newToken();
		UUID id = Ids.newId();
		Instant expiresAt = absolute(old.client(), old.mfaVerified()) ? old.expiresAt() : now.plus(ANDROID_LIFETIME);
		jdbc.sql("""
				insert into identity.refresh_tokens
				    (id, user_id, device_id, family_id, client, token_hash, mfa_verified, issued_at, expires_at)
				values (:id, :userId, :deviceId, :familyId, :client, :hash, :mfa, :now, :expiresAt)""")
			.param("id", id).param("userId", old.userId()).param("deviceId", old.deviceId())
			.param("familyId", old.familyId()).param("client", old.client().name()).param("hash", hash(token))
			.param("mfa", old.mfaVerified()).param("now", java.sql.Timestamp.from(now))
			.param("expiresAt", java.sql.Timestamp.from(expiresAt))
			.update();
		jdbc.sql("update identity.refresh_tokens set replaced_by = :next, last_used_at = coalesce(last_used_at, :now) where id = :id")
			.param("id", old.id()).param("next", id).param("now", java.sql.Timestamp.from(now)).update();
		return new Issued(token, old.familyId(), expiresAt);
	}

	/** Oturumun canlı (döndürülmemiş, iptal edilmemiş, süresi dolmamış) belirteci; satır kilitlenir. */
	Optional<TokenRow> lockLiveHead(UUID sessionId, Instant now) {
		return jdbc.sql("select " + TOKEN_COLUMNS + " from identity.refresh_tokens"
				+ " where family_id = :family and revoked_at is null and replaced_by is null and expires_at > :now"
				+ " for update")
			.param("family", sessionId).param("now", java.sql.Timestamp.from(now)).query((rs, n) -> row(rs)).optional();
	}

	/**
	 * ID-08: aynı istemci, cihaz ve MFA durumuyla <b>yeni</b> oturum (yeni {@code family_id}). Web'de eski oturumun
	 * mutlak bitişi devralınır (LD-19; parola değişikliği süreyi uzatmaz), Android'de 30 gün.
	 */
	Issued continueAsNewSession(TokenRow old, Instant now) {
		String token = newToken();
		UUID id = Ids.newId();
		Instant expiresAt = absolute(old.client(), old.mfaVerified()) ? old.expiresAt() : now.plus(ANDROID_LIFETIME);
		jdbc.sql("""
				insert into identity.refresh_tokens
				    (id, user_id, device_id, family_id, client, token_hash, mfa_verified, issued_at, expires_at)
				values (:id, :userId, :deviceId, :id, :client, :hash, :mfa, :now, :expiresAt)""")
			.param("id", id).param("userId", old.userId()).param("deviceId", old.deviceId())
			.param("client", old.client().name()).param("hash", hash(token)).param("mfa", old.mfaVerified())
			.param("now", java.sql.Timestamp.from(now)).param("expiresAt", java.sql.Timestamp.from(expiresAt))
			.update();
		return new Issued(token, id, expiresAt);
	}

	void revoke(UUID id, String reason, Instant now) {
		jdbc.sql("""
				update identity.refresh_tokens set revoked_at = :now, revoke_reason = :reason
				 where id = :id and revoked_at is null""")
			.param("id", id).param("reason", reason).param("now", java.sql.Timestamp.from(now)).update();
	}

	/** Zincirin (oturumun) tüm canlı belirteçleri; erişim belirteçleri sonraki istekte reddedilir (LD-47). */
	void revokeSession(UUID sessionId, String reason, Instant now) {
		jdbc.sql("""
				update identity.refresh_tokens set revoked_at = :now, revoke_reason = :reason
				 where family_id = :family and revoked_at is null""")
			.param("family", sessionId).param("reason", reason).param("now", java.sql.Timestamp.from(now)).update();
	}

	/** Kullanıcının tüm oturumları; {@code exceptSession} varsa o oturum korunur (ID-08). */
	void revokeUserSessions(UUID userId, UUID exceptSession, String reason, Instant now) {
		jdbc.sql("""
				update identity.refresh_tokens set revoked_at = :now, revoke_reason = :reason
				 where user_id = :userId and revoked_at is null and (cast(:except as uuid) is null or family_id <> :except)""")
			.param("userId", userId).param("except", exceptSession).param("reason", reason)
			.param("now", java.sql.Timestamp.from(now)).update();
	}

	/** Çıkışta oturumun cihazı pasifleşir; o kuruluma bildirim gitmez (DB §3.3, §3.4 {@code device_id}). */
	void deactivateSessionDevices(UUID userId, UUID sessionId, Instant now) {
		jdbc.sql("""
				update identity.devices set active = false, deactivated_at = :now, deactivation_reason = 'LOGOUT'
				 where active and user_id = :userId
				   and (cast(:session as uuid) is null
				        or id in (select device_id from identity.refresh_tokens where family_id = :session))""")
			.param("userId", userId).param("session", sessionId).param("now", java.sql.Timestamp.from(now)).update();
	}

	/** Oturumun canlı belirtecinin bitişi ({@code SessionInfo.expiresAt}). */
	Optional<Instant> sessionExpiry(UUID sessionId) {
		return jdbc.sql("""
				select expires_at from identity.refresh_tokens
				 where family_id = :family and revoked_at is null and replaced_by is null""")
			.param("family", sessionId).query((rs, n) -> instant(rs.getTimestamp("expires_at"))).optional();
	}

	private static Instant instant(java.sql.Timestamp timestamp) {
		return timestamp != null ? timestamp.toInstant() : null;
	}

	private UUID activeDevice(UUID userId, String installationId) {
		return jdbc.sql("""
				select id from identity.devices
				 where user_id = :userId and installation_id = :installationId and active""")
			.param("userId", userId)
			.param("installationId", installationId)
			.query(UUID.class)
			.optional()
			.orElse(null);
	}

	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Belirteç değeri metne dönüşmez. */
	record Issued(String token, UUID sessionId, Instant expiresAt) {
		@Override
		public String toString() {
			return "RefreshToken[sessionId=" + sessionId + ", expiresAt=" + expiresAt + "]";
		}
	}

}
