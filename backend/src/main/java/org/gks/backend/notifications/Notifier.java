package org.gks.backend.notifications;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bildirim üretiminin tek giriş noktası (communication.md §1.1 "İş kaydı + NotificationRequested tek işlem").
 * <p>
 * Kaynak modül (tasks, groups, identity) bunu <b>kendi iş işleminin içinde</b> çağırır; uygulama işlemsiz çağrıyı
 * reddeder ({@code MANDATORY}). Böylece iş işlemi geri alınırsa bildirim ve teslim kayıtları da geri alınır.
 * Aynı {@link NotificationRequest#idempotencyKey()} ile ikinci çağrı yeni kayıt üretmez (§6.5).
 */
public interface Notifier {

	/** @return oluşan bildirimin kimliği; anahtar daha önce kullanılmışsa boş */
	java.util.Optional<UUID> notify(NotificationRequest request);

	/**
	 * Aynı bildirimi seçili alıcılarda yeniden öne çıkarır: gelen kutusu kaydı okunmamış olur (AL-10 yeniden
	 * gönderim; push turu FCM ile eklenecek). Çağıranın işleminde çalışır.
	 */
	void resurface(UUID notificationId, Collection<UUID> users);

	enum Category { ALERT, TASK, ANNOUNCEMENT, MEETING_POINT, OPERATION_INVITE, SYSTEM }

	/** backend-api-design.md §12.4 {@code NotificationKind} (yalnızca bu aşamada üretilenler + duyuru). */
	enum Kind {
		TASK_ASSIGNED, TASK_UPDATED, TASK_CANCELLED, TASK_CAPACITY_FULL, TASK_ASSIGNMENT_REMOVED,
		MEETING_POINT_UPDATED, GROUP_MEMBER_ADDED, ANNOUNCEMENT, OPERATION_INVITE, ALERT_SENT, ALERT_CANCELLED,
		ALERT_CLOSED
	}

	/** communication.md §1.2. {@code CRITICAL} ayrı kuyruktan iletilir. */
	enum Priority { CRITICAL, HIGH, NORMAL, LOW }

	/** İstemcinin bağlantıya çevirdiği kaynak ({@code NotificationInboxItem.link}). */
	enum SourceType { TASK, GROUP, OPERATION, INVITATION, ALERT }

	/**
	 * @param idempotencyKey kaynak olay anahtarı, ör. {@code task:{id}:PUBLISHED}
	 * @param audience       {@code audience_expr} (ör. {@code {"users": [...]}}, {@code {"operation": id}})
	 * @param recipients     çözülmüş alıcılar (anlık görüntü); boşsa bildirim yazılmaz
	 * @param createdBy      gönderen; sistem bildiriminde {@code null}
	 */
	record NotificationRequest(String idempotencyKey, Category category, Kind kind, Priority priority, String title,
			String body, SourceType sourceType, UUID sourceId, UUID operationId, Map<String, Object> audience,
			Set<UUID> recipients, UUID createdBy) {

		public NotificationRequest {
			Objects.requireNonNull(idempotencyKey, "idempotencyKey");
			Objects.requireNonNull(category, "category");
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(priority, "priority");
			Objects.requireNonNull(title, "title");
			if ((sourceType == null) != (sourceId == null)) {
				throw new IllegalArgumentException("sourceType ve sourceId birlikte verilir");
			}
			audience = Map.copyOf(audience);
			recipients = Set.copyOf(new LinkedHashSet<>(recipients));
			title = title.length() > 200 ? title.substring(0, 199) + "…" : title;
		}

		/** Kişi hedefli bildirim (görev ataması, davet): {@code audience_expr = {"users": [...]}}. */
		public static NotificationRequest toUsers(String idempotencyKey, Category category, Kind kind, Priority priority,
				String title, String body, SourceType sourceType, UUID sourceId, UUID operationId,
				Collection<UUID> users, UUID createdBy) {
			Set<UUID> recipients = new LinkedHashSet<>(users);
			return new NotificationRequest(idempotencyKey, category, kind, priority, title, body, sourceType, sourceId,
					operationId, Map.of("users", recipients.stream().map(UUID::toString).toList()), recipients, createdBy);
		}
	}

}
