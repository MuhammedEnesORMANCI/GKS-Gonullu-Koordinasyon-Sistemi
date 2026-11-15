package org.gks.backend.groups;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** backend-api-design.md §8.2 istek şemaları. Bilinmeyen alanlar genel kuralla reddedilir (§3). */
final class GroupRequests {

	private GroupRequests() {
	}

	record GroupCreateRequest(String name, String description, UUID leaderUserId, List<UUID> memberUserIds) {
	}

	record GroupMemberAddRequest(UUID userId) {
	}

	record GroupLeaderRequest(UUID userId) {
	}

	record MeetingPointRequest(String address, Double lat, Double lon, String description, OffsetDateTime time) {
	}

}
