package org.gks.backend.access;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class RoleBasedLocationAccess implements LocationAccess {

	private final RoleAssignmentStore store;

	RoleBasedLocationAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision canView(Subject subject, UUID operationId) {
		return Grants.coordinator(subject, store.grants(subject.userId()), operationId);   // KOR veya ADM (ADR-020)
	}

}
