package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * authorization.md §5.1 grup izinleri. K1 izinleri yalnızca kurumsal kapıdan (MFA'lı web) geçer; K2 ({@code LEADER})
 * izinleri Android'de de kullanılabilir (LD-38). Grup lideri hiçbir zaman üye ekleyip çıkaramaz (AC-09.6).
 */
@Component
class RoleBasedGroupAccess implements GroupAccess {

	private final RoleAssignmentStore store;

	RoleBasedGroupAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision can(Subject subject, GroupAction action, UUID operationId, GroupRole membershipRole) {
		Grants grants = store.grants(subject.userId());
		Decision coordinator = Grants.corporate(subject, grants.has(Role.KOR, operationId), Role.KOR);
		return switch (action) {
			// group.create.operational, membership.*: yalnızca K1 (KOR kendi operasyonu; ADM yalnızca KOR olarak).
			case CREATE, ADD_MEMBER, REMOVE_MEMBER, CHANGE_LEADER, RENAME -> coordinator;
			// group.view: K1 kapsamı (ADM salt-okur, KOR) veya K2 aktif üyelik.
			case VIEW -> Decision.anyOf(coordinator, Grants.corporate(subject, grants.has(Role.ADM), Role.ADM),
					membershipRole != null ? Decision.ALLOW : Decision.FORBIDDEN);
			// group.update (açıklama), meeting_point.update: K1 veya K2 LEADER (K-08).
			case UPDATE, UPDATE_MEETING_POINT -> Decision.anyOf(coordinator,
					membershipRole == GroupRole.LEADER ? Decision.ALLOW : Decision.FORBIDDEN);
		};
	}

	@Override
	public ListScope listScope(Subject subject) {
		Grants grants = store.grants(subject.userId());
		boolean admin = Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).allowed();
		Set<UUID> coordinated = Grants.corporate(subject, grants.has(Role.KOR), Role.KOR).allowed()
				? grants.operations(Role.KOR) : Set.of();
		return new ListScope(admin, coordinated, true);
	}

}
