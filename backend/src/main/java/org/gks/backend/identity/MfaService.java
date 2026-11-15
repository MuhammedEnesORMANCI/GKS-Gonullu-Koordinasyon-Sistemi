package org.gks.backend.identity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.access.WebAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ID-03 TOTP kurulumu ve ID-04 TOTP doğrulaması (backend-api-design.md §2.3; K-19, ADR-013, LD-57).
 * <ul>
 * <li>Bilet geçersiz, kullanılmış veya süresi dolmuşsa {@code 401 AUTH_MFA_REQUIRED}; istemci parola adımına döner.</li>
 * <li>Hatalı kod: bilet deneme sayacı ve hesap kilit sayacı artar ({@code 400 INVALID_CODE}); 5 hatada bilet
 * geçersizleşir, hesap kilitlenirse {@code 423}. Sayaçlar hatayla dönülse de kalıcıdır.</li>
 * <li>Doğru kod: bilet tükenir, TOTP adımı kaydedilir (aynı kod tekrar kabul edilmez), kurulum sürüyorsa kalıcılaşır,
 * {@code mfaVerified = true} web oturumu açılır. Web oturumu bu yoldan başka hiçbir yoldan MFA'lı açılmaz.</li>
 * </ul>
 */
@Service
class MfaService {

	static final String ISSUER = "GKS";

	private final MfaTickets tickets;
	private final Users users;
	private final TotpSecretCipher cipher;
	private final RefreshTokens refreshTokens;
	private final AccessTokens accessTokens;
	private final WebAccess webAccess;
	private final TransactionTemplate transactions;
	private final Clock clock;

	MfaService(MfaTickets tickets, Users users, TotpSecretCipher cipher, RefreshTokens refreshTokens,
			AccessTokens accessTokens, WebAccess webAccess, TransactionTemplate transactions, Clock clock) {
		this.tickets = tickets;
		this.users = users;
		this.cipher = cipher;
		this.refreshTokens = refreshTokens;
		this.accessTokens = accessTokens;
		this.webAccess = webAccess;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** Gizli anahtar yalnızca bu yanıtta bir kez döner (§5 {@code MfaEnrollResponse}); metne dönüşmez. */
	record Enrollment(String otpauthUri, String secret) {
		@Override
		public String toString() {
			return "Enrollment[secret=***]";
		}
	}

	/** ID-03: TOTP kurulmamışsa yeni anahtar üretir (bekleyen); kuruluysa {@code 409}. Bilet tüketilmez. */
	Enrollment enroll(String ticket) {
		return transactions.execute(status -> {
			Instant now = clock.instant();
			MfaTickets.Ticket valid = tickets.lockValid(ticket, now).orElseThrow(() -> ApiException.of(Code.AUTH_MFA_REQUIRED));
			if (users.lockTotp(valid.userId()).enrolled()) {
				throw ApiException.invalidStateTransition("ENROLLED", "ENROLLED");
			}
			byte[] secret = Totp.newSecret();
			users.setPendingTotp(valid.userId(), cipher.encrypt(secret), now);
			String email = users.find(valid.userId()).orElseThrow().email();
			String base32 = Totp.base32(secret);
			String label = URLEncoder.encode(ISSUER + ":" + email, StandardCharsets.UTF_8).replace("+", "%20");
			return new Enrollment("otpauth://totp/" + label + "?secret=" + base32 + "&issuer=" + ISSUER
					+ "&algorithm=SHA1&digits=6&period=30", base32);
		});
	}

	/** ID-04. */
	LoginService.Result verify(String ticket, String code) {
		if (code == null || !code.matches("^[0-9]{6}$")) {
			throw ApiException.validation(List.of(new FieldError("code", "INVALID_FORMAT", "Kod 6 haneli olmalıdır")));
		}
		Outcome outcome = transactions.execute(status -> attempt(ticket, code));
		if (outcome.error() != null) {
			throw outcome.error();   // sayaçlar commit edildikten sonra
		}
		return outcome.result();
	}

	private record Outcome(LoginService.Result result, ApiException error) {
	}

	private Outcome attempt(String ticket, String code) {
		Instant now = clock.instant();
		MfaTickets.Ticket valid = tickets.lockValid(ticket, now).orElseThrow(() -> ApiException.of(Code.AUTH_MFA_REQUIRED));
		Users.Account account = users.find(valid.userId()).orElseThrow(() -> ApiException.of(Code.AUTH_MFA_REQUIRED));
		if (account.status() == Users.Status.DISABLED) {
			throw ApiException.of(Code.ACCOUNT_DISABLED);
		}
		if (account.status() == Users.Status.CLOSED) {
			throw ApiException.of(Code.AUTH_MFA_REQUIRED);
		}
		Optional<Instant> lockedUntil = users.findByEmail(account.email()).map(Users.Credentials::lockedUntil)
			.filter(until -> until.isAfter(now));
		if (lockedUntil.isPresent()) {
			throw ApiException.accountLocked(lockedUntil.get(), now);
		}
		Users.Totp totp = users.lockTotp(valid.userId());
		if (totp.encryptedSecret() == null) {
			throw ApiException.invalidStateTransition("NOT_ENROLLED", "VERIFIED");   // önce ID-03
		}
		OptionalLong step = Totp.verify(cipher.decrypt(totp.encryptedSecret()), code, now, totp.lastUsedStep());
		if (step.isEmpty()) {
			tickets.recordFailure(valid.id());
			Optional<Instant> locked = users.recordFailure(valid.userId(), now, now.plus(LoginService.LOCK_DURATION));
			return new Outcome(null, locked.map(until -> ApiException.accountLocked(until, now))
				.orElseGet(() -> ApiException.validation(List.of(new FieldError("code", "INVALID_CODE", "Doğrulama kodu hatalı")))));
		}
		tickets.use(valid.id(), now);
		users.acceptTotp(valid.userId(), step.getAsLong(), now);
		users.resetFailures(valid.userId(), now);
		// Rol bilet süresi içinde kaldırıldıysa MFA'lı oturum açılmaz (web: web rolü yok; Android: artık ADM/KOR değil,
		// kullanıcı yeniden giriş yapınca gönüllü oturumu alır).
		boolean stillAllowed = valid.client() == ClientType.WEB
				? webAccess.webLogin(valid.userId()) != WebAccess.WebLogin.NOT_ALLOWED
				: webAccess.mobileLogin(valid.userId()) == WebAccess.WebLogin.MFA_REQUIRED;
		if (!stillAllowed) {
			throw ApiException.of(Code.CLIENT_NOT_ALLOWED);
		}
		ClientType client = valid.client();
		RefreshTokens.Issued refresh = refreshTokens.startSession(valid.userId(), client, valid.installationId(), true, now);
		AccessTokens.Issued access = accessTokens.issue(valid.userId(), refresh.sessionId(), client, true, now);
		return new Outcome(LoginService.Result.session(client, account.mustChangePassword(), access, refresh, true), null);
	}

}
