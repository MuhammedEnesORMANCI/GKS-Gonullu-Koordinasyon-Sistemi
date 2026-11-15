package org.gks.backend.notifications;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.Ids;
import org.gks.backend.notifications.Notifier.NotificationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

/** {@code notifications.notifications} ve {@code notifications.deliveries} (DB §6.5, §6.6). */
@Repository
class NotificationStore {

	private final JdbcClient jdbc;
	private final JdbcTemplate batch;
	private final JsonMapper json;

	NotificationStore(JdbcClient jdbc, JdbcTemplate batch, JsonMapper json) {
		this.jdbc = jdbc;
		this.batch = batch;
		this.json = json;
	}

	// ---------------------------------------------------------------- yazma

	/** @return {@code false}: aynı kaynak olay anahtarı zaten var (tekrar; sessizce yok sayılır, §6.5) */
	boolean insertNotification(UUID id, NotificationRequest r, Instant now) {
		return jdbc.sql("""
				insert into notifications.notifications
				       (id, category, kind, priority, title, body, source_type, source_id, operation_id, audience_expr,
				        recipient_count, created_by, created_at, idempotency_key)
				values (:id, :category, :kind, :priority, :title, :body, :sourceType, :sourceId, :operationId,
				        cast(:audience as jsonb), :count, :createdBy, :now, :key)
				on conflict (idempotency_key) do nothing""")
			.param("id", id).param("category", r.category().name()).param("kind", r.kind().name())
			.param("priority", r.priority().name()).param("title", r.title()).param("body", r.body())
			.param("sourceType", r.sourceType() == null ? null : r.sourceType().name()).param("sourceId", r.sourceId())
			.param("operationId", r.operationId()).param("audience", json.writeValueAsString(r.audience()))
			.param("count", r.recipients().size()).param("createdBy", r.createdBy())
			.param("now", Timestamp.from(now)).param("key", r.idempotencyKey())
			.update() == 1;
	}

	/** Gelen kutusu satırları {@code SENT} olarak oluşur (§6.6); bildirim başına kullanıcıda tek satır. */
	void insertInApp(UUID notificationId, Collection<UUID> userIds, Instant now) {
		Timestamp at = Timestamp.from(now);
		List<Object[]> rows = userIds.stream().map(u -> new Object[] { Ids.newId(), notificationId, u, at, at }).toList();
		batch.batchUpdate("""
				insert into notifications.deliveries
				       (id, notification_id, user_id, channel, queue_class, status, queued_at, sent_at)
				values (?, ?, ?, 'IN_APP', 'STANDARD', 'SENT', ?, ?)
				on conflict do nothing""", rows);
	}

	/** Teslim kuyruğu; aynı deneme iki kez girmez ({@code ux_deliveries_push_unique}). */
	void insertPush(UUID notificationId, Map<UUID, List<UUID>> devicesByUser, String queueClass, Instant now) {
		Timestamp at = Timestamp.from(now);
		List<Object[]> rows = new ArrayList<>();
		devicesByUser.forEach((user, devices) -> devices
			.forEach(device -> rows.add(new Object[] { Ids.newId(), notificationId, user, device, queueClass, at, at })));
		if (rows.isEmpty()) {
			return;
		}
		batch.batchUpdate("""
				insert into notifications.deliveries
				       (id, notification_id, user_id, device_id, channel, queue_class, status, queued_at, available_at)
				values (?, ?, ?, ?, 'PUSH', ?, 'QUEUED', ?, ?)
				on conflict do nothing""", rows);
	}

	// ---------------------------------------------------------------- gelen kutusu (NT-04, NT-05)

	record InboxRow(UUID id, String category, String kind, String priority, String title, String body,
			String sourceType, UUID sourceId, UUID notificationId, UUID operationId, Instant createdAt, Instant readAt) {
	}

