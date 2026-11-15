package org.gks.backend.tasks;

import java.util.Map;
import java.util.Set;

/** Görev durumu ve geçiş tablosu (backend-api-design.md §9.2, K-12, VS-17). */
enum TaskState {

	DRAFT, PUBLISHED, IN_PROGRESS, DONE, CANCELLED;

	/** TK-04 geçişleri. {@code DRAFT → PUBLISHED} yalnızca TK-03 ile yapılır, bu tabloda yoktur. */
	private static final Map<TaskState, Set<TaskState>> TRANSITIONS = Map.of(
			DRAFT, Set.of(CANCELLED),
			PUBLISHED, Set.of(IN_PROGRESS, CANCELLED),
			IN_PROGRESS, Set.of(DONE, CANCELLED),
			DONE, Set.of(),
			CANCELLED, Set.of());

	boolean canTransitionTo(TaskState target) {
		return TRANSITIONS.get(this).contains(target);
	}

	/** Atamalar yalnızca yayındaki görevde yanıtlanır ve eklenir (AC-15.3). */
	boolean isOpen() {
		return this == PUBLISHED || this == IN_PROGRESS;
	}

}
