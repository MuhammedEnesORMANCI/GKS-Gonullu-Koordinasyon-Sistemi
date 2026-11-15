package org.gks.backend.organization;

import java.util.Map;
import java.util.Set;

/** Operasyon durumu ve geçiş tablosu (backend-api-design.md §7.2, AC-08.1). */
public enum OperationState {

	PLANNED, ACTIVE, SUSPENDED, COMPLETED, CANCELLED;

	private static final Map<OperationState, Set<OperationState>> TRANSITIONS = Map.of(
			PLANNED, Set.of(ACTIVE, CANCELLED),
			ACTIVE, Set.of(SUSPENDED, COMPLETED, CANCELLED),
			SUSPENDED, Set.of(ACTIVE, COMPLETED, CANCELLED),
			COMPLETED, Set.of(),
			CANCELLED, Set.of());

	/** {@code SUSPENDED} Genişletilmiş MVP'dedir; Çekirdek'te bu duruma geçilmez (DB §4.3). */
	boolean canTransitionTo(OperationState target) {
		return target != SUSPENDED && TRANSITIONS.get(this).contains(target);
	}

	public boolean isClosed() {
		return this == COMPLETED || this == CANCELLED;
	}

}
