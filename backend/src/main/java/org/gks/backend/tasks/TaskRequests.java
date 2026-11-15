package org.gks.backend.tasks;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * backend-api-design.md §9.3 basit istek şemaları. {@code TaskCreateRequest} ve {@code TaskUpdateRequest} alan
 * varlığı (yok / {@code null} / değer) ayrımı gerektirdiği için {@link TaskValidation} ile JSON ağacından okunur.
 * Bilinmeyen alanlar genel kuralla reddedilir (§3).
 */
final class TaskRequests {

	private TaskRequests() {
	}

	record TaskPublishRequest(Integer version) {
	}

	record TaskStateRequest(TaskState targetState) {
	}

	record AssignmentAddRequest(UUID userId) {
	}

	/** {@code respondedAtDevice} bilgi amaçlıdır; sunucu zamanı esastır (AC-14.1). DB'de karşılığı yoktur. */
	record AssignmentResponseRequest(OffsetDateTime respondedAtDevice) {
	}

}
