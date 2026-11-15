package org.gks.backend.organization;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * §7.4 {@code OperationView}. Boş isteğe bağlı alanlar yazılmaz (§3). KOR/ADM izdüşümü (yönetim yetkisi olan)
 * koordinatörleri kimlikle, ayrıca {@code overdue} ve {@code pendingAssignmentCount} görür; katılımcı (GON/LDR)
 * {@code coordinators[]} içinde yalnızca {@code displayName} görür (OR-05). {@code activeSharingCount} ve
 * {@code openAlertCount} location ve alerts modülleriyle gelecek.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OperationView(UUID id, String name, String description, OperationState state, Instant plannedEndAt,
		Instant activatedAt, Instant closedAt, Instant createdAt, List<UserRef> coordinators, Boolean overdue,
		Integer pendingAssignmentCount) {

	/** Kapanmamış operasyon için "planlanan bitiş + 2 sa" hatırlatma eşiği (§7.4 {@code overdue}). */
	static final Duration OVERDUE_AFTER = Duration.ofHours(2);

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UserRef(UUID id, String displayName) {
	}

	static OperationView managerView(Operation operation, List<UserRef> coordinators, int pendingAssignments,
			Instant now) {
		boolean overdue = !operation.state().isClosed() && operation.plannedEndAt() != null
				&& now.isAfter(operation.plannedEndAt().plus(OVERDUE_AFTER));
		return new OperationView(operation.id(), operation.name(), operation.description(), operation.state(),
				operation.plannedEndAt(), operation.activatedAt(), operation.closedAt(), operation.createdAt(),
				coordinators, overdue, pendingAssignments);
	}

	static OperationView participantView(Operation operation, List<UserRef> coordinators) {
		List<UserRef> namesOnly = coordinators.stream().map(ref -> new UserRef(null, ref.displayName())).toList();
		return new OperationView(operation.id(), operation.name(), operation.description(), operation.state(),
				operation.plannedEndAt(), operation.activatedAt(), operation.closedAt(), operation.createdAt(),
				namesOnly, null, null);
	}

}
