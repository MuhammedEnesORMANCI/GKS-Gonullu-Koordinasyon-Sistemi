package org.gks.backend.identity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.access.EffectivePermissions;
import org.gks.backend.access.GroupRole;
import org.gks.backend.access.Role;
import org.gks.backend.access.RoleAssignmentView;
import org.gks.backend.access.RoleAssignments;
import org.gks.backend.access.Subject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ID-11 {@code GET /me/context} (backend §5 {@code MeContext}): kullanıcı, oturum, aktif roller, KOR veya katılımcı
 * olduğu açık operasyonlar ({@code PLANNED}/{@code ACTIVE}/(G) {@code SUSPENDED}), grupları ve <b>bu oturumun</b> etkin
 * izinleri. {@code must_change_password} iken de izinlidir.
 */
@Service
class MeContextService {

	private static final Set<String> OPEN_STATES = Set.of("PLANNED", "ACTIVE", "SUSPENDED");

	private final Users users;
	private final RefreshTokens refreshTokens;
	private final RoleAssignments roles;
	private final EffectivePermissions permissions;
	private final MeContextSources.Operations operations;
	private final MeContextSources.Groups groups;
	private final InvitationService invitations;

	MeContextService(Users users, RefreshTokens refreshTokens, RoleAssignments roles, EffectivePermissions permissions,
			MeContextSources.Operations operations, MeContextSources.Groups groups, InvitationService invitations) {
		this.invitations = invitations;
		this.users = users;
		this.refreshTokens = refreshTokens;
		this.roles = roles;
		this.permissions = permissions;
		this.operations = operations;
		this.groups = groups;
	}

	record MeContext(UserView user, SessionView session, List<RoleAssignmentView> roles, List<MyOperation> operations,
			List<MeContextSources.GroupMembership> groups, List<InvitationService.InvitationView> pendingInvitations,
			EffectivePermissions.View permissions) {
	}

	record UserView(UUID id, String displayName, String fullName) {
	}

	record SessionView(UUID sessionId, ClientType clientType, boolean mfaVerified, Instant expiresAt,
			boolean mustChangePassword) {
	}

	record MyOperation(UUID id, String name, String state, List<Role> myRoles) {
	}

	@Transactional(readOnly = true)
	MeContext of(AuthenticatedSession session) {
		Users.Account account = users.find(session.userId()).orElseThrow(() -> ApiException.of(Code.AUTH_UNAUTHENTICATED));
		Map<UUID, Set<Role>> operationRoles = roles.operationRoles(session.userId());
		Map<UUID, MeContextSources.OperationSummary> summaries = operations.find(operationRoles.keySet());
		List<MyOperation> myOperations = operationRoles.entrySet().stream()
			.filter(entry -> summaries.containsKey(entry.getKey()))
			.filter(entry -> OPEN_STATES.contains(summaries.get(entry.getKey()).state()))
			.map(entry -> {
				MeContextSources.OperationSummary op = summaries.get(entry.getKey());
				return new MyOperation(op.id(), op.name(), op.state(), entry.getValue().stream().sorted().toList());
			})
			.sorted((a, b) -> b.id().compareTo(a.id()))
			.toList();
		List<MeContextSources.GroupMembership> memberships = groups.activeMemberships(session.userId());
		Map<UUID, GroupRole> groupRoles = new HashMap<>();
		memberships.forEach(m -> groupRoles.put(m.id(), GroupRole.valueOf(m.role())));
		Subject subject = new Subject(session.userId(), session.webSession(), session.mfaVerified());
		Instant expiresAt = refreshTokens.sessionExpiry(session.sessionId()).orElse(null);
		return new MeContext(new UserView(account.id(), account.displayName(), account.fullName()),
				new SessionView(session.sessionId(), session.client(), session.mfaVerified(), expiresAt,
						account.mustChangePassword()),
				roles.activeFor(session.userId()), myOperations, memberships, invitations.pendingFor(session.userId()),
				permissions.of(subject, groupRoles));
	}

}