	private static final String INBOX_SELECT = """
			select d.id, n.category, n.kind, n.priority, n.title, n.body, n.source_type, n.source_id, n.id as notification_id,
			       n.operation_id, n.created_at, d.read_at
			  from notifications.deliveries d
			  join notifications.notifications n on n.id = d.notification_id
			 where d.user_id = :userId and d.channel = 'IN_APP'""";

	/** {@code ix_deliveries_inbox}: kimlik azalan (en yeni önce), {@code limit + 1} satır. */
	List<InboxRow> inbox(UUID userId, boolean unreadOnly, String category, UUID after, int limit) {
		return jdbc.sql(INBOX_SELECT + """
				   and (cast(:after as uuid) is null or d.id < :after)
				   and (:unreadOnly = false or d.read_at is null)
				   and (cast(:category as text) is null or n.category = :category)
				 order by d.id desc
				 limit :limit""")
			.param("userId", userId).param("after", after).param("unreadOnly", unreadOnly)
			.param("category", category).param("limit", limit + 1)
			.query(NotificationStore::inboxRow).list();
	}

	Optional<InboxRow> inboxItem(UUID userId, UUID deliveryId) {
		return jdbc.sql(INBOX_SELECT + " and d.id = :id").param("userId", userId).param("id", deliveryId)
			.query(NotificationStore::inboxRow).optional();
	}

	/** İlk okumada {@code read_at} dolar; tekrar okuma değiştirmez. @return satır kullanıcıya ait mi */
	boolean markRead(UUID userId, UUID deliveryId, Instant now) {
		return jdbc.sql("""
				update notifications.deliveries set read_at = coalesce(read_at, :now)
				 where id = :id and user_id = :userId and channel = 'IN_APP'""")
			.param("id", deliveryId).param("userId", userId).param("now", Timestamp.from(now)).update() == 1;
	}

	/** {@code ix_deliveries_unread}. */
	int unreadCount(UUID userId) {
		return jdbc.sql("""
				select count(*) from notifications.deliveries
				 where user_id = :userId and channel = 'IN_APP' and read_at is null""")
			.param("userId", userId).query(Integer.class).single();
	}

	private static InboxRow inboxRow(ResultSet rs, int row) throws SQLException {
		return new InboxRow(rs.getObject("id", UUID.class), rs.getString("category"), rs.getString("kind"),
				rs.getString("priority"), rs.getString("title"), rs.getString("body"), rs.getString("source_type"),
				rs.getObject("source_id", UUID.class), rs.getObject("notification_id", UUID.class),
				rs.getObject("operation_id", UUID.class), instant(rs, "created_at"), instant(rs, "read_at"));
	}

	// ---------------------------------------------------------------- duyurular (NT-03, NT-06)

	record AnnouncementRow(UUID id, String title, String body, UUID operationId, UUID createdBy, Instant createdAt,
			int recipientCount, int sent, int failed, int read) {
	}

	/** @param operationIds {@code null}: tüm duyurular (kurum kapsamı) */
	List<AnnouncementRow> announcements(Collection<UUID> operationIds, boolean includeOrganizationWide, UUID operationFilter,
			UUID after, int limit) {
		return jdbc.sql(ANNOUNCEMENT_SELECT + """
				   and (cast(:after as uuid) is null or n.id < :after)
				   and (cast(:operationFilter as uuid) is null or n.operation_id = :operationFilter)
				   and (:all or n.operation_id in (:ops) or (:orgWide and n.operation_id is null))
				 order by n.id desc
				 limit :limit""")
			.param("after", after).param("operationFilter", operationFilter).param("all", operationIds == null)
			.param("ops", operationIds == null || operationIds.isEmpty() ? List.of(new UUID(0, 0)) : operationIds)
			.param("orgWide", includeOrganizationWide).param("limit", limit + 1)
			.query(NotificationStore::announcementRow).list();
	}

	Optional<AnnouncementRow> announcement(UUID id) {
		return jdbc.sql(ANNOUNCEMENT_SELECT + " and n.id = :id").param("id", id)
			.query(NotificationStore::announcementRow).optional();
	}

