package org.gks.backend.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class RoleBasedOperationAccess implements OperationAccess {

	private final RoleAssignmentStore store;

	RoleBasedOperationAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision canCreate(Subject subject) {
		return Grants.corporate(subject, store.grants(subject.userId()).has(Role.ADM), Role.ADM);
	}

	@Override
	public Decision canManage(Subject subject, UUID operationId) {
		return Grants.coordinator(subject, store.grants(subject.userId()), operationId);   // ADM tümü, KOR kendi (ADR-015)
	}

	/** Katılım (GON) istemciden bağımsızdır; ADM/KOR görünümü kurumsal kapıdan geçer (§2.4). */
	@Override
	public Decision canView(Subject subject, UUID operationId) {
		Grants grants = store.grants(subject.userId());
		return Decision.anyOf(grants.has(Role.GON, operationId) ? Decision.ALLOW : Decision.FORBIDDEN,
				Grants.corporate(subject, grants.has(Role.ADM), Role.ADM),
				Grants.corporate(subject, grants.has(Role.KOR, operationId), Role.KOR));
	}

	@Override
	public ViewScope viewScope(Subject subject) {
		Grants grants = store.grants(subject.userId());
		boolean admin = Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).allowed();
		Set<UUID> operations = new HashSet<>(grants.operations(Role.GON));
		if (Grants.corporate(subject, grants.has(Role.KOR), Role.KOR).allowed()) {
			operations.addAll(grants.operations(Role.KOR));
		}
		return new ViewScope(admin, operations);
	}

}
