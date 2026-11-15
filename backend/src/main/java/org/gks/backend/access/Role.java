package org.gks.backend.access;

/** backend-api-design.md §12.4 {@code Role}; LDR rol değildir (grup üyelik rolüdür, {@link GroupRole}). */
public enum Role {

	ADM(ScopeType.ORGANIZATION), KOR(ScopeType.OPERATION), GON(ScopeType.OPERATION), TEK(ScopeType.ORGANIZATION),
	UYM(ScopeType.ORGANIZATION);

	/** Geçerli rol–kapsam çifti (backend §6, DB {@code ck_role_assignments_role_scope}). */
	public final ScopeType scopeType;

	Role(ScopeType scopeType) {
		this.scopeType = scopeType;
	}

	/** Web oturumu açabilen roller (LD-03). */
	boolean webRole() {
		return this != GON;
	}

	/**
	 * Bu rolden türeyen kurumsal izinler MFA'lı oturum ister (K-19, LD-01): web'de TOTP, Android'de de TOTP ile açılan
	 * 12 sa mutlak yönetim oturumu (ADR-014). Yalnızca bu roller mobilde yönetim yapabilir.
	 */
	boolean requiresMfa() {
		return this == ADM || this == KOR;
	}

}
