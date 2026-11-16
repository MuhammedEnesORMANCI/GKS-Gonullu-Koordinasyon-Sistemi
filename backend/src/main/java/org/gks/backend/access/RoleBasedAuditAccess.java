package org.gks.backend.access;

import org.springframework.stereotype.Component;

@Component
class RoleBasedAuditAccess implements AuditAccess {

	private final RoleAssignmentStore store;

	RoleBasedAuditAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision canView(Subject subject) {
		Grants grants = store.grants(subject.userId());
		return Decision.anyOf(Grants.corporate(subject, grants.has(Role.ADM), Role.ADM),
				Grants.corporate(subject, grants.has(Role.UYM), Role.UYM));
	}

}
