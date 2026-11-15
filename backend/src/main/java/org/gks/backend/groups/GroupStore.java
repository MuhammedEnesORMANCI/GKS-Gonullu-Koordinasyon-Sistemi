package org.gks.backend.groups;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.access.GroupAccess.ListScope;
import org.gks.backend.access.GroupRole;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code groups} şeması erişimi (DB §5.1, §5.2). Üyelik değişiklikleri grup satırının kilidi altında yapılır (E-5, E-6). */
@Component
class GroupStore {

	private static final String GROUP_COLUMNS = """
			id, organization_id, operation_id, name, description, created_at, archived_at,
			mp_address, mp_lat, mp_lon, mp_description, mp_time, mp_updated_by, mp_updated_at""";

	private static final RowMapper<GroupRow> GROUP = (rs, row) -> new GroupRow(uuid(rs, "id"),
			uuid(rs, "organization_id"), uuid(rs, "operation_id"), rs.getString("name"), rs.getString("description"),
			instant(rs, "created_at"), instant(rs, "archived_at"), rs.getString("mp_address"),
			(Double) rs.getObject("mp_lat"), (Double) rs.getObject("mp_lon"), rs.getString("mp_description"),
			instant(rs, "mp_time"), uuid(rs, "mp_updated_by"), instant(rs, "mp_updated_at"));

	private final JdbcClient jdbc;

	GroupStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record GroupRow(UUID id, UUID organizationId, UUID operationId, String name, String description, Instant createdAt,
			Instant archivedAt, String mpAddress, Double mpLat, Double mpLon, String mpDescription, Instant mpTime,
			UUID mpUpdatedBy, Instant mpUpdatedAt) {
	}

	record MemberRow(UUID userId, GroupRole role, Instant joinedAt) {
	}

	record SummaryRow(UUID id, String name, UUID operationId, boolean archived, UUID leaderId, int memberCount,
			GroupRole myRole) {
	}

	Optional<GroupRow> find(UUID id) {
		return jdbc.sql("select " + GROUP_COLUMNS + " from groups.groups where id = :id").param("id", id)
			.query(GROUP).optional();
	}

	/** Grup satırı kilit noktasıdır (DB §5.1): üye ekleme/çıkarma ve lider değişikliği sıraya girer. */
	Optional<GroupRow> lock(UUID id) {
		return jdbc.sql("select " + GROUP_COLUMNS + " from groups.groups where id = :id for update").param("id", id)
			.query(GROUP).optional();
	}

	Optional<GroupRole> activeRole(UUID groupId, UUID userId) {
		return jdbc.sql("""
				select role from groups.group_memberships
				 where group_id = :groupId and user_id = :userId and status = 'ACTIVE'""")
			.param("groupId", groupId).param("userId", userId)
			.query((rs, row) -> GroupRole.valueOf(rs.getString("role"))).optional();
	}

	List<MemberRow> activeMembers(UUID groupId) {
		return jdbc.sql("""
				select user_id, role, joined_at from groups.group_memberships
				 where group_id = :groupId and status = 'ACTIVE'
				 order by case role when 'LEADER' then 0 else 1 end, joined_at, user_id""")
			.param("groupId", groupId)
			.query((rs, row) -> new MemberRow(uuid(rs, "user_id"), GroupRole.valueOf(rs.getString("role")),
					instant(rs, "joined_at")))
			.list();
	}

	int activeMemberCount(UUID groupId) {
		return jdbc.sql("select count(*) from groups.group_memberships where group_id = :groupId and status = 'ACTIVE'")
			.param("groupId", groupId).query(Integer.class).single();
	}

	void insertGroup(UUID id, UUID organizationId, UUID operationId, String name, String description, UUID createdBy,
			Instant now) {
		jdbc.sql("""
				insert into groups.groups (id, organization_id, operation_id, name, description, created_by, created_at, updated_at)
				values (:id, :organizationId, :operationId, :name, :description, :createdBy, :now, :now)""")
			.param("id", id).param("organizationId", organizationId).param("operationId", operationId)
			.param("name", name).param("description", description).param("createdBy", createdBy)
			.param("now", Timestamp.from(now))
			.update();
	}

	void insertMembership(UUID id, UUID groupId, UUID userId, GroupRole role, UUID addedBy, Instant now) {
		jdbc.sql("""
				insert into groups.group_memberships (id, group_id, user_id, role, added_by, joined_at, updated_at)
				values (:id, :groupId, :userId, :role, :addedBy, :now, :now)""")
			.param("id", id).param("groupId", groupId).param("userId", userId).param("role", role.name())
			.param("addedBy", addedBy).param("now", Timestamp.from(now))
			.update();
	}

