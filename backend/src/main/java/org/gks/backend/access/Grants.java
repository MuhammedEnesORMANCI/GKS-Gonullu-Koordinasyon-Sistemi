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
	 * Operasyonu koordine etme hakkı (ADR-015): o operasyonun KOR'u <b>veya</b> ADM. ADM, KOR ataması olmadan her
	 * operasyonda KOR'un operasyon kapsamlı tüm yönetim izinlerine sahiptir. Operasyon kapsamlı yönetim kararları
	 * yalnızca bu yöntemden (ve {@link #coordinator}) türer; kural tek yerde tutulur.
	 * <p>
	 * Acil alarm izinleri de bu yöntemden türer (ADR-018: ADM, KOR'un tüm alarm izinlerine sahiptir).
	 * <b>Kapsam dışı:</b> konum ({@code location.view}, K-15); o karar konum modülünde verilir
	 * ({@link EffectivePermissions#ADM_OPERATION}).
	 */
	boolean coordinates(UUID operationId) {
		return has(Role.KOR, operationId) || has(Role.ADM);
	}

	/**
	 * Operasyon kapsamlı yönetim izni kararı (task.manage, group.*, membership.*, meeting_point.update,
	 * announcement.send.group …): {@link #coordinates} + {@link #corporate} istemci/MFA kapısı. ADM ve KOR'un ikisi de
	 * MFA zorunlu rol olduğundan kapı aynıdır: web'de MFA, Android'de yalnızca MFA'lı yönetim oturumu (ADR-014).
	 */
	static Decision coordinator(Subject subject, Grants grants, UUID operationId) {
		return corporate(subject, grants.coordinates(operationId), Role.KOR);
	}

	/**
	 * Kurumsal bir izin bu rolden türeyebiliyorsa istemci ve MFA kapısı (§2.4, ADR-014; ADR-015 kapıyı değiştirmez):
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
