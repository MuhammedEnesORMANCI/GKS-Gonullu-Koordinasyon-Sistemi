package org.gks.backend.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import java.util.Map;
import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.access.WebAccess;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * ID-02 giriş (backend-api-design.md §2.3, AC-03.1, AC-03.2, LD-03).
 * <p>
 * Hesabın varlığı açık edilmez: kayıtlı olmayan e-postada da parola özeti hesaplanır, aynı hata ve aynı kilit
 * kuralı uygulanır. Hesap durumu ({@code 403 ACCOUNT_DISABLED}) yalnızca parola doğruysa söylenir.
 * İşlem sınırı bilinçli olarak yoktur: hatalı deneme sayacı, istek hata ile bitse de kalıcı olmalıdır.
 */
@Service
class LoginService {

	static final Duration LOCK_DURATION = Duration.ofMinutes(15);   // AC-03.2

	private static final Pattern INSTALLATION_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	private final Users users;
	private final UnknownAccountThrottle unknownAccounts;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokens refreshTokens;
	private final AccessTokens accessTokens;
	private final WebAccess webAccess;
	private final MfaTickets mfaTickets;
	private final Clock clock;
	private final String dummyHash;
	private final IdentityAudit audit;

	LoginService(Users users, UnknownAccountThrottle unknownAccounts, PasswordEncoder passwordEncoder,
			RefreshTokens refreshTokens, AccessTokens accessTokens, WebAccess webAccess, MfaTickets mfaTickets, Clock clock,
			IdentityAudit audit) {
		this.audit = audit;
		this.users = users;
		this.unknownAccounts = unknownAccounts;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokens = refreshTokens;
		this.accessTokens = accessTokens;
		this.webAccess = webAccess;
		this.mfaTickets = mfaTickets;
		this.clock = clock;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * Her sonuç denetime yazılır (ADR-020): başarıda {@code LOGIN_SUCCEEDED} (oturum açıldıysa; TOTP bekleyen girişte
	 * kayıt ID-04'te), başarısızlıkta {@code LOGIN_FAILED} ve neden kodu. Tanınmayan e-postada aktör boştur; e-posta
	 * yazılmaz.
	 */
	Result login(LoginRequest request) {
		validate(request);
		UUID[] actor = new UUID[1];
		try {
			Result result = authenticate(request, actor);
			if (!result.mfaRequired()) {
				audit.record(actor[0], "SELF", request.clientType(), "LOGIN_SUCCEEDED", result.refresh().sessionId(),
						Map.of("mfa", false));
			}
			return result;
		}
		catch (ApiException ex) {
			audit.recordFailure(actor[0], request.clientType(),
					Map.of("reason", ex.code().name(), "accountKnown", actor[0] != null), ex);
			throw ex;
		}
	}

	private Result authenticate(LoginRequest request, UUID[] actor) {
		String email = UserAccounts.normalizeEmail(request.email());
		Instant now = clock.instant();
		Instant lockUntil = now.plus(LOCK_DURATION);

		Optional<Users.Credentials> found = users.findByEmail(email);
		if (found.isEmpty()) {
			Optional<Instant> locked = unknownAccounts.lockedUntil(email, now);
			passwordEncoder.matches(request.password(), dummyHash);   // süre farkı hesabın varlığını göstermesin
			if (locked.isPresent()) {
				throw ApiException.accountLocked(locked.get(), now);
			}
			throw failure(unknownAccounts.recordFailure(email, now, lockUntil), now);
		}

		Users.Credentials user = found.get();
		actor[0] = user.id();
		if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
			passwordEncoder.matches(request.password(), dummyHash);
			throw ApiException.accountLocked(user.lockedUntil(), now);
		}
		if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
			throw failure(users.recordFailure(user.id(), now, lockUntil), now);
		}
		if (user.status() == Users.Status.CLOSED) {
			throw ApiException.of(Code.AUTH_INVALID_CREDENTIALS);   // kapatılmış hesap girişte yok sayılır
		}
		users.resetFailures(user.id(), now);
		if (user.status() == Users.Status.DISABLED) {
			throw ApiException.of(Code.ACCOUNT_DISABLED);
		}
		if (request.clientType() == ClientType.WEB) {
			WebAccess.WebLogin decision = webAccess.webLogin(user.id());
			if (decision == WebAccess.WebLogin.NOT_ALLOWED) {
				throw ApiException.of(Code.CLIENT_NOT_ALLOWED);   // LD-03: web rolü yok
			}
			if (decision == WebAccess.WebLogin.MFA_REQUIRED) {
				// K-19: ADM/KOR için web oturumu ancak TOTP doğrulandıktan sonra açılır (ID-04). Burada çerez ve
				// yenileme belirteci yoktur; yalnızca tek kullanımlık, 5 dk'lık bilet döner.
				return Result.mfaPending(ClientType.WEB, user.mustChangePassword(), mfaTickets.issue(user.id(), now),
						!users.lockTotp(user.id()).enrolled());
			}
		}
		else if (webAccess.mobileLogin(user.id()) == WebAccess.WebLogin.MFA_REQUIRED) {
			// ADR-014: ADM/KOR Android'de de yalnızca TOTP'den sonra (yönetim oturumu, 12 sa mutlak) oturum açar.
			// Gönüllü (MFA'sız, 30 gün) Android oturumu bu kullanıcılara verilmez; böylece uzun ömürlü oturum hiçbir
			// zaman kurumsal izin taşımaz.
			return Result.mfaPending(ClientType.ANDROID, user.mustChangePassword(),
					mfaTickets.issue(user.id(), now, ClientType.ANDROID, request.installationId()),
					!users.lockTotp(user.id()).enrolled());
		}

		RefreshTokens.Issued refresh = refreshTokens.startSession(user.id(), request.clientType(),
				request.installationId(), false, now);
		AccessTokens.Issued access = accessTokens.issue(user.id(), refresh.sessionId(), request.clientType(), false, now);
		return Result.session(request.clientType(), user.mustChangePassword(), access, refresh, false);
	}

