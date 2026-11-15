package org.gks.backend.access;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** TD-01: katılım = aktif {@code GON/OPERATION} ataması. */
@Component
class RoleBasedParticipation implements Participation {

	private final RoleAssignmentStore store;

	RoleBasedParticipation(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public boolean isParticipant(UUID operationId, UUID userId) {
		return store.isParticipant(operationId, userId);
	}

	@Override
	public List<UUID> participants(UUID operationId) {
		return store.participants(operationId);
	}

}
