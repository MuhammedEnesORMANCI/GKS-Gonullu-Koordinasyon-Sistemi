package org.gks.backend.access;

import java.util.UUID;

import org.springframework.stereotype.Component;

/** LD-03: web oturumu ADM, KOR, TEK veya UYM rolü ister; ADM veya KOR rolü varsa oturum TOTP'den sonra açılır (K-19). */
@Component
class RoleBasedWebAccess implements WebAccess {

	private final RoleAssignmentStore store;

	RoleBasedWebAccess(RoleAssignmentStore store) {
		this.store = store;
	}

	@Override
	public WebLogin webLogin(UUID userId) {
		Grants grants = store.grants(userId);
		if (grants.grants().stream().anyMatch(g -> g.role().requiresMfa())) {
			return WebLogin.MFA_REQUIRED;
		}
		return grants.grants().stream().anyMatch(g -> g.role().webRole()) ? WebLogin.ALLOWED : WebLogin.NOT_ALLOWED;
	}

	@Override
	public WebLogin mobileLogin(UUID userId) {
		return store.grants(userId).grants().stream().anyMatch(g -> g.role().requiresMfa()) ? WebLogin.MFA_REQUIRED
				: WebLogin.ALLOWED;
	}

}