	/** Üyelik satırı silinmez; geçmiş korunur (DB §5.2). */
	int endMembership(UUID groupId, UUID userId, UUID endedBy, Instant now) {
		return jdbc.sql("""
				update groups.group_memberships
				   set status = 'REMOVED', ended_at = :now, ended_by = :endedBy,
				       end_reason = 'REMOVED_BY_COORDINATOR', updated_at = :now
				 where group_id = :groupId and user_id = :userId and status = 'ACTIVE'""")
			.param("groupId", groupId).param("userId", userId).param("endedBy", endedBy)
			.param("now", Timestamp.from(now))
			.update();
	}

	void setRole(UUID groupId, UUID userId, GroupRole role, Instant now) {
		jdbc.sql("""
				update groups.group_memberships set role = :role, updated_at = :now
				 where group_id = :groupId and user_id = :userId and status = 'ACTIVE'""")
			.param("groupId", groupId).param("userId", userId).param("role", role.name())
			.param("now", Timestamp.from(now))
			.update();
	}

	void updateDetails(UUID groupId, String name, String description, Instant now) {
		jdbc.sql("update groups.groups set name = :name, description = :description, updated_at = :now where id = :id")
			.param("id", groupId).param("name", name).param("description", description)
			.param("now", Timestamp.from(now))
			.update();
	}

	void updateMeetingPoint(UUID groupId, String address, Double lat, Double lon, String description, Instant time,
			UUID updatedBy, Instant now) {
		jdbc.sql("""
				update groups.groups
				   set mp_address = :address, mp_lat = :lat, mp_lon = :lon, mp_description = :description,
				       mp_time = :time, mp_updated_by = :updatedBy, mp_updated_at = :now, updated_at = :now
				 where id = :id""")
			.param("id", groupId).param("address", address).param("lat", lat).param("lon", lon)
			.param("description", description).param("time", Timestamp.from(time)).param("updatedBy", updatedBy)
			.param("now", Timestamp.from(now))
			.update();
	}

	/**
	 * GR-02: kapsam filtresi sorgu düzeyindedir; kapsam dışı grup hiç dönmez (authorization.md §6). Sıralama
	 * {@code id} azalan (UUIDv7 = oluşturma zamanı, §3.5). Arşivli grup K2 üyeliğiyle görünmez (DB §5.2).
	 */
	List<SummaryRow> list(ListScope scope, UUID me, UUID operationId, boolean includeArchived, UUID after, int limit) {
		List<String> visible = new ArrayList<>();
		String archivedFilter = includeArchived ? "" : " and g.archived_at is null";
		if (scope.allOperations()) {
			visible.add("(true" + archivedFilter + ")");
		}
		else if (!scope.operationIds().isEmpty()) {
			visible.add("(g.operation_id in (:scopeOperations)" + archivedFilter + ")");
		}
		if (scope.memberGroups()) {
			visible.add("""
					(g.archived_at is null and exists (select 1 from groups.group_memberships v
					     where v.group_id = g.id and v.user_id = :me and v.status = 'ACTIVE'))""");
		}
		if (visible.isEmpty()) {
			return List.of();
		}
		StringBuilder sql = new StringBuilder("""
				select g.id, g.name, g.operation_id, g.archived_at is not null as archived,
				       (select l.user_id from groups.group_memberships l
				         where l.group_id = g.id and l.role = 'LEADER' and l.status = 'ACTIVE') as leader_id,
				       (select count(*) from groups.group_memberships c
				         where c.group_id = g.id and c.status = 'ACTIVE') as member_count,
				       (select r.role from groups.group_memberships r
				         where r.group_id = g.id and r.user_id = :me and r.status = 'ACTIVE'
				           and g.archived_at is null) as my_role
				  from groups.groups g
				 where (""").append(String.join(" or ", visible)).append(")");
		if (operationId != null) {
			sql.append(" and g.operation_id = :operationId");
		}
		if (after != null) {
			sql.append(" and g.id < :after");
		}
		sql.append(" order by g.id desc limit :limit");

		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("me", me).param("limit", limit);
		if (!scope.allOperations() && !scope.operationIds().isEmpty()) {
			statement = statement.param("scopeOperations", scope.operationIds());
		}
		if (operationId != null) {
			statement = statement.param("operationId", operationId);
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		return statement.query((rs, row) -> new SummaryRow(uuid(rs, "id"), rs.getString("name"),
				uuid(rs, "operation_id"), rs.getBoolean("archived"), uuid(rs, "leader_id"), rs.getInt("member_count"),
				rs.getString("my_role") != null ? GroupRole.valueOf(rs.getString("my_role")) : null))
			.list();
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp != null ? timestamp.toInstant() : null;
	}

}
