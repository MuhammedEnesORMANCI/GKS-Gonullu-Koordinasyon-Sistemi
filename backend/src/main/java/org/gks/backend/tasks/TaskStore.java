package org.gks.backend.tasks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.tasks.TaskValidation.TargetsInput;
import org.gks.backend.tasks.TaskValidation.TimeWindowInput;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code tasks} şeması erişimi (DB §5.3 – §5.5). Kapasiteyi etkileyen değişiklikler görev satırının kilidi altında
 * yapılır (E-1 – E-3); kilit sırası görev → atama (§8.1).
 */
@Component
class TaskStore {

	private static final String TASK_COLUMNS = """
			id, operation_id, title, description, priority, meeting_point,
			lower(time_window) as tw_start, upper(time_window) as tw_end, capacity, accepted_count,
			response_window_minutes, target_expr::text as target_expr, state, version, created_by, created_at,
			published_at""";

	/** {@link #TASK_COLUMNS} birleştirmeli sorgu için {@code t} takma adıyla. */
	private static final String T_TASK_COLUMNS = """
			t.id, t.operation_id, t.title, t.description, t.priority, t.meeting_point,
			lower(t.time_window) as tw_start, upper(t.time_window) as tw_end, t.capacity, t.accepted_count,
			t.response_window_minutes, t.target_expr::text as target_expr, t.state, t.version, t.created_by,
			t.created_at, t.published_at""";

	private static final String ASSIGNMENT_COLUMNS = """
			id, task_id, user_id, status, assigned_at, respond_by, responded_at, removed_by""";

	private static final RowMapper<AssignmentRow> ASSIGNMENT = (rs, row) -> new AssignmentRow(uuid(rs, "id"),
			uuid(rs, "task_id"), uuid(rs, "user_id"), AssignmentStatus.valueOf(rs.getString("status")),
			instant(rs, "assigned_at"), instant(rs, "respond_by"), instant(rs, "responded_at"),
			uuid(rs, "removed_by"));

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final RowMapper<TaskRow> task;

	TaskStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
		this.task = (rs, row) -> new TaskRow(uuid(rs, "id"), uuid(rs, "operation_id"), rs.getString("title"),
				rs.getString("description"), TaskPriority.valueOf(rs.getString("priority")),
				rs.getString("meeting_point"), instant(rs, "tw_start"), instant(rs, "tw_end"),
				(Integer) rs.getObject("capacity"), rs.getInt("accepted_count"), rs.getInt("response_window_minutes"),
				targets(rs.getString("target_expr")), TaskState.valueOf(rs.getString("state")), rs.getInt("version"),
				uuid(rs, "created_by"), instant(rs, "created_at"), instant(rs, "published_at"));
	}

	record TaskRow(UUID id, UUID operationId, String title, String description, TaskPriority priority,
			String meetingPoint, Instant windowStart, Instant windowEnd, Integer capacity, int acceptedCount,
			int responseWindowMinutes, TargetsInput targets, TaskState state, int version, UUID createdBy,
			Instant createdAt, Instant publishedAt) {

		boolean full() {
			return capacity != null && acceptedCount >= capacity;
		}

	}

	record AssignmentRow(UUID id, UUID taskId, UUID userId, AssignmentStatus status, Instant assignedAt,
			Instant respondBy, Instant respondedAt, UUID removedBy) {
	}

	/** Görev ve öznenin oradaki ataması (gönüllü izdüşümü) ile KOR/ADM sayaçları. */
	record SummaryRow(TaskRow task, int pendingCount, int overdueCount, AssignmentRow mine) {
	}

	record StateChangeRow(TaskState state, UUID changedBy, Instant changedAt) {
	}

	record Counts(int pending, int overdue) {
	}

	// --- görev ---

	Optional<TaskRow> find(UUID id) {
		return jdbc.sql("select " + TASK_COLUMNS + " from tasks.tasks where id = :id").param("id", id)
			.query(task).optional();
	}

	/** Görev satırı kapasite ve durum kilit noktasıdır (E-1, E-2, E-3). */
	Optional<TaskRow> lock(UUID id) {
		return jdbc.sql("select " + TASK_COLUMNS + " from tasks.tasks where id = :id for update").param("id", id)
			.query(task).optional();
	}

	void insertTask(UUID id, UUID operationId, String title, String description, TaskPriority priority,
			String meetingPoint, TimeWindowInput window, Integer capacity, int responseWindowMinutes,
			TargetsInput targets, UUID createdBy, Instant now) {
		jdbc.sql("""
				insert into tasks.tasks (id, operation_id, title, description, priority, meeting_point, time_window,
				                         capacity, response_window_minutes, target_expr, state, version, created_by,
				                         created_at, updated_at)
				values (:id, :operationId, :title, :description, :priority, :meetingPoint,
				        case when cast(:start as timestamptz) is null then null
				             else tstzrange(cast(:start as timestamptz), cast(:end as timestamptz), '[)') end,
				        :capacity, :window, cast(:targets as jsonb), 'DRAFT', 1, :createdBy, :now, :now)""")
			.param("id", id).param("operationId", operationId).param("title", title)
			.param("description", description).param("priority", priority.name()).param("meetingPoint", meetingPoint)
			.param("start", window == null ? null : Timestamp.from(window.start()))
			.param("end", window == null || window.end() == null ? null : Timestamp.from(window.end()))
			.param("capacity", capacity).param("window", responseWindowMinutes).param("targets", targetsJson(targets))
			.param("createdBy", createdBy).param("now", Timestamp.from(now))
			.update();
	}

	/** TK-02: tüm düzenlenebilir alanlar yazılır, {@code version} +1 (TD-05). */
	void updateDetails(UUID id, String title, String description, TaskPriority priority, String meetingPoint,
			TimeWindowInput window, Integer capacity, int responseWindowMinutes, TargetsInput targets, Instant now) {
		jdbc.sql("""
				update tasks.tasks
				   set title = :title, description = :description, priority = :priority, meeting_point = :meetingPoint,
				       time_window = case when cast(:start as timestamptz) is null then null
				                     else tstzrange(cast(:start as timestamptz), cast(:end as timestamptz), '[)') end,
				       capacity = :capacity, response_window_minutes = :window, target_expr = cast(:targets as jsonb),
				       version = version + 1, updated_at = :now
				 where id = :id""")
			.param("id", id).param("title", title).param("description", description)
			.param("priority", priority.name()).param("meetingPoint", meetingPoint)
			.param("start", window == null ? null : Timestamp.from(window.start()))
			.param("end", window == null || window.end() == null ? null : Timestamp.from(window.end()))
			.param("capacity", capacity).param("window", responseWindowMinutes).param("targets", targetsJson(targets))
			.param("now", Timestamp.from(now))
			.update();
	}

	/** Durum geçişi; {@code version} +1, son değiştiren ve zaman damgası (DB §5.3). */
	void setState(UUID id, TaskState state, UUID changedBy, Instant now) {
		jdbc.sql("""
				update tasks.tasks
				   set state = :state, version = version + 1, state_changed_by = :changedBy, updated_at = :now,
				       published_at = case when :state = 'PUBLISHED' then :now else published_at end,
				       completed_at = case when :state = 'DONE' then :now else completed_at end,
				       cancelled_at = case when :state = 'CANCELLED' then :now else cancelled_at end
				 where id = :id""")
			.param("id", id).param("state", state.name()).param("changedBy", changedBy)
			.param("now", Timestamp.from(now))
			.update();
	}

	void insertStateChange(UUID id, UUID taskId, TaskState from, TaskState to, UUID changedBy, Instant now) {
		jdbc.sql("""
				insert into tasks.task_state_changes (id, task_id, from_state, to_state, changed_by, changed_at)
				values (:id, :taskId, :from, :to, :changedBy, :now)""")
			.param("id", id).param("taskId", taskId).param("from", from == null ? null : from.name())
			.param("to", to.name()).param("changedBy", changedBy).param("now", Timestamp.from(now))
			.update();
	}

	List<StateChangeRow> history(UUID taskId) {
		return jdbc.sql("""
				select to_state, changed_by, changed_at from tasks.task_state_changes
				 where task_id = :taskId order by id""")
			.param("taskId", taskId)
			.query((rs, row) -> new StateChangeRow(TaskState.valueOf(rs.getString("to_state")),
					uuid(rs, "changed_by"), instant(rs, "changed_at")))
			.list();
	}

	/** Kabul: kapasite kilidi altında +1; {@code ck_tasks_capacity_not_exceeded} son güvencedir. */
	void incrementAccepted(UUID taskId, Instant now) {
		jdbc.sql("update tasks.tasks set accepted_count = accepted_count + 1, updated_at = :now where id = :id")
			.param("id", taskId).param("now", Timestamp.from(now)).update();
	}

	/** E-3: kabul edilmiş atamanın kaldırılması. */
	void decrementAccepted(UUID taskId, Instant now) {
		jdbc.sql("update tasks.tasks set accepted_count = accepted_count - 1, updated_at = :now where id = :id")
			.param("id", taskId).param("now", Timestamp.from(now)).update();
	}

	Counts counts(UUID taskId, Instant now) {
		return jdbc.sql("""
				select count(*) filter (where status = 'PENDING') as pending,
				       count(*) filter (where status = 'PENDING' and respond_by < :now) as overdue
				  from tasks.task_assignments where task_id = :taskId""")
			.param("taskId", taskId).param("now", Timestamp.from(now))
			.query((rs, row) -> new Counts(rs.getInt("pending"), rs.getInt("overdue"))).single();
	}

	/**
	 * TK-07: {@code assignedOnly} gönüllü izdüşümüdür; kapsam filtresi sorgu düzeyindedir (authorization.md §6).
	 * {@code REMOVED} atama görünürlük vermez. Sıralama {@code id} azalan (UUIDv7 = oluşturma zamanı, §3.5).
	 */
	List<SummaryRow> list(UUID operationId, boolean assignedOnly, UUID me, TaskState state, UUID after, int limit,
			Instant now) {
		StringBuilder sql = new StringBuilder("select ").append(T_TASK_COLUMNS)
			.append("""
					, (select count(*) from tasks.task_assignments p
					    where p.task_id = t.id and p.status = 'PENDING') as pending_count,
					  (select count(*) from tasks.task_assignments o
					    where o.task_id = t.id and o.status = 'PENDING' and o.respond_by < :now) as overdue_count,
					  m.id as m_id, m.status as m_status, m.assigned_at as m_assigned_at, m.respond_by as m_respond_by,
					  m.responded_at as m_responded_at, m.removed_by as m_removed_by
					  from tasks.tasks t
					  left join tasks.task_assignments m on m.task_id = t.id and m.user_id = :me and m.status <> 'REMOVED'
					 where t.operation_id = :operationId""");
		if (assignedOnly) {
			sql.append(" and m.id is not null");
		}
		if (state != null) {
			sql.append(" and t.state = :state");
		}
		if (after != null) {
			sql.append(" and t.id < :after");
		}
		sql.append(" order by t.id desc limit :limit");
		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("operationId", operationId)
			.param("me", me).param("now", Timestamp.from(now)).param("limit", limit);
		if (state != null) {
			statement = statement.param("state", state.name());
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		return statement.query((rs, row) -> new SummaryRow(task.mapRow(rs, row), rs.getInt("pending_count"),
				rs.getInt("overdue_count"), mine(rs))).list();
	}

	// --- atama ---

	Optional<AssignmentRow> findAssignment(UUID id) {
		return jdbc.sql("select " + ASSIGNMENT_COLUMNS + " from tasks.task_assignments where id = :id")
			.param("id", id).query(ASSIGNMENT).optional();
	}

	Optional<AssignmentRow> lockAssignment(UUID id) {
		return jdbc.sql("select " + ASSIGNMENT_COLUMNS + " from tasks.task_assignments where id = :id for update")
			.param("id", id).query(ASSIGNMENT).optional();
	}

	Optional<AssignmentRow> findAssignment(UUID taskId, UUID userId) {
		return jdbc.sql("select " + ASSIGNMENT_COLUMNS
				+ " from tasks.task_assignments where task_id = :taskId and user_id = :userId")
			.param("taskId", taskId).param("userId", userId).query(ASSIGNMENT).optional();
	}

	/** Öznenin görevdeki etkin ({@code REMOVED} olmayan) ataması (gönüllü izdüşümü). */
	Optional<AssignmentRow> visibleAssignment(UUID taskId, UUID userId) {
		return findAssignment(taskId, userId).filter(a -> a.status() != AssignmentStatus.REMOVED);
	}

	void insertAssignment(UUID id, UUID taskId, UUID userId, UUID viaGroupId, String source, UUID assignedBy,
			Instant now, Instant respondBy) {
		jdbc.sql("""
				insert into tasks.task_assignments (id, task_id, user_id, via_group_id, source, status, assigned_at,
				                                    assigned_by, respond_by, updated_at)
				values (:id, :taskId, :userId, :viaGroupId, :source, 'PENDING', :now, :assignedBy, :respondBy, :now)""")
			.param("id", id).param("taskId", taskId).param("userId", userId).param("viaGroupId", viaGroupId)
			.param("source", source).param("assignedBy", assignedBy).param("now", Timestamp.from(now))
			.param("respondBy", Timestamp.from(respondBy))
			.update();
	}

	/** DB §5.4 "Yeniden atama": mevcut satır {@code PENDING}'e döner, zamanlar ve yanıt alanları yenilenir. */
	void reactivateAssignment(UUID id, UUID assignedBy, Instant now, Instant respondBy) {
		jdbc.sql("""
				update tasks.task_assignments
				   set status = 'PENDING', source = 'MANUAL', assigned_at = :now, assigned_by = :assignedBy,
				       respond_by = :respondBy, responded_at = null, removed_at = null, removed_by = null,
				       updated_at = :now
				 where id = :id""")
			.param("id", id).param("assignedBy", assignedBy).param("now", Timestamp.from(now))
			.param("respondBy", Timestamp.from(respondBy))
			.update();
	}

	void accept(UUID id, Instant now) {
		jdbc.sql("""
				update tasks.task_assignments set status = 'ACCEPTED', responded_at = :now, updated_at = :now
				 where id = :id and status = 'PENDING'""")
			.param("id", id).param("now", Timestamp.from(now)).update();
	}

	/** E-4: kilitsiz koşullu güncelleme; 0 satır → atama artık {@code PENDING} değil. */
	int reject(UUID id, Instant now) {
		return jdbc.sql("""
				update tasks.task_assignments set status = 'REJECTED', responded_at = :now, updated_at = :now
				 where id = :id and status = 'PENDING'""")
			.param("id", id).param("now", Timestamp.from(now)).update();
	}

	void markCapacityFull(UUID id, Instant now) {
		jdbc.sql("""
				update tasks.task_assignments set status = 'CAPACITY_FULL', updated_at = :now
				 where id = :id and status = 'PENDING'""")
			.param("id", id).param("now", Timestamp.from(now)).update();
	}

	/** E-1 (5), TK-02: kapasite dolunca kalan {@code PENDING} atamalar aynı işlemde kapanır (AC-14.5). */
	/** @return {@code CAPACITY_FULL} yapılan atamaların kullanıcıları (bildirim için, AC-14.5) */
	List<UUID> markPendingCapacityFull(UUID taskId, Instant now) {
		return jdbc.sql("""
				update tasks.task_assignments set status = 'CAPACITY_FULL', updated_at = :now
				 where task_id = :taskId and status = 'PENDING'
				returning user_id""")
			.param("taskId", taskId).param("now", Timestamp.from(now)).query(UUID.class).list();
	}

	/** Bildirim alıcıları: verilen durumlardaki atamaların kullanıcıları. */
	List<UUID> assignees(UUID taskId, Collection<AssignmentStatus> statuses) {
		return jdbc.sql("""
				select user_id from tasks.task_assignments where task_id = :taskId and status in (:statuses)
				 order by user_id""")
			.param("taskId", taskId).param("statuses", statuses.stream().map(Enum::name).toList())
			.query(UUID.class).list();
	}

	void remove(UUID id, UUID removedBy, Instant now) {
		jdbc.sql("""
				update tasks.task_assignments
				   set status = 'REMOVED', removed_at = :now, removed_by = :removedBy, updated_at = :now
				 where id = :id""")
			.param("id", id).param("removedBy", removedBy).param("now", Timestamp.from(now)).update();
	}

	/** TK-09: {@code overdue} = {@code PENDING} ve {@code respond_by < now} (AC-16.3). */
	List<AssignmentRow> assignmentsOfTask(UUID taskId, Set<AssignmentStatus> statuses, Boolean overdue, UUID after,
			int limit, Instant now) {
		StringBuilder sql = new StringBuilder("select " + ASSIGNMENT_COLUMNS
				+ " from tasks.task_assignments where task_id = :taskId");
		if (!statuses.isEmpty()) {
			sql.append(" and status in (:statuses)");
		}
		if (Boolean.TRUE.equals(overdue)) {
			sql.append(" and status = 'PENDING' and respond_by < :now");
		}
		else if (Boolean.FALSE.equals(overdue)) {
			sql.append(" and not (status = 'PENDING' and respond_by < :now)");
		}
		if (after != null) {
			sql.append(" and id < :after");
		}
		sql.append(" order by id desc limit :limit");
		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("taskId", taskId).param("limit", limit);
		if (!statuses.isEmpty()) {
			statement = statement.param("statuses", statuses.stream().map(Enum::name).toList());
		}
		if (overdue != null) {
			statement = statement.param("now", Timestamp.from(now));
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		return statement.query(ASSIGNMENT).list();
	}

	/** TK-10: öznenin tüm atamaları (taslak görevlerin ataması olmaz). */
	List<AssignmentRow> myAssignments(UUID userId, Set<AssignmentStatus> statuses, UUID operationId, UUID after,
			int limit) {
		StringBuilder sql = new StringBuilder("""
				select a.id, a.task_id, a.user_id, a.status, a.assigned_at, a.respond_by, a.responded_at, a.removed_by
				  from tasks.task_assignments a join tasks.tasks t on t.id = a.task_id
				 where a.user_id = :userId""");
		if (!statuses.isEmpty()) {
			sql.append(" and a.status in (:statuses)");
		}
		if (operationId != null) {
			sql.append(" and t.operation_id = :operationId");
		}
		if (after != null) {
			sql.append(" and a.id < :after");
		}
		sql.append(" order by a.id desc limit :limit");
		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("userId", userId).param("limit", limit);
		if (!statuses.isEmpty()) {
			statement = statement.param("statuses", statuses.stream().map(Enum::name).toList());
		}
		if (operationId != null) {
			statement = statement.param("operationId", operationId);
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		return statement.query(ASSIGNMENT).list();
	}

	Map<UUID, TaskRow> tasks(Collection<UUID> ids) {
		Map<UUID, TaskRow> rows = new HashMap<>();
		if (ids.isEmpty()) {
			return rows;
		}
		jdbc.sql("select " + TASK_COLUMNS + " from tasks.tasks where id in (:ids)").param("ids", ids)
			.query(task).list().forEach(row -> rows.put(row.id(), row));
		return rows;
	}

	// --- yardımcılar ---

	private String targetsJson(TargetsInput targets) {
		if (targets == null) {
			return null;
		}
		Map<String, List<String>> expr = new java.util.LinkedHashMap<>();
		expr.put("users", targets.userIds().stream().map(UUID::toString).toList());
		expr.put("groups", targets.groupIds().stream().map(UUID::toString).toList());
		return json.writeValueAsString(expr);
	}

	/** {@code target_expr = {users:[…], groups:[…]}} (DB §5.3, §15.1). */
	private TargetsInput targets(String expr) {
		if (expr == null) {
			return new TargetsInput(List.of(), List.of());
		}
		JsonNode node = json.readTree(expr);
		return new TargetsInput(uuids(node.get("users")), uuids(node.get("groups")));
	}

	private static List<UUID> uuids(JsonNode array) {
		List<UUID> ids = new ArrayList<>();
		if (array != null) {
			for (int i = 0; i < array.size(); i++) {
				ids.add(UUID.fromString(array.get(i).stringValue()));
			}
		}
		return ids;
	}

	private static AssignmentRow mine(ResultSet rs) throws SQLException {
		UUID id = uuid(rs, "m_id");
		if (id == null) {
			return null;
		}
		return new AssignmentRow(id, uuid(rs, "id"), null, AssignmentStatus.valueOf(rs.getString("m_status")),
				instant(rs, "m_assigned_at"), instant(rs, "m_respond_by"), instant(rs, "m_responded_at"),
				uuid(rs, "m_removed_by"));
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp != null ? timestamp.toInstant() : null;
	}

}
