package org.gks.backend.access;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class RoleBasedActingBasis implements ActingBasis {

	private final RoleAssignmentStore store;

	RoleBasedActingBasis(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public String operation(Subject subject, UUID operationId) {
		Grants grants = store.grants(subject.userId());
		if (grants.has(Role.KOR, operationId)) {
			return "KOR";
		}
		return grants.has(Role.ADM) ? "ADM" : "SELF";
	}

	@Override
	public String organization(Subject subject) {
		Grants grants = store.grants(subject.userId());
		if (grants.has(Role.ADM)) {
			return "ADM";
		}
		if (grants.has(Role.TEK)) {
			return "TEK";
		}
		return grants.has(Role.UYM) ? "UYM" : "SELF";
	}

}
