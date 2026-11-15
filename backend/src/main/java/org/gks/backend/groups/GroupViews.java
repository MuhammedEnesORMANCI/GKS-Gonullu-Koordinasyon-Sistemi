package org.gks.backend.groups;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.gks.backend.access.GroupRole;

import com.fasterxml.jackson.annotation.JsonInclude;

/** backend-api-design.md §8.2 yanıt şemaları. Boş isteğe bağlı alanlar yazılmaz (§3). */
final class GroupViews {

	private GroupViews() {
	}

	/** §3 {@code UserRef}: her izdüşümde güvenli en dar kişi gösterimi. */
	record UserRef(UUID id, String displayName) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record GroupSummary(UUID id, String name, UUID operationId, String operationName, UserRef leader, int memberCount,
			boolean archived, GroupRole myRole) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record GroupView(UUID id, String name, UUID operationId, String operationName, UserRef leader, int memberCount,
			boolean archived, GroupRole myRole, String description, List<GroupMemberView> members,
			MeetingPoint meetingPoint, Instant createdAt, String type, String joinPolicy) {
	}

	/** Kişi alanı bu aşamada yalnızca {@code UserRef}; role göre geniş izdüşüm (§3.8) access ile gelecek. */
	record GroupMemberView(UserRef user, GroupRole role, Instant joinedAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MeetingPoint(String address, Double lat, Double lon, String description, Instant time, UserRef updatedBy,
			Instant updatedAt) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record GroupPage(List<GroupSummary> items, String nextCursor) {
	}

}
