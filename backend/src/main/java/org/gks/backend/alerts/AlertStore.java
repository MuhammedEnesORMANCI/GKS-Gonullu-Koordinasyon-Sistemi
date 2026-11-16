package org.gks.backend.alerts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.Ids;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code alerts.alerts} ve {@code alerts.alert_recipients} (DB §6.3 – §6.4). */
@Component
class AlertStore {

	private static final TypeReference<Map<String, List<String>>> TARGETS = new TypeReference<>() {
	};

	private static final String ALERT_COLUMNS = """
			a.id, a.operation_id, a.message, a.requested_action, a.priority, a.state, a.version, a.target_expr::text as targets,
			a.audience_fingerprint, a.recipient_count, a.corrects_alert_id, a.sent_notification_id, a.created_by,
			a.created_at, a.sent_by, a.sent_at, a.cancelled_by, a.cancelled_at, a.closed_by, a.closed_at, a.close_reason,
			a.reminder_interval_seconds, a.reminder_max, a.scope, a.event_lat, a.event_lon,
			(select c.id from alerts.alerts c where c.corrects_alert_id = a.id) as corrected_by""";

	private static final String RECIPIENT_COLUMNS = """
			r.id, r.alert_id, r.user_id, r.via_group_id, r.state, r.response_option, r.responded_at_device,
			r.responded_at_server, r.sent_at, r.delivered_at, r.seen_at, r.reminder_count, r.failure_reason""";

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final RowMapper<AlertRow> alert;
	private final RowMapper<RecipientRow> recipient;

	AlertStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
		this.alert = (rs, row) -> {
			Map<String, List<String>> targets = json.readValue(rs.getString("targets"), TARGETS);
			return new AlertRow(uuid(rs, "id"), uuid(rs, "operation_id"), rs.getString("message"),
					rs.getString("requested_action"), rs.getString("priority"), AlertState.valueOf(rs.getString("state")),
					rs.getInt("version"), ids(targets.get("users")), ids(targets.get("groups")),
					rs.getString("audience_fingerprint"), (Integer) rs.getObject("recipient_count"),
					uuid(rs, "corrects_alert_id"), uuid(rs, "corrected_by"), uuid(rs, "sent_notification_id"),
					uuid(rs, "created_by"), instant(rs, "created_at"), uuid(rs, "sent_by"), instant(rs, "sent_at"),
					uuid(rs, "cancelled_by"), instant(rs, "cancelled_at"), uuid(rs, "closed_by"), instant(rs, "closed_at"),
					rs.getString("close_reason"), rs.getInt("reminder_interval_seconds"), rs.getInt("reminder_max"),
					rs.getString("scope"), ids(targets.get("operations")), targets.containsKey("organization"),
					(Double) rs.getObject("event_lat"), (Double) rs.getObject("event_lon"));
		};
		this.recipient = (rs, row) -> new RecipientRow(uuid(rs, "id"), uuid(rs, "alert_id"), uuid(rs, "user_id"),
				uuid(rs, "via_group_id"), RecipientState.valueOf(rs.getString("state")),
				option(rs.getString("response_option")), instant(rs, "responded_at_device"),
				instant(rs, "responded_at_server"), instant(rs, "sent_at"), instant(rs, "delivered_at"),
				instant(rs, "seen_at"), rs.getInt("reminder_count"), rs.getString("failure_reason"));
	}

	record AlertRow(UUID id, UUID operationId, String message, String requestedAction, String priority, AlertState state,
			int version, List<UUID> userIds, List<UUID> groupIds, String fingerprint, Integer recipientCount,
			UUID correctsAlertId, UUID correctedByAlertId, UUID sentNotificationId, UUID createdBy, Instant createdAt,
			UUID sentBy, Instant sentAt, UUID cancelledBy, Instant cancelledAt, UUID closedBy, Instant closedAt,
			String closeReason, int reminderIntervalSeconds, int reminderMax, String scope, List<UUID> operationIds,
			boolean organization, Double eventLat, Double eventLon) {

		/** ADR-026: operasyondan bağımsız Genel Acil Alarm. */
		boolean general() {
			return "GENERAL".equals(scope);
		}
	}

	/** Alarm hedefleri; {@code operationIds}/{@code organization} yalnızca genel alarmda. */
	record Targets(List<UUID> userIds, List<UUID> groupIds, List<UUID> operationIds, boolean organization) {

		static Targets of(List<UUID> userIds, List<UUID> groupIds) {
			return new Targets(userIds, groupIds, List.of(), false);
		}
	}

	record RecipientRow(UUID id, UUID alertId, UUID userId, UUID viaGroupId, RecipientState state,
			ResponseOption responseOption, Instant respondedAtDevice, Instant respondedAt, Instant sentAt,
			Instant deliveredAt, Instant seenAt, int reminderCount, String failureReason) {
	}

	/** {@code AlertCounts}; {@code pending} = QUEUED + SENT + DELIVERED + SEEN; {@code needHelp} ⊂ {@code responded}. */
	record Counts(int total, int responded, int pending, int unanswered, int failed, int needHelp) {
		static final Counts EMPTY = new Counts(0, 0, 0, 0, 0, 0);
	}

	/** ADR-025: harita için operasyon başına en son gönderilen açık alarm. */
	record OpenAlertRef(UUID id, UUID operationId, Instant sentAt) {
	}

	Map<UUID, OpenAlertRef> latestOpenAlerts(Collection<UUID> operationIds) {
		Map<UUID, OpenAlertRef> result = new HashMap<>();
		if (operationIds.isEmpty()) {
			return result;
		}
		jdbc.sql("""
				select distinct on (operation_id) id, operation_id, sent_at from alerts.alerts
				 where state = 'OPEN' and operation_id in (:ops) order by operation_id, sent_at desc""")
			.param("ops", operationIds)
			.query((rs, row) -> result.put(uuid(rs, "operation_id"), new OpenAlertRef(uuid(rs, "id"),
					uuid(rs, "operation_id"), rs.getTimestamp("sent_at").toInstant())))
			.list();
		return result;
	}

	// ---- alarm ----

	void insertDraft(UUID id, UUID operationId, String message, String requestedAction, String priority,
			Targets targets, UUID correctsAlertId, UUID createdBy, Instant now, Double eventLat, Double eventLon) {
		jdbc.sql("""
				insert into alerts.alerts (id, operation_id, scope, message, requested_action, priority, target_expr,
				                           corrects_alert_id, created_by, created_at, updated_at, event_lat, event_lon)
				values (:id, :op, :scope, :message, :action, :priority, cast(:targets as jsonb), :corrects, :by, :now, :now,
				        :lat, :lon)""")
			.param("id", id).param("op", operationId).param("scope", operationId == null ? "GENERAL" : "OPERATION")
			.param("message", message).param("action", requestedAction)
			.param("priority", priority).param("targets", targets(targets)).param("corrects", correctsAlertId)
			.param("by", createdBy).param("now", Timestamp.from(now)).param("lat", eventLat).param("lon", eventLon).update();
	}

	Optional<AlertRow> find(UUID id) {
		return jdbc.sql("select " + ALERT_COLUMNS + " from alerts.alerts a where a.id = :id").param("id", id)
			.query(alert).optional();
	}

	/** Durum geçişleri için satır kilidi (eşzamanlı gönderim/iptal/kapatma sıraya girer). */
	Optional<AlertRow> lock(UUID id) {
		jdbc.sql("select id from alerts.alerts where id = :id for update").param("id", id).query(UUID.class).optional();
		return find(id);
	}

	boolean isCorrected(UUID id) {
		return jdbc.sql("select count(*) from alerts.alerts where corrects_alert_id = :id").param("id", id)
			.query(Integer.class).single() > 0;
	}

	void deleteDraft(UUID id) {
		jdbc.sql("delete from alerts.alerts where id = :id and state = 'DRAFT'").param("id", id).update();
	}

	void updatePreview(UUID id, String fingerprint, int count, Instant now) {
		jdbc.sql("""
				update alerts.alerts set audience_fingerprint = :fp, preview_recipient_count = :count, previewed_at = :now
				 where id = :id""")
			.param("id", id).param("fp", fingerprint).param("count", count).param("now", Timestamp.from(now)).update();
	}

	/** {@code DRAFT → SENT → OPEN} tek işlemde (SENT ara durumu satırda kalmaz, emergency-alert-design §1.1). */
	void markOpen(UUID id, UUID sentBy, int recipientCount, String idempotencyKey, UUID notificationId, Instant now) {
		jdbc.sql("""
				update alerts.alerts set state = 'OPEN', sent_by = :by, sent_at = :now, recipient_count = :count,
				       idempotency_key = :key, sent_notification_id = :notification, updated_at = :now
				 where id = :id and state = 'DRAFT'""")
			.param("id", id).param("by", sentBy).param("count", recipientCount).param("key", idempotencyKey)
			.param("notification", notificationId).param("now", Timestamp.from(now)).update();
	}

	void cancel(UUID id, UUID by, Instant now) {
		jdbc.sql("""
				update alerts.alerts set state = 'CANCELLED', cancelled_by = :by, cancelled_at = :now, updated_at = :now
				 where id = :id and state = 'OPEN'""")
			.param("id", id).param("by", by).param("now", Timestamp.from(now)).update();
		stopReminders(id, now);
	}

	void close(UUID id, UUID by, String reason, Instant now) {
		jdbc.sql("""
				update alerts.alerts set state = 'CLOSED', closed_by = :by, closed_at = :now, close_reason = :reason,
				       updated_at = :now
				 where id = :id and state = 'OPEN'""")
			.param("id", id).param("by", by).param("reason", reason).param("now", Timestamp.from(now)).update();
		stopReminders(id, now);
	}

	List<UUID> openAlertIds(UUID operationId) {
		return jdbc.sql("select id from alerts.alerts where operation_id = :op and state = 'OPEN' for update")
			.param("op", operationId).query(UUID.class).list();
	}

	/**
	 * Alarm listesi (zaman azalan, UUIDv7 sırası). {@code operationIds == null}: tüm operasyonlar (ADM).
	 */
	List<AlertRow> list(Collection<UUID> operationIds, Set<AlertState> states, UUID before, int limit) {
		return list(operationIds, null, states, before, limit);
	}

	/**
	 * {@code operationIds == null}: tümü (ADM). Aksi hâlde o operasyonların alarmları ve, {@code generalCreatedBy}
	 * verilirse, o kişinin gönderdiği genel alarmlar (ADR-026: KOR kendi genel alarmını izler).
	 */
	List<AlertRow> list(Collection<UUID> operationIds, UUID generalCreatedBy, Set<AlertState> states, UUID before, int limit) {
		StringBuilder sql = new StringBuilder("select " + ALERT_COLUMNS + " from alerts.alerts a where true");
		Map<String, Object> params = new HashMap<>();
		if (operationIds != null) {
			if (operationIds.isEmpty() && generalCreatedBy == null) {
				return List.of();
			}
			sql.append(" and (a.operation_id in (:ops)");
			params.put("ops", operationIds.isEmpty() ? List.of(new UUID(0, 0)) : operationIds);
			if (generalCreatedBy != null) {
				sql.append(" or (a.scope = 'GENERAL' and a.created_by = :generalBy)");
				params.put("generalBy", generalCreatedBy);
			}
			sql.append(")");
		}
		if (!states.isEmpty()) {
			sql.append(" and a.state in (:states)");
			params.put("states", states.stream().map(Enum::name).toList());
		}
		if (before != null) {
			sql.append(" and a.id < :before");
			params.put("before", before);
		}
		sql.append(" order by a.id desc limit :limit");
		params.put("limit", limit + 1);
		return jdbc.sql(sql.toString()).params(params).query(alert).list();
	}

	Map<UUID, Counts> counts(Collection<UUID> alertIds) {
		Map<UUID, Counts> counts = new HashMap<>();
		if (alertIds.isEmpty()) {
			return counts;
		}
		jdbc.sql("""
				select alert_id, count(*) as total,
				       count(*) filter (where state = 'RESPONDED') as responded,
				       count(*) filter (where state in ('QUEUED', 'SENT', 'DELIVERED', 'SEEN')) as pending,
				       count(*) filter (where state = 'UNANSWERED') as unanswered,
				       count(*) filter (where state = 'FAILED') as failed,
				       count(*) filter (where response_option = 'NEED_HELP') as need_help
				  from alerts.alert_recipients where alert_id in (:ids) group by alert_id""")
			.param("ids", alertIds)
			.query((rs, row) -> counts.put(uuid(rs, "alert_id"), new Counts(rs.getInt("total"), rs.getInt("responded"),
					rs.getInt("pending"), rs.getInt("unanswered"), rs.getInt("failed"), rs.getInt("need_help"))))
			.list();
		return counts;
	}

	// ---- alıcılar ----

	/**
	 * Alıcı anlık görüntüsü. Bu aşamada iletim gelen kutusundan yapıldığı için satır {@code SENT} doğar ve
	 * {@code sent_at} gelen kutusuna yazılma anıdır (ADR-018); FCM eklenince {@code QUEUED} doğup işçiyle ilerler.
	 */
	void insertRecipients(UUID alertId, Map<UUID, UUID> audience, Instant now, Duration reminderInterval) {
		Timestamp at = Timestamp.from(now);
		Timestamp next = Timestamp.from(now.plus(reminderInterval));
		audience.forEach((userId, viaGroup) -> jdbc.sql("""
				insert into alerts.alert_recipients (id, alert_id, user_id, via_group_id, state, queued_at, sent_at,
				                                     next_reminder_at, updated_at)
				values (:id, :alert, :user, :group, 'SENT', :now, :now, :next, :now)""")
			.param("id", Ids.newId()).param("alert", alertId).param("user", userId).param("group", viaGroup)
			.param("now", at).param("next", next).update());
	}

	List<RecipientRow> recipients(UUID alertId) {
		return jdbc.sql("select " + RECIPIENT_COLUMNS + " from alerts.alert_recipients r where r.alert_id = :id")
			.param("id", alertId).query(recipient).list();
	}

	List<UUID> recipientUserIds(UUID alertId) {
		return jdbc.sql("select user_id from alerts.alert_recipients where alert_id = :id").param("id", alertId)
			.query(UUID.class).list();
	}

	Optional<RecipientRow> recipient(UUID id) {
		return jdbc.sql("select " + RECIPIENT_COLUMNS + " from alerts.alert_recipients r where r.id = :id")
			.param("id", id).query(recipient).optional();
	}

	Optional<RecipientRow> lockRecipient(UUID id) {
		jdbc.sql("select id from alerts.alert_recipients where id = :id for update").param("id", id).query(UUID.class)
			.optional();
		return recipient(id);
	}

	Optional<RecipientRow> recipientOf(UUID alertId, UUID userId) {
		return jdbc.sql("select " + RECIPIENT_COLUMNS + " from alerts.alert_recipients r where r.alert_id = :a and r.user_id = :u")
			.param("a", alertId).param("u", userId).query(recipient).optional();
	}

	/** AL-13/14: yalnızca ileri geçiş; durum asla geri alınmaz (B-03). @return satır değişti mi */
	boolean signal(UUID id, RecipientState target, Instant now) {
		String sql = target == RecipientState.DELIVERED
				? "update alerts.alert_recipients set state = 'DELIVERED', delivered_at = coalesce(delivered_at, :now), updated_at = :now where id = :id and state = 'SENT'"
				: "update alerts.alert_recipients set state = 'SEEN', seen_at = coalesce(seen_at, :now), updated_at = :now where id = :id and state in ('SENT', 'DELIVERED')";
		return jdbc.sql(sql).param("id", id).param("now", Timestamp.from(now)).update() == 1;
	}

	void respond(UUID id, ResponseOption option, Instant device, String key, Instant now) {
		jdbc.sql("""
				update alerts.alert_recipients
				   set state = 'RESPONDED', response_option = :option, responded_at_device = :device,
				       responded_at_server = :now, first_responded_at = coalesce(first_responded_at, :now),
				       response_idempotency_key = :key, next_reminder_at = null, failure_reason = null, updated_at = :now
				 where id = :id""")
			.param("id", id).param("option", option.name()).param("device", Timestamp.from(device)).param("key", key)
			.param("now", Timestamp.from(now)).update();
	}

	/** AL-10: seçilen {@code UNANSWERED}/{@code FAILED} alıcılar yeniden iletilir; diğerleri atlanır. */
	List<UUID> resend(UUID alertId, Collection<UUID> recipientIds, Instant now, Duration reminderInterval) {
		StringBuilder sql = new StringBuilder("""
				update alerts.alert_recipients
				   set state = 'SENT', reminder_count = 0, resend_count = resend_count + 1, failure_reason = null,
				       queued_at = :now, sent_at = coalesce(sent_at, :now), next_reminder_at = :next, updated_at = :now
				 where alert_id = :alert and state in ('UNANSWERED', 'FAILED')""");
		Map<String, Object> params = new HashMap<>();
		params.put("alert", alertId);
		params.put("now", Timestamp.from(now));
		params.put("next", Timestamp.from(now.plus(reminderInterval)));
		if (!recipientIds.isEmpty()) {
			sql.append(" and id in (:ids)");
			params.put("ids", recipientIds);
		}
		sql.append(" returning user_id");
		return jdbc.sql(sql.toString()).params(params).query(UUID.class).list();
	}

	/** AL-08: alıcısı olunan alarmlar, en yeni önce. */
	List<MyRow> mine(UUID userId, Set<AlertState> states, UUID before, int limit) {
		StringBuilder sql = new StringBuilder("select " + ALERT_COLUMNS + ", r.id as r_id, r.state as r_state, "
				+ "r.response_option as r_option, r.responded_at_server as r_responded "
				+ "from alerts.alert_recipients r join alerts.alerts a on a.id = r.alert_id where r.user_id = :user");
		Map<String, Object> params = new HashMap<>();
		params.put("user", userId);
		if (!states.isEmpty()) {
			sql.append(" and a.state in (:states)");
			params.put("states", states.stream().map(Enum::name).toList());
		}
		if (before != null) {
			sql.append(" and a.id < :before");
			params.put("before", before);
		}
		sql.append(" order by a.id desc limit :limit");
		params.put("limit", limit + 1);
		return jdbc.sql(sql.toString()).params(params)
			.query((rs, row) -> new MyRow(alert.mapRow(rs, row), uuid(rs, "r_id"), RecipientState.valueOf(rs.getString("r_state")),
					option(rs.getString("r_option")), instant(rs, "r_responded")))
			.list();
	}

	record MyRow(AlertRow alert, UUID recipientId, RecipientState state, ResponseOption option, Instant respondedAt) {
	}

	/**
	 * Hatırlatma zamanlayıcısı (K-13, 30 sn): zamanı gelen alıcılar {@code SKIP LOCKED} ile alınır. Sınıra
	 * ulaşmamışsa sayaç artar ve sonraki hatırlatma planlanır; sınırdaysa alıcı {@code UNANSWERED} olur.
	 *
	 * @return alarm → durumu değişen alıcılar
	 */
	Map<UUID, List<UUID>> advanceReminders(Instant now, int batch) {
		Map<UUID, List<UUID>> changed = new LinkedHashMap<>();
		List<Object[]> due = jdbc.sql("""
				select r.id, r.alert_id, r.reminder_count, a.reminder_max, a.reminder_interval_seconds
				  from alerts.alert_recipients r join alerts.alerts a on a.id = r.alert_id
				 where r.next_reminder_at <= :now and r.state in ('SENT', 'DELIVERED', 'SEEN') and a.state = 'OPEN'
				 order by r.next_reminder_at
				 limit :batch
				   for update of r skip locked""")
			.param("now", Timestamp.from(now)).param("batch", batch)
			.query((rs, row) -> new Object[] { uuid(rs, "id"), uuid(rs, "alert_id"), rs.getInt("reminder_count"),
					rs.getInt("reminder_max"), rs.getInt("reminder_interval_seconds") })
			.list();
		for (Object[] row : due) {
			UUID id = (UUID) row[0];
			int count = (Integer) row[2];
			if (count < (Integer) row[3]) {
				jdbc.sql("""
						update alerts.alert_recipients set reminder_count = reminder_count + 1,
						       next_reminder_at = :next, updated_at = :now where id = :id""")
					.param("id", id).param("next", Timestamp.from(now.plusSeconds((Integer) row[4])))
					.param("now", Timestamp.from(now)).update();
			}
			else {
				jdbc.sql("""
						update alerts.alert_recipients set state = 'UNANSWERED', next_reminder_at = null, updated_at = :now
						 where id = :id""")
					.param("id", id).param("now", Timestamp.from(now)).update();
				changed.computeIfAbsent((UUID) row[1], k -> new ArrayList<>()).add(id);
			}
		}
		return changed;
	}

	private void stopReminders(UUID alertId, Instant now) {
		jdbc.sql("""
				update alerts.alert_recipients set next_reminder_at = null, updated_at = :now
				 where alert_id = :id and next_reminder_at is not null""")
			.param("id", alertId).param("now", Timestamp.from(now)).update();
	}

	private String targets(Targets targets) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		map.put("users", targets.userIds().stream().map(UUID::toString).toList());
		map.put("groups", targets.groupIds().stream().map(UUID::toString).toList());
		if (!targets.operationIds().isEmpty()) {
			map.put("operations", targets.operationIds().stream().map(UUID::toString).toList());
		}
		if (targets.organization()) {
			map.put("organization", List.of("ALL"));
		}
		return json.writeValueAsString(map);
	}

	private static List<UUID> ids(List<String> values) {
		return values == null ? List.of() : values.stream().map(UUID::fromString).toList();
	}

	private static ResponseOption option(String value) {
		return value == null ? null : ResponseOption.valueOf(value);
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

}
