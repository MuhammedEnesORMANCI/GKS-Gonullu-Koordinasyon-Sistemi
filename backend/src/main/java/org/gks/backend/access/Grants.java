package org.gks.backend.access;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bir kullanıcının aktif rol atamaları (K1). Her {@code can()} çağrısında veritabanından okunur
 * ({@code ix_role_assignments_user_active}); rol kaldırılınca erişim bir sonraki istekte biter (AC-05.2).
 */
record Grants(List<Grant> grants) {

	record Grant(Role role, UUID scopeId) {
	}

	boolean has(Role role) {
		return grants.stream().anyMatch(g -> g.role() == role);
	}

	boolean has(Role role, UUID operationId) {
		return operationId != null && grants.stream()
			.anyMatch(g -> g.role() == role && g.scopeId().equals(operationId));
	}

	Set<UUID> operations(Role role) {
		return grants.stream().filter(g -> g.role() == role).map(Grant::scopeId).collect(Collectors.toSet());
	}

	/**
	 * Kurumsal bir izin bu rolden türeyebiliyorsa istemci ve MFA kapısı (§2.4, ADR-014):
	 * <ul>
	 * <li>Web: ADM/KOR için {@code mfaVerified}; TEK/UYM MFA istemez (LD-01).</li>
	 * <li>Android: yalnızca MFA zorunlu roller (ADM/KOR) ve yalnızca {@code mfaVerified} yönetim oturumu
	 * (12 sa mutlak). MFA'sız rollerin (TEK/UYM) kurumsal izinleri mobile taşınmaz; MFA'sız (30 gün kayan)
	 * gönüllü oturumu hiçbir kurumsal izin taşımaz.</li>
	 * </ul>
	 * Rol yoksa {@code FORBIDDEN}; istemci/MFA koşulu sağlanmazsa {@code CLIENT_NOT_ALLOWED}.
	 */
	static Decision corporate(Subject subject, boolean hasRole, Role role) {
		if (!hasRole) {
			return Decision.FORBIDDEN;
		}
		boolean allowed = subject.webSession()
				? !role.requiresMfa() || subject.mfaVerified()
				: role.requiresMfa() && subject.mfaVerified();
		return allowed ? Decision.ALLOW : Decision.CLIENT_NOT_ALLOWED;
	}

}
