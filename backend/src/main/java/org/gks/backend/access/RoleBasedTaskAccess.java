package org.gks.backend.access;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * authorization.md §5.4 görev izinleri. {@code task.manage}: KOR kendi operasyonunda, ADM KOR ataması olmadan
 * tüm operasyonlarda (ADR-015; MFA'lı web veya MFA'lı Android yönetim oturumu, ADR-014). Android'deki KOR yalnızca kendi atamalarını görür (§2.4).
 */
@Component
class RoleBasedTaskAccess implements TaskAccess {

	private final RoleAssignmentStore store;

	RoleBasedTaskAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public Decision can(Subject subject, TaskAction action, UUID operationId, boolean assignee) {
		return switch (action) {
			case MANAGE -> Grants.coordinator(subject, store.grants(subject.userId()), operationId);   // KOR kendi, ADM tümü
			case RESPOND -> assignee ? Decision.ALLOW : Decision.FORBIDDEN;   // task.respond: atanan kişinin kendisi
			case LIST_OWN_ASSIGNMENTS -> Decision.ALLOW;                       // self
		};
	}

	@Override
	public ViewScope viewScope(Subject subject, UUID operationId) {
		Grants grants = store.grants(subject.userId());
		if (Grants.corporate(subject, grants.has(Role.KOR, operationId), Role.KOR).allowed()
				|| Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).allowed()) {
			return ViewScope.ALL;
		}
		if (grants.has(Role.GON, operationId) || grants.has(Role.KOR, operationId)) {
			return ViewScope.ASSIGNED;
		}
		return ViewScope.NONE;
	}

}
