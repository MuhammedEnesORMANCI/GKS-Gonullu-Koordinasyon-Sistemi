package org.gks.backend.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.access.ActingBasis;
import org.gks.backend.AuditTrail;
import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.PageCursor;
import org.gks.backend.access.Decision;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Role;
import org.gks.backend.access.RoleAssignmentView;
import org.gks.backend.access.RoleAssignments;
import org.gks.backend.access.Subject;
import org.gks.backend.access.UserAdminAccess;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Kullanıcı ve hesap yönetimi (ID-19 – ID-25; backend-api-design.md §5.1). Yetki access modülündedir
 * ({@link UserAdminAccess}); izdüşüm §3.8: ADM ve kendi operasyonundaki KOR tam, TEK yalnızca ad ve durum (AC-06.5).
 * Denetim kaydı (AC-03.5, AC-06.3, AC-07.2) audit modülü yazılınca eklenecek.
 */
@Service
class UserAdminService {

	static final int REASON_MAX = 300;
	private static final String TEMP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

	private final Users users;
	private final RefreshTokens tokens;
	private final UserAdminAccess access;
	private final Participation participation;
	private final RoleAssignments roles;
	private final MeContextSources.Groups groups;
	private final PasswordEncoder passwordEncoder;
	private final UserAdminMailer mailer;
	private final Clock clock;
	private final AuditTrail audit;
	private final ActingBasis acting;
	private final SecureRandom random = new SecureRandom();

