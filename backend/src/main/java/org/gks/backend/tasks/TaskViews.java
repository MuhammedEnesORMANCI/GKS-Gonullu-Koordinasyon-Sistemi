package org.gks.backend.tasks;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** backend-api-design.md §9.3 yanıt şemaları. Boş isteğe bağlı alanlar yazılmaz (§3.1). */
final class TaskViews {

	private TaskViews() {
	}

	/** §12.3 {@code UserRef}: her izdüşümde güvenli en dar kişi gösterimi. */
	record UserRef(UUID id, String displayName) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TimeWindow(Instant start, Instant end) {
	}

	record TaskTargets(List<UUID> userIds, List<UUID> groupIds) {
	}

	/** GON/LDR izdüşümünde öznenin bu görevdeki ataması (§9.3 "{@code acceptedCount}/{@code capacity} + {@code myAssignment}"). */
	record MyAssignmentRef(UUID id, AssignmentStatus status, Instant respondBy, boolean overdue) {
	}

	/**
	 * {@code pendingCount}/{@code overdueCount} yalnızca KOR/ADM (BOŞLUK-3); {@code myAssignment} yalnızca gönüllü
	 * izdüşümünde. {@code operationId}/{@code operationName} yalnızca {@link MyAssignmentView} içinde yazılır.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TaskSummary(UUID id, UUID operationId, String operationName, String title, TaskPriority priority,
			TaskState state, int acceptedCount, Integer pendingCount, Integer overdueCount, Integer capacity,
			TimeWindow timeWindow, Instant createdAt, MyAssignmentRef myAssignment) {
	}

	/** {@code targets} ve {@code history} yalnızca KOR/ADM. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TaskView(UUID id, String title, TaskPriority priority, TaskState state, int acceptedCount,
			Integer pendingCount, Integer overdueCount, Integer capacity, TimeWindow timeWindow, Instant createdAt,
			MyAssignmentRef myAssignment, String description, String meetingPoint, TaskTargets targets,
			int responseWindowMinutes, int version, UserRef createdBy, Instant publishedAt,
			List<TaskStateChange> history) {
	}

	/** AC-15.2. */
	record TaskStateChange(TaskState state, UserRef changedBy, Instant changedAt) {
	}

	/** Kişi alanı bu aşamada yalnızca {@code UserRef}; role göre geniş izdüşüm (§3.8) access ile gelecek. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AssignmentView(UUID id, UUID taskId, UserRef user, AssignmentStatus status, Instant respondBy,
			boolean overdue, Instant respondedAt, UserRef removedBy) {
	}

	record MyAssignmentView(UUID id, AssignmentStatus status, Instant respondBy, boolean overdue, TaskSummary task) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Page<T>(List<T> items, String nextCursor) {
	}

}
