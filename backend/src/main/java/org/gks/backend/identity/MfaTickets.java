package org.gks.backend.identity;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * MFA bileti (§2.1 {@code mfaTicket}; LD-57): parola doğrulandı, TOTP bekleniyor. 5 dk, tek kullanımlık; 5 hatalı kodda
 * geçersizleşir (§2.3). 256 bit rastgele; veritabanında yalnızca SHA-256 özeti tutulur. Bilet bir oturum değildir:
 * kurumsal hiçbir uca erişim vermez, yalnızca ID-03 ve ID-04'te kullanılır.
 */
@Component
class MfaTickets {

	static final Duration LIFETIME = Duration.ofMinutes(5);
	static final int MAX_ATTEMPTS = 5;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	MfaTickets(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @param client doğrulama sonrasında açılacak oturumun istemcisi; Android'de {@code installationId} dolu (ADR-014) */
	record Ticket(UUID id, UUID userId, int attempts, ClientType client, String installationId) {
	}

	String issue(UUID userId, Instant now) {
		return issue(userId, now, ClientType.WEB, null);
	}

	String issue(UUID userId, Instant now, ClientType client, String installationId) {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		jdbc.sql("""
				insert into identity.mfa_tickets (id, user_id, token_hash, created_at, expires_at, client, installation_id)
				values (:id, :userId, :hash, :now, :expiresAt, :client, :installationId)""")
			.param("id", Ids.newId()).param("userId", userId).param("hash", RefreshTokens.hash(token))
			.param("client", client.name()).param("installationId", client == ClientType.ANDROID ? installationId : null)
			.param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(now.plus(LIFETIME)))
			.update();
		return token;
	}

	/** Geçerli bilet (kullanılmamış, süresi dolmamış, hak bitmemiş) kilitlenerek okunur; yoksa boş. */
	Optional<Ticket> lockValid(String token, Instant now) {
		if (token == null || token.isBlank()) {
			return Optional.empty();
		}
		return jdbc.sql("""
				select id, user_id, attempts, client, installation_id from identity.mfa_tickets
				 where token_hash = :hash and used_at is null and expires_at > :now and attempts < :max
				   for update""")
			.param("hash", RefreshTokens.hash(token)).param("now", Timestamp.from(now)).param("max", MAX_ATTEMPTS)
			.query((rs, row) -> new Ticket(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
					rs.getInt("attempts"), ClientType.valueOf(rs.getString("client")), rs.getString("installation_id")))
			.optional();
	}

	/** @return bu hatadan sonraki deneme sayısı */
	int recordFailure(UUID ticketId) {
		return jdbc.sql("update identity.mfa_tickets set attempts = attempts + 1 where id = :id returning attempts")
			.param("id", ticketId).query(Integer.class).single();
	}

	void use(UUID ticketId, Instant now) {
		jdbc.sql("update identity.mfa_tickets set used_at = :now where id = :id")
			.param("id", ticketId).param("now", Timestamp.from(now)).update();
	}

}