	UserAdminService(Users users, RefreshTokens tokens, UserAdminAccess access, Participation participation,
			RoleAssignments roles, MeContextSources.Groups groups, PasswordEncoder passwordEncoder, UserAdminMailer mailer,
			Clock clock, AuditTrail audit, ActingBasis acting) {
		this.users = users;
		this.tokens = tokens;
		this.access = access;
		this.participation = participation;
		this.roles = roles;
		this.groups = groups;
		this.passwordEncoder = passwordEncoder;
		this.mailer = mailer;
		this.clock = clock;
		this.audit = audit;
		this.acting = acting;
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UserSummary(UUID id, String displayName, String status, String fullName, String email, String phone) {
	}

	record GroupRef(UUID id, String name, UUID operationId, String role) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UserDetail(UUID id, String displayName, String status, String fullName, String email, String phone,
			List<RoleAssignmentView> roles, List<GroupRef> groups, Boolean mfaEnrolled, Instant lockedUntil,
			Instant createdAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UserPage(List<UserSummary> items, String nextCursor, Long totalCount) {
	}

	// ---------------------------------------------------------------- ID-19, ID-20

	/**
	 * ID-19. Kurum geneli: ADM (tam) veya TEK (ad + durum). {@code operationId} ile: o operasyonun katılımcıları;
	 * kurum dizini izni yoksa KOR kendi operasyonunda (ADR-015 ile ADM tümünde) tam izdüşümle görür.
	 */
	@Transactional(readOnly = true)
	UserPage list(Subject subject, Users.Status status, UUID operationId, boolean includeTotal, PageCursor page) {
		UserAdminAccess.Directory directory = access.directory(subject);
		boolean full;
		Collection<UUID> ids = null;
		if (operationId == null) {
			directory.decision().require();
			full = directory.full();
		}
		else {
			if (directory.allowed()) {
				full = directory.full();
			}
			else {
				access.operationDirectory(subject, operationId).require();
				full = true;
			}
			ids = new HashSet<>(participation.participants(operationId));
		}
		List<Users.Listed> rows = users.list(status, ids, page.after(), page.limit());
		PageCursor.Slice<Users.Listed> slice = page.slice(rows, Users.Listed::id);
		boolean projectFull = full;
		List<UserSummary> items = slice.items().stream().map(r -> summary(r, projectFull)).toList();
		return new UserPage(items, slice.nextCursor(), includeTotal ? users.count(status, ids) : null);
	}

	/** ID-20: kapsam ID-19 ile aynı; KOR yalnızca kendi operasyonlarındaki rol ve grupları görür. */
	@Transactional(readOnly = true)
	UserDetail detail(Subject subject, UUID userId) {
		Users.Managed user = users.managed(userId, false).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		UserAdminAccess.Directory directory = access.directory(subject);
		if (directory.allowed()) {
			if (!directory.full()) {
				return new UserDetail(user.id(), user.displayName(), user.status().name(), null, null, null, null, null,
						null, null, null);
			}
			return new UserDetail(user.id(), user.displayName(), user.status().name(), user.fullName(), user.email(),
					user.phone(), roles.activeFor(userId), groupsOf(userId, null), user.mfaEnrolled(), user.lockedUntil(),
					user.createdAt());
		}
		Set<UUID> shared = new HashSet<>();
		Decision strongest = directory.decision();
		for (Map.Entry<UUID, Set<Role>> entry : roles.operationRoles(userId).entrySet()) {
			if (!entry.getValue().contains(Role.GON)) {
				continue;
			}
			Decision decision = access.operationDirectory(subject, entry.getKey());
			if (decision.allowed()) {
				shared.add(entry.getKey());
			}
			else if (decision == Decision.CLIENT_NOT_ALLOWED) {
				strongest = Decision.CLIENT_NOT_ALLOWED;
			}
		}
		if (shared.isEmpty()) {
			(strongest == Decision.CLIENT_NOT_ALLOWED ? Decision.CLIENT_NOT_ALLOWED : Decision.FORBIDDEN).require();
		}
		List<RoleAssignmentView> visibleRoles = roles.activeFor(userId).stream()
			.filter(r -> shared.contains(r.scopeId()))
			.toList();
		return new UserDetail(user.id(), user.displayName(), user.status().name(), user.fullName(), user.email(),
				user.phone(), visibleRoles, groupsOf(userId, shared), null, null, null);
	}

	// ---------------------------------------------------------------- ID-21 – ID-25

	/** ID-21: hesap devre dışı; tüm oturumlar iptal, cihazlar pasif (AC-06.2). Kendi hesabı {@code 403} (S-09). */
	@Transactional
	UserSummary disable(Subject subject, UUID userId, String reason) {
		if (reason != null && reason.strip().length() > REASON_MAX) {
			throw ApiException.validation(List.of(new ApiException.FieldError("reason", "TOO_LONG",
					"Gerekçe en fazla 300 karakter olabilir")));
		}
		Users.Managed user = locked(userId);
		access.canDisable(subject, userId).require();
		notSelf(subject, userId);
		if (user.status() != Users.Status.ACTIVE) {
			throw ApiException.invalidStateTransition(user.status().name(), Users.Status.DISABLED.name());
		}
		Instant now = clock.instant();
		users.setStatus(userId, Users.Status.DISABLED, now);
		tokens.deactivateUserDevices(userId, "ACCOUNT_DISABLED", now);
		tokens.revokeUserSessions(userId, null, "ACCOUNT_DISABLED", now);
		// Gerekçe metni yazılmaz; yalnızca verilip verilmediği (backend §5.2 ReasonRequest).
		record(subject, "ACCOUNT_DISABLED", userId, Map.of("reasonGiven", reason != null && !reason.isBlank()));
		return summaryFor(subject, userId);
	}

	/** ID-22: geri alma (yalnızca ADM); yalnızca {@code DISABLED} hesap. */
	@Transactional
	UserSummary enable(Subject subject, UUID userId) {
		Users.Managed user = locked(userId);
		access.canEnable(subject).require();
		if (user.status() != Users.Status.DISABLED) {
			throw ApiException.invalidStateTransition(user.status().name(), Users.Status.ACTIVE.name());
		}
		users.setStatus(userId, Users.Status.ACTIVE, clock.instant());
		record(subject, "ACCOUNT_ENABLED", userId, Map.of());
		return summaryFor(subject, userId);
	}

	/** ID-23: tüm oturumlar iptal, cihazlar pasif; sonraki istekler {@code 401} (AC-06.1, LD-47). */
	@Transactional
	void revokeSessions(Subject subject, UUID userId) {
		locked(userId);
		access.canRevokeSessions(subject).require();
		Instant now = clock.instant();
		tokens.deactivateUserDevices(userId, "LOGOUT", now);
		tokens.revokeUserSessions(userId, null, "ADMIN_REVOKE", now);
		record(subject, "SESSIONS_REVOKED", userId, Map.of());
	}

	/**
	 * ID-24: geçici parola e-postayla gider (commit sonrası), ilk girişte değişim zorunlu (AC-03.5), oturumlar iptal.
	 * Kendi parolası için {@code POST /me/password} kullanılır ({@code 403}).
	 */
	@Transactional
	void resetPassword(Subject subject, UUID userId) {
		Users.Managed user = locked(userId);
		access.canResetPassword(subject).require();
		notSelf(subject, userId);
		if (user.status() == Users.Status.CLOSED) {
			throw ApiException.invalidStateTransition(user.status().name(), "PASSWORD_RESET");
		}
		String temporary = temporaryPassword();
		Instant now = clock.instant();
		users.resetPasswordByAdmin(userId, passwordEncoder.encode(temporary), now);
		tokens.deactivateUserDevices(userId, "LOGOUT", now);
		tokens.revokeUserSessions(userId, null, "PASSWORD_CHANGED", now);
		record(subject, "PASSWORD_RESET_BY_ADMIN", userId, Map.of());   // geçici parola yazılmaz
		String email = user.email();
		String name = user.displayName();
		afterCommit(() -> mailer.sendTemporaryPassword(email, name, temporary));
	}

	/**
	 * ID-25: TOTP silinir, sonraki yönetim girişinde yeniden kurulur. Web ve (ADR-014) MFA'lı Android yönetim
	 * oturumları dahil tüm oturumlar iptal edilir. TOTP yoksa {@code 409}. Kendi MFA'sı {@code 403}.
	 */
	@Transactional
	void resetMfa(Subject subject, UUID userId) {
		Users.Managed user = locked(userId);
		access.canResetMfa(subject).require();
		notSelf(subject, userId);
		if (!user.totpPresent()) {
			throw ApiException.invalidStateTransition("NOT_ENROLLED", "MFA_RESET");
		}
		Instant now = clock.instant();
		users.clearTotp(userId, now);
		tokens.deactivateUserDevices(userId, "LOGOUT", now);
		tokens.revokeUserSessions(userId, null, "MFA_RESET", now);
		record(subject, "MFA_RESET", userId, Map.of());
	}

	// ---------------------------------------------------------------- yardımcılar

	private Users.Managed locked(UUID userId) {
		return users.managed(userId, true).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
	}

	private void record(Subject subject, String action, UUID userId, Map<String, Object> details) {
		audit.record(subject.userId(), acting.organization(subject), subject.client(), action, "USER", userId, null, details);
	}

	private static void notSelf(Subject subject, UUID userId) {
		if (subject.userId().equals(userId)) {
			throw ApiException.of(Code.FORBIDDEN);   // kaynak kuralı not.self (S-09)
		}
	}

	private UserSummary summaryFor(Subject subject, UUID userId) {
		Users.Managed user = users.managed(userId, false).orElseThrow();
		boolean full = access.directory(subject).full();
		return full ? new UserSummary(user.id(), user.displayName(), user.status().name(), user.fullName(), user.email(),
				user.phone()) : new UserSummary(user.id(), user.displayName(), user.status().name(), null, null, null);
	}

	private static UserSummary summary(Users.Listed row, boolean full) {
		return full ? new UserSummary(row.id(), row.displayName(), row.status().name(), row.fullName(), row.email(), row.phone())
				: new UserSummary(row.id(), row.displayName(), row.status().name(), null, null, null);
	}

	private List<GroupRef> groupsOf(UUID userId, Set<UUID> operations) {
		List<GroupRef> result = new ArrayList<>();
		for (MeContextSources.GroupMembership m : groups.activeMemberships(userId)) {
			if (operations == null || operations.contains(m.operationId())) {
				result.add(new GroupRef(m.id(), m.name(), m.operationId(), m.role()));
			}
		}
		return result;
	}

	/** 14 karakter, karışması kolay harfler (0/O, 1/l/I) yok; ör. {@code Hk7m-pR4t-wX2q} (PasswordPolicy en az 10). */
	String temporaryPassword() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			if (i > 0 && i % 4 == 0) {
				sb.append('-');
			}
			sb.append(TEMP_ALPHABET.charAt(random.nextInt(TEMP_ALPHABET.length())));
		}
		return sb.toString();
	}

	private static void afterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		}
		else {
			action.run();
		}
	}

}
