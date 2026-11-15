package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Roller her kararda veritabanından okunur (LD-55); önbellek yok. */
@Component
class RoleBasedAnnouncementAccess implements AnnouncementAccess {

	private final RoleAssignmentStore store;

	RoleBasedAnnouncementAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision canSendToOperation(Subject subject, UUID operationId) {
		return Grants.corporate(subject, store.grants(subject.userId()).has(Role.KOR, operationId), Role.KOR);
	}

	@Override
	public Decision canSendToOrganization(Subject subject) {
		return Grants.corporate(subject, store.grants(subject.userId()).has(Role.ADM), Role.ADM);
	}

	@Override
	public ListScope listScope(Subject subject) {
		Grants grants = store.grants(subject.userId());
		boolean admin = Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).allowed();
		Set<UUID> coordinated = Grants.corporate(subject, grants.has(Role.KOR), Role.KOR).allowed()
				? grants.operations(Role.KOR) : Set.of();
		return new ListScope(admin, coordinated);
	}

}
