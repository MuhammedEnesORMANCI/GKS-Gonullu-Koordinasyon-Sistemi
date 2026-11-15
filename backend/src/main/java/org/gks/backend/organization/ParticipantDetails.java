package org.gks.backend.organization;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OR-06 ve {@code OperationView} özetleri için diğer modüllerden gelen bilgiler. groups ve tasks zaten organization'a
 * bağımlı olduğundan organization onlara bağımlı olamaz (döngü); bu arayüzleri onlar gerçekleştirir (ADR-001).
 */
public final class ParticipantDetails {

	private ParticipantDetails() {
	}

	/** groups gerçekleştirir. */
	public interface Groups {

		/** Kişilerin bu operasyondaki arşivlenmemiş grupları. */
		Map<UUID, List<GroupRef>> activeGroups(UUID operationId, Collection<UUID> userIds);

		/** {@code notInGroup} süzgeci: grubun aktif üyeleri; grup bu operasyonun arşivlenmemiş grubu değilse boş. */
		Optional<Set<UUID>> activeMembers(UUID operationId, UUID groupId);

	}

	/** tasks gerçekleştirir. */
	public interface Assignments {

		/** §7.4 {@code openAssignmentCount}: bu operasyondaki {@code PENDING} + {@code ACCEPTED} atamalar. */
		Map<UUID, Integer> openCounts(UUID operationId, Collection<UUID> userIds);

		/** {@code OperationView.pendingAssignmentCount} (KOR/ADM panel özeti, BOŞLUK-3). */
		int pendingCount(UUID operationId);

	}

	public record GroupRef(UUID id, String name) {
	}

}
