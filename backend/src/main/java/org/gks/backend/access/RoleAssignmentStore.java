package org.gks.backend.access;

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
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code access.role_assignments} erişimi (DB §4.1). */
@Component
class RoleAssignmentStore {

	private static final String COLUMNS = "id, user_id, role, scope_type, scope_id, granted_by, granted_at, revoked_at";

	private static final RowMapper<AssignmentRow> ROW = (rs, row) -> new AssignmentRow(uuid(rs, "id"),
			uuid(rs, "user_id"), Role.valueOf(rs.getString("role")), ScopeType.valueOf(rs.getString("scope_type")),
			uuid(rs, "scope_id"), uuid(rs, "granted_by"), instant(rs, "granted_at"), instant(rs, "revoked_at"));

	private final JdbcClient jdbc;

	RoleAssignmentStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record AssignmentRow(UUID id, UUID userId, Role role, ScopeType scopeType, UUID scopeId, UUID grantedBy,
			Instant grantedAt, Instant revokedAt) {
	}

	Grants grants(UUID userId) {
		return new Grants(jdbc.sql("""
				select role, scope_id from access.role_assignments
				 where user_id = :userId and revoked_at is null""")
			.param("userId", userId)
			.query((rs, row) -> new Grants.Grant(Role.valueOf(rs.getString("role")), uuid(rs, "scope_id")))
			.list());
	}

	List<UUID> participants(UUID operationId) {
		return jdbc.sql("""
				select user_id from access.role_assignments
				 where role = 'GON' and scope_type = 'OPERATION' and scope_id = :operationId and revoked_at is null
				 order by user_id""")
			.param("operationId", operationId).query(UUID.class).list();
	}

	/** "X, Y operasyonunun katılımcısı mı?" tek indeks araması ({@code ux_role_assignments_active}, DB §4.1). */
	boolean isParticipant(UUID operationId, UUID userId) {
		return jdbc.sql("""
				select exists (select 1 from access.role_assignments
				                where user_id = :userId and role = 'GON' and scope_type = 'OPERATION'
				                  and scope_id = :operationId and revoked_at is null)""")
			.param("userId", userId).param("operationId", operationId)
			.query(Boolean.class).single();
	}

	Map<UUID, List<UUID>> activeHolders(Role role, Collection<UUID> scopeIds) {
		Map<UUID, List<UUID>> holders = new HashMap<>();
		if (scopeIds.isEmpty()) {
			return holders;
		}
		jdbc.sql("""
				select scope_id, user_id from access.role_assignments
				 where role = :role and scope_id in (:scopeIds) and revoked_at is null
				 order by granted_at, id""")
			.param("role", role.name()).param("scopeIds", scopeIds)
			.query((rs, row) -> holders.computeIfAbsent(uuid(rs, "scope_id"), k -> new ArrayList<>())
				.add(uuid(rs, "user_id")))
			.list();
		return holders;
	}

	List<RoleAssignments.Participant> participants(UUID operationId, Collection<UUID> excluded, UUID after, int limit) {
		StringBuilder sql = new StringBuilder("""
				select id, user_id, granted_at from access.role_assignments
				 where role = 'GON' and scope_type = 'OPERATION' and scope_id = :operationId and revoked_at is null""");
		if (!excluded.isEmpty()) {
			sql.append(" and user_id not in (:excluded)");
		}
		if (after != null) {
			sql.append(" and id < :after");
		}
		sql.append(" order by id desc limit :limit");
		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("operationId", operationId)
			.param("limit", limit);
		if (!excluded.isEmpty()) {
			statement = statement.param("excluded", excluded);
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		return statement.query((rs, row) -> new RoleAssignments.Participant(uuid(rs, "id"), uuid(rs, "user_id"),
				instant(rs, "granted_at"))).list();
	}

	int activeAdminCount() {
		return jdbc.sql("select count(*) from access.role_assignments where role = 'ADM' and revoked_at is null")
			.query(Integer.class).single();
	}

	void insert(UUID id, UUID userId, Role role, UUID scopeId, UUID grantedBy, Instant now) {
		jdbc.sql("""
				insert into access.role_assignments (id, user_id, role, scope_type, scope_id, granted_by, granted_at)
				values (:id, :userId, :role, :scopeType, :scopeId, :grantedBy, :now)""")
			.param("id", id).param("userId", userId).param("role", role.name())
			.param("scopeType", role.scopeType.name()).param("scopeId", scopeId).param("grantedBy", grantedBy)
			.param("now", Timestamp.from(now))
			.update();
	}

	/** Atama satırı kilitlenerek okunur; eşzamanlı iki kaldırma sıraya girer. */
	Optional<AssignmentRow> lock(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from access.role_assignments where id = :id for update")
			.param("id", id).query(ROW).optional();
	}

	Optional<AssignmentRow> find(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from access.role_assignments where id = :id")
			.param("id", id).query(ROW).optional();
	}

	void revoke(UUID id, UUID revokedBy, Instant now) {
		jdbc.sql("""
				update access.role_assignments set revoked_at = :now, revoked_by = :revokedBy, revoke_reason = 'MANUAL'
				 where id = :id and revoked_at is null""")
			.param("id", id).param("revokedBy", revokedBy).param("now", Timestamp.from(now))
			.update();
	}

	/** AC-03: filtreler isteğe bağlı; sıralama {@code id} azalan (§3.5). */
	List<AssignmentRow> list(UUID userId, UUID scopeId, Role role, Boolean active, UUID after, int limit) {
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from access.role_assignments where true");
		if (userId != null) {
			sql.append(" and user_id = :userId");
		}
		if (scopeId != null) {
			sql.append(" and scope_id = :scopeId");
		}
		if (role != null) {
			sql.append(" and role = :role");
		}
		if (active != null) {
			sql.append(active ? " and revoked_at is null" : " and revoked_at is not null");
		}
		if (after != null) {
			sql.append(" and id < :after");
		}
		sql.append(" order by id desc limit :limit");
		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit);
		if (userId != null) {
			statement = statement.param("userId", userId);
		}
		if (scopeId != null) {
			statement = statement.param("scopeId", scopeId);
		}
		if (role != null) {
			statement = statement.param("role", role.name());
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		return statement.query(ROW).list();
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp != null ? timestamp.toInstant() : null;
	}

}