	private static final String ANNOUNCEMENT_SELECT = """
			select n.id, n.title, n.body, n.operation_id, n.created_by, n.created_at, coalesce(n.recipient_count, 0) as recipient_count,
			       (select count(*) from notifications.deliveries d where d.notification_id = n.id and d.status = 'SENT'
			                                                   and d.channel = 'IN_APP') as sent,
			       (select count(*) from notifications.deliveries d where d.notification_id = n.id and d.status = 'FAILED') as failed,
			       (select count(*) from notifications.deliveries d where d.notification_id = n.id and d.read_at is not null) as read
			  from notifications.notifications n
			 where n.category = 'ANNOUNCEMENT'""";

	private static AnnouncementRow announcementRow(ResultSet rs, int row) throws SQLException {
		return new AnnouncementRow(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"),
				rs.getObject("operation_id", UUID.class), rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
				rs.getInt("recipient_count"), rs.getInt("sent"), rs.getInt("failed"), rs.getInt("read"));
	}

	// ---------------------------------------------------------------- teslim işçisi (LD-40 kira deseni)

	record LeasedDelivery(UUID id, UUID deviceId, int sendTries, String category, String kind, String priority,
			String title, String sourceType, UUID sourceId) {
	}

	/**
	 * Kısa işlemde kira: satırlar {@code FOR UPDATE SKIP LOCKED} ile seçilir, {@code available_at} kira bitişine
	 * ötelenir ve {@code send_tries} artar; işlem hemen commit edilir, ağ çağrısı işlem dışında yapılır (LD-40).
	 * İşçi çökerse satır {@code QUEUED} kalır ve kira bitince yeniden alınır (en az bir kez iletim).
	 */
	List<LeasedDelivery> lease(String queueClass, Instant now, Duration lease, int limit) {
		return jdbc.sql("""
				with picked as (
				    select id from notifications.deliveries
				     where status = 'QUEUED' and queue_class = :queue and available_at <= :now
				     order by available_at
				     limit :limit
				     for update skip locked)
				update notifications.deliveries d
				   set available_at = :leaseUntil, send_tries = d.send_tries + 1
				  from picked, notifications.notifications n
				 where d.id = picked.id and n.id = d.notification_id
				returning d.id, d.device_id, d.send_tries, n.category, n.kind, n.priority, n.title, n.source_type, n.source_id""")
			.param("queue", queueClass).param("now", Timestamp.from(now))
			.param("leaseUntil", Timestamp.from(now.plus(lease))).param("limit", limit)
			.query((rs, row) -> new LeasedDelivery(rs.getObject("id", UUID.class), rs.getObject("device_id", UUID.class),
					rs.getInt("send_tries"), rs.getString("category"), rs.getString("kind"), rs.getString("priority"),
					rs.getString("title"), rs.getString("source_type"), rs.getObject("source_id", UUID.class)))
			.list();
	}

	void markSent(UUID id, String messageId, Instant now) {
		jdbc.sql("""
				update notifications.deliveries set status = 'SENT', sent_at = :now, fcm_message_id = :messageId, error_code = null
				 where id = :id and status = 'QUEUED'""")
			.param("id", id).param("now", Timestamp.from(now)).param("messageId", messageId).update();
	}

	void retryAt(UUID id, String errorCode, Instant at) {
		jdbc.sql("update notifications.deliveries set available_at = :at, error_code = :code where id = :id and status = 'QUEUED'")
			.param("id", id).param("at", Timestamp.from(at)).param("code", errorCode).update();
	}

	void markFailed(UUID id, String errorCode, Instant now) {
		jdbc.sql("""
				update notifications.deliveries set status = 'FAILED', failed_at = :now, error_code = :code
				 where id = :id and status = 'QUEUED'""")
			.param("id", id).param("now", Timestamp.from(now)).param("code", errorCode).update();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

}
