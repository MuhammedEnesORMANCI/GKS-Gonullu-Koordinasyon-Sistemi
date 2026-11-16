package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class RoleBasedAlertAccess implements AlertAccess {

	private final RoleAssignmentStore store;

	RoleBasedAlertAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision canManage(Subject subject, UUID operationId) {
		return Grants.coordinator(subject, store.grants(subject.userId()), operationId);
	}

	@Override
	public ViewScope viewScope(Subject subject) {
		Grants grants = store.grants(subject.userId());
		if (Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).allowed()) {
			return new ViewScope(true, Set.of());
		}
		if (Grants.corporate(subject, grants.has(Role.KOR), Role.KOR).allowed()) {
			return new ViewScope(false, grants.operations(Role.KOR));
		}
		return new ViewScope(false, Set.of());
	}

}