	private static ApiException failure(Optional<Instant> lockedUntil, Instant now) {
		return lockedUntil.map(until -> ApiException.accountLocked(until, now))
			.orElseGet(() -> ApiException.of(Code.AUTH_INVALID_CREDENTIALS));
	}

	private static void validate(LoginRequest request) {
		List<FieldError> errors = new ArrayList<>();
		if (request.email() == null || request.email().isBlank()) {
			errors.add(new FieldError("email", "REQUIRED", "E-posta zorunludur"));
		}
		else if (request.email().strip().length() > 254) {
			errors.add(new FieldError("email", "TOO_LONG", "E-posta en fazla 254 karakter olabilir"));
		}
		if (request.password() == null || request.password().isEmpty()) {
			errors.add(new FieldError("password", "REQUIRED", "Parola zorunludur"));
		}
		else if (request.password().length() > 128) {
			errors.add(new FieldError("password", "TOO_LONG", "Parola en fazla 128 karakter olabilir"));
		}
		if (request.clientType() == null) {
			errors.add(new FieldError("clientType", "REQUIRED", "İstemci türü zorunludur"));
		}
		if (request.clientType() == ClientType.ANDROID) {
			if (request.installationId() == null || request.installationId().isEmpty()) {
				errors.add(new FieldError("installationId", "REQUIRED", "Kurulum kimliği zorunludur"));
			}
			else if (!INSTALLATION_ID.matcher(request.installationId()).matches()) {
				errors.add(new FieldError("installationId", "INVALID_FORMAT", "Kurulum kimliği geçersiz"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
	}

	/**
	 * Başarılı giriş: ya oturum ({@code access}/{@code refresh}) ya da TOTP bekleyen bilet ({@code mfaTicket}).
	 * Belirteç ve bilet değerleri metne dönüşmez.
	 */
	record Result(ClientType client, boolean mustChangePassword, AccessTokens.Issued access,
			RefreshTokens.Issued refresh, boolean mfaVerified, String mfaTicket, boolean mfaEnrollmentRequired) {

		static Result session(ClientType client, boolean mustChangePassword, AccessTokens.Issued access,
				RefreshTokens.Issued refresh, boolean mfaVerified) {
			return new Result(client, mustChangePassword, access, refresh, mfaVerified, null, false);
		}

		static Result mfaPending(ClientType client, boolean mustChangePassword, String ticket, boolean enrollmentRequired) {
			return new Result(client, mustChangePassword, null, null, false, ticket, enrollmentRequired);
		}

		boolean mfaRequired() {
			return mfaTicket != null;
		}

		@Override
		public String toString() {
			return "LoginResult[client=" + client + ", mfaRequired=" + mfaRequired() + "]";
		}
	}

}
