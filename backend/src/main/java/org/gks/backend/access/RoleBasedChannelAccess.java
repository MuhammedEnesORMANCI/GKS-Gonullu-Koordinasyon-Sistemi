package org.gks.backend.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Roller her kararda veritabanından okunur (LD-55); önbellek yok. */
@Component
class RoleBasedChannelAccess implements ChannelAccess {

	private final RoleAssignmentStore store;

	RoleBasedChannelAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public boolean coordinates(Subject subject, UUID operationId) {
		return Grants.coordinator(subject, store.grants(subject.userId()), operationId).allowed();
	}

	@Override
	public boolean allOperations(Subject subject) {
		return Grants.corporate(subject, store.grants(subject.userId()).has(Role.ADM), Role.ADM).allowed();
	}

	@Override
	public Set<UUID> coordinatedOperations(Subject subject) {
		Grants grants = store.grants(subject.userId());
		return Grants.corporate(subject, grants.has(Role.KOR), Role.KOR).allowed() ? grants.operations(Role.KOR) : Set.of();
	}

	@Override
	public Set<UUID> participatingOperations(UUID userId) {
		return store.grants(userId).operations(Role.GON);
	}

	@Override
	public List<UUID> coordinators(UUID operationId) {
		return store.activeHolders(Role.KOR, List.of(operationId)).getOrDefault(operationId, List.of());
	}

}
