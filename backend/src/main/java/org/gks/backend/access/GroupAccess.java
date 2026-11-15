package org.gks.backend.access;

import java.util.Set;
import java.util.UUID;

/**
 * Grup kaynakları için {@code can()} (ADR-002, authorization.md §2, §5; backend-api-design.md §8.1).
 * <p>
 * Gerçekleştirme: {@link RoleBasedGroupAccess} (K1 rol atamaları + çağıranın verdiği K2 üyelik rolü).
 */
public interface GroupAccess {

	/**
	 * @param operationId    grubun operasyonu (kapsam)
	 * @param membershipRole öznenin bu gruptaki aktif rolü (K2); üye değilse veya grup arşivliyse {@code null}
	 */
	Decision can(Subject subject, GroupAction action, UUID operationId, GroupRole membershipRole);

	/** Liste ucu (GR-02) için kapsam; filtre sorgu düzeyinde uygulanır (authorization.md §6). */
	ListScope listScope(Subject subject);

	/** authorization.md §5 izinleri. */
	enum GroupAction {
		/** {@code group.create.operational}: yalnızca K1 (KOR kendi operasyonu; ADM yalnızca KOR olarak). */
		CREATE,
		/** {@code group.view}: K1 kapsamı veya K2 aktif üyelik. */
		VIEW,
		/** {@code group.update} açıklama: K1 veya K2 {@code LEADER}. */
		UPDATE,
		/** {@code group.update} ad: yalnızca KOR (§8.2 {@code GroupUpdateRequest}). */
		RENAME,
		/** {@code meeting_point.update}: K1 (KOR kendi operasyonu) veya K2 {@code LEADER} (K-08). */
		UPDATE_MEETING_POINT,
		/** {@code membership.add}: yalnızca K1; {@code LEADER} hiçbir zaman (AC-09.6). */
		ADD_MEMBER,
		/** {@code membership.remove}: yalnızca K1. */
		REMOVE_MEMBER,
		/** {@code membership.role.update} (lider atama): yalnızca K1. */
		CHANGE_LEADER
	}

	/**
	 * Öznenin görebileceği gruplar: K1 kapsamı (tüm operasyonlar veya belirli operasyonlar) ve K2 aktif
	 * üyeliklerinin grupları.
	 */
	record ListScope(boolean allOperations, Set<UUID> operationIds, boolean memberGroups) {

		public static final ListScope NONE = new ListScope(false, Set.of(), false);

		public ListScope {
			operationIds = Set.copyOf(operationIds);
		}

	}

}
