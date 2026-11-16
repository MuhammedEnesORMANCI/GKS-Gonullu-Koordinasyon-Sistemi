package org.gks.backend.access;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class RoleBasedRealtimeAccess implements RealtimeAccess {

	private final RoleAssignmentStore store;

	RoleBasedRealtimeAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision canFollowOperation(Subject subject, UUID operationId) {
		return Grants.coordinator(subject, store.grants(subject.userId()), operationId);
	}

	@Override
	public Decision canFollowOrganization(Subject subject) {
		return Grants.corporate(subject, store.grants(subject.userId()).has(Role.ADM), Role.ADM);
	}

}
