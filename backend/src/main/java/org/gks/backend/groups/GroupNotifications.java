package org.gks.backend.groups;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.notifications.Notifier;
import org.gks.backend.notifications.Notifier.Category;
import org.gks.backend.notifications.Notifier.Kind;
import org.gks.backend.notifications.Notifier.NotificationRequest;
import org.gks.backend.notifications.Notifier.Priority;
import org.gks.backend.notifications.Notifier.SourceType;
import org.springframework.stereotype.Component;

/**
 * Grup bildirimleri (communication.md §1.2: toplanma noktası değişikliği {@code HIGH}; §8.1 GR-04 "üyeye HIGH
 * bildirim", GR-08 "üyelere HIGH MEETING_POINT"). Grup servisinin işlemi içinde çağrılır; alıcılar yalnızca grubun
 * aktif üyeleridir, işlemi yapan hariç.
 */
@Component
class GroupNotifications {

	private final Notifier notifier;

	GroupNotifications(Notifier notifier) {
		this.notifier = notifier;
	}

	/** GR-01 / GR-04: gruba eklenen kişiler. */
	void membersAdded(UUID groupId, String groupName, UUID operationId, String key, Collection<UUID> users, UUID actor) {
		send(key, Category.SYSTEM, Kind.GROUP_MEMBER_ADDED, "Gruba eklendiniz: " + groupName,
				"Grup bilgilerini, liderinizi ve toplanma noktasını görmek için açın.", groupId, operationId, users, actor);
	}

	/** GR-08: toplanma noktası güncellendi (AC-12.1 – 12.3). */
	void meetingPointUpdated(UUID groupId, String groupName, UUID operationId, String address, Instant updatedAt,
			Collection<UUID> members, UUID actor) {
		send("group:" + groupId + ":MEETING_POINT:" + updatedAt.toEpochMilli(), Category.MEETING_POINT,
				Kind.MEETING_POINT_UPDATED, "Toplanma noktası güncellendi: " + groupName,
				address == null ? "Yeni toplanma noktasını görmek için açın." : "Yeni toplanma noktası: " + address, groupId,
				operationId, members, actor);
	}

	private void send(String key, Category category, Kind kind, String title, String body, UUID groupId,
			UUID operationId, Collection<UUID> recipients, UUID actor) {
		Set<UUID> users = new LinkedHashSet<>(recipients);
		users.remove(actor);
		notifier.notify(NotificationRequest.toUsers(key, category, kind, Priority.HIGH, title, body, SourceType.GROUP,
				groupId, operationId, users, actor));
	}

}
