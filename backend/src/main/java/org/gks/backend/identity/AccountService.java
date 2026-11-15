package org.gks.backend.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/** ID-08 parola değiştirme, ID-09 / ID-10 kendi profili (backend-api-design.md §5; AC-03.5, AC-04.1 – 04.3). */
@Service
class AccountService {

	/** {@code MeProfileUpdate} dışındaki görünüm alanları {@code READ_ONLY} (AC-04.2). */
	private static final Set<String> READ_ONLY = Set.of("id", "email", "status", "roles", "mfaEnrolled", "createdAt");
	private static final Set<String> FIELDS = Set.of("fullName", "displayName", "phone");

	private final Users users;
	private final PasswordPolicy passwordPolicy;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokens refreshTokens;
	private final AccessTokens accessTokens;
	private final Clock clock;

	AccountService(Users users, PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder,
			RefreshTokens refreshTokens, AccessTokens accessTokens, Clock clock) {
		this.accessTokens = accessTokens;
		this.users = users;
		this.passwordPolicy = passwordPolicy;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokens = refreshTokens;
		this.clock = clock;
	}

	/** §5 {@code MeProfile}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MeProfile(UUID id, String email, String fullName, String displayName, String status, String phone,
			boolean mfaEnrolled, Instant createdAt) {
	}

	/** Parolalar metne dönüşmez. */
	record PasswordChangeRequest(String currentPassword, String newPassword) {
		@Override
		public String toString() {
			return "PasswordChangeRequest[***]";
		}
	}

	/**
	 * ID-08: mevcut parola doğrulanır (hatalıysa {@code 400 currentPassword INVALID}; 401 kullanılmaz, S-03), yeni
	 * parola kurallara uyar ve mevcutla aynı olamaz. Zorunlu değişim işareti kalkar; bu oturum dışındaki tüm oturumlar
	 * iptal edilir. Belirteçler parola bilgisi taşımadığından bu oturum geçerli kalır.
	 */
	@Transactional
	SessionService.Rotation changePassword(UUID userId, UUID sessionId, PasswordChangeRequest request) {
		List<FieldError> errors = new ArrayList<>();
		if (request == null || request.currentPassword() == null || request.currentPassword().isEmpty()) {
			errors.add(new FieldError("currentPassword", "REQUIRED", "Mevcut parola zorunludur"));
		}
		if (request == null || request.newPassword() == null || request.newPassword().isEmpty()) {
			errors.add(new FieldError("newPassword", "REQUIRED", "Yeni parola zorunludur"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		Users.Account account = users.find(userId).orElseThrow(() -> ApiException.of(Code.AUTH_UNAUTHENTICATED));
		if (!passwordEncoder.matches(request.currentPassword(), account.passwordHash())) {
			throw ApiException.validation(List.of(new FieldError("currentPassword", "INVALID", "Mevcut parola hatalı")));
		}
		passwordPolicy.check("newPassword", request.newPassword(), account.email()).ifPresent(errors::add);
		if (errors.isEmpty() && passwordEncoder.matches(request.newPassword(), account.passwordHash())) {
			errors.add(new FieldError("newPassword", "NOT_ALLOWED_VALUE", "Yeni parola mevcut parolayla aynı olamaz"));
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		Instant now = clock.instant();
		RefreshTokens.TokenRow current = refreshTokens.lockLiveHead(sessionId, now)
			.orElseThrow(() -> ApiException.of(Code.AUTH_SESSION_REVOKED));
		users.changePassword(userId, passwordEncoder.encode(request.newPassword()), now);
		// Mevcut oturum dahil tüm oturumlar iptal: parola öncesi erişim/yenileme belirteçleri hemen geçersiz (LD-47).
		refreshTokens.revokeUserSessions(userId, null, "PASSWORD_CHANGED", now);
		RefreshTokens.Issued refresh = refreshTokens.continueAsNewSession(current, now);
		AccessTokens.Issued access = accessTokens.issue(userId, refresh.sessionId(), current.client(),
				current.mfaVerified(), now);
		return new SessionService.Rotation(current.client(), access, refresh);
	}

	/** ID-09. */
	@Transactional(readOnly = true)
	MeProfile profile(UUID userId) {
		Users.Account account = users.find(userId).orElseThrow(() -> ApiException.of(Code.AUTH_UNAUTHENTICATED));
		return new MeProfile(account.id(), account.email(), account.fullName(), account.displayName(),
				account.status().name(), account.phone(), account.mfaEnrolled(), account.createdAt());
	}

	/** ID-10: kısmi (LD-52); {@code phone: null} siler, zorunlu alana {@code null} → {@code REQUIRED}. */
	@Transactional
	MeProfile updateProfile(UUID userId, JsonNode body) {
		if (body == null || !body.isObject()) {
			throw ApiException.validation(List.of());
		}
		List<FieldError> errors = new ArrayList<>();
		for (String field : body.propertyNames()) {
			if (READ_ONLY.contains(field)) {
				errors.add(new FieldError(field, "READ_ONLY", "Bu alan değiştirilemez"));
			}
			else if (!FIELDS.contains(field)) {
				errors.add(new FieldError(field, "UNKNOWN_FIELD", "Bu alan tanınmıyor"));
			}
		}
		Users.Account account = users.find(userId).orElseThrow(() -> ApiException.of(Code.AUTH_UNAUTHENTICATED));
		String fullName = account.fullName();
		String displayName = account.displayName();
		String phone = account.phone();
		if (body.has("fullName")) {
			fullName = UserAccounts.text("fullName", textOrNull(body.get("fullName")), 2, 100, errors);
		}
		if (body.has("displayName")) {
			displayName = UserAccounts.text("displayName", textOrNull(body.get("displayName")), 2, 40, errors);
		}
		if (body.has("phone")) {
			String value = textOrNull(body.get("phone"));
			phone = value == null || value.isBlank() ? null : value.strip();
			if (phone != null && !UserAccounts.PHONE.matcher(phone).matches()) {
				errors.add(new FieldError("phone", "INVALID_FORMAT", "Telefon E.164 biçiminde olmalıdır"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		users.updateProfile(userId, fullName, displayName, phone, clock.instant());
		return profile(userId);
	}

	private static String textOrNull(JsonNode node) {
		return node == null || node.isNull() ? null : node.isString() ? node.stringValue() : node.toString();
	}

}
