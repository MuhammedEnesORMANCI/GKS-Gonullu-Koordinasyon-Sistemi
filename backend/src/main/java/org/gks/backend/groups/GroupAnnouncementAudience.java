package org.gks.backend.groups;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.notifications.NotificationDirectories;
import org.springframework.stereotype.Component;

/** Duyuru hedef kitlesi: yalnızca operasyonun arşivlenmemiş gruplarının aktif üyeleri ({@link GroupMembers}). */
@Component
class GroupAnnouncementAudience implements NotificationDirectories.GroupAudience {

	private final GroupMembers members;

	GroupAnnouncementAudience(GroupMembers members) {
		this.members = members;
	}

	@Override
	public Map<UUID, List<UUID>> activeMembers(UUID operationId, Collection<UUID> groupIds) {
		return groupIds.isEmpty() ? Map.of() : members.activeMembers(operationId, groupIds);
	}

}
