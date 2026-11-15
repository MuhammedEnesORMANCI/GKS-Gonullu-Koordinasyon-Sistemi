package org.gks.backend.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ID-05 yenileme ve döndürme, ID-06 çıkış, ID-07 tüm oturumlardan çıkış (ADR-012, TD-04, LD-18, LD-19, LD-47).
 * <p>
 * Tekrar kullanım: değiştirilmiş bir belirteç yeniden gelirse yalnızca LD-18 toleransı (halef kullanılmamış, aynı istemci
 * türü, ilk döndürmeden bu yana ≤ 10 sn) uygulanır; aksi hâlde çalınma sayılır ve zincirin tamamı iptal edilir. İptal,
 * hata dönmeden önce commit edilir. Web ve Android belirteçleri kendi taşıma yolundan kabul edilir.
 */
@Service
class SessionService {

	static final Duration ROTATION_TOLERANCE = Duration.ofSeconds(10);   // LD-18

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final RefreshTokens refreshTokens;
	private final AccessTokens accessTokens;
	private final Users users;
	private final TransactionTemplate transactions;
	private final Clock clock;

	SessionService(RefreshTokens refreshTokens, AccessTokens accessTokens, Users users, TransactionTemplate transactions,
			Clock clock) {
		this.refreshTokens = refreshTokens;
		this.accessTokens = accessTokens;
		this.users = users;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** Döndürme sonucu; belirteç değerleri metne dönüşmez. */
	record Rotation(ClientType client, AccessTokens.Issued access, RefreshTokens.Issued refresh) {
		@Override
		public String toString() {
			return "Rotation[client=" + client + ", session=" + refresh.sessionId() + "]";
		}
	}

	private record Outcome(Rotation rotation, ApiException error) {
	}

	/** ID-05. */
	Rotation refresh(String token, ClientType transport) {
		if (token == null || token.isBlank()) {
			throw ApiException.of(Code.AUTH_UNAUTHENTICATED);
		}
		Outcome outcome = transactions.execute(status -> rotate(token, transport));
		if (outcome.error() != null) {
			throw outcome.error();   // zincir iptali commit edildikten sonra
		}
		return outcome.rotation();
	}

	private Outcome rotate(String token, ClientType transport) {
		Instant now = clock.instant();
		RefreshTokens.TokenRow row = refreshTokens.lockByToken(token)
			.orElseThrow(() -> ApiException.of(Code.AUTH_UNAUTHENTICATED));
		if (row.client() != transport) {
			throw ApiException.of(Code.AUTH_UNAUTHENTICATED);   // §2.2 adım 2: belirteç kendi taşıma yolundan
		}
		if (row.revokedAt() != null || !now.isBefore(row.expiresAt())) {
			throw ApiException.of(Code.AUTH_SESSION_REVOKED);   // iptal veya süre (web: 12 sa mutlak, LD-19)
		}
		Users.Account account = users.find(row.userId()).orElseThrow(() -> ApiException.of(Code.AUTH_SESSION_REVOKED));
		if (account.status() == Users.Status.DISABLED) {
			throw ApiException.of(Code.ACCOUNT_DISABLED);
		}
		if (account.status() == Users.Status.CLOSED) {
			throw ApiException.of(Code.AUTH_SESSION_REVOKED);
		}
		if (row.replacedBy() != null) {
			RefreshTokens.TokenRow successor = refreshTokens.lock(row.replacedBy()).orElse(null);
			boolean tolerated = successor != null && successor.lastUsedAt() == null && successor.revokedAt() == null
					&& row.lastUsedAt() != null && !now.isAfter(row.lastUsedAt().plus(ROTATION_TOLERANCE));
			if (!tolerated) {
				refreshTokens.revokeSession(row.familyId(), "REUSE_DETECTED", now);
				log.warn("Yenileme belirteci tekrar kullanıldı; oturum {} iptal edildi", row.familyId());
				return new Outcome(null, ApiException.of(Code.AUTH_SESSION_REVOKED));
			}
			refreshTokens.revoke(successor.id(), "ROTATION_RETRY", now);   // kullanılmamış halef yerine yenisi
		}
		RefreshTokens.Issued refresh = refreshTokens.rotate(row, now);
		AccessTokens.Issued access = accessTokens.issue(row.userId(), row.familyId(), row.client(), row.mfaVerified(), now);
		return new Outcome(new Rotation(row.client(), access, refresh), null);
	}

	/** ID-06: bu oturum iptal; Android'de oturumun cihazı pasifleşir. */
	void logout(UUID userId, UUID sessionId) {
		transactions.executeWithoutResult(status -> {
			Instant now = clock.instant();
			refreshTokens.deactivateSessionDevices(userId, sessionId, now);
			refreshTokens.revokeSession(sessionId, "LOGOUT", now);
		});
	}

	/** ID-07: kullanıcının tüm oturumları iptal, tüm cihazları pasif (AC-06.4). */
	void logoutAll(UUID userId) {
		transactions.executeWithoutResult(status -> {
			Instant now = clock.instant();
			refreshTokens.deactivateSessionDevices(userId, null, now);
			refreshTokens.revokeUserSessions(userId, null, "LOGOUT_ALL", now);
		});
	}

}
