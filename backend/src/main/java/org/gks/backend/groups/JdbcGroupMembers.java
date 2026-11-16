package org.gks.backend.groups;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcGroupMembers implements GroupMembers {

	private final JdbcClient jdbc;

	JdbcGroupMembers(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, List<UUID>> activeMembers(UUID operationId, Collection<UUID> groupIds) {
		Map<UUID, List<UUID>> result = new LinkedHashMap<>();
		if (groupIds.isEmpty()) {
			return result;
		}
		List<UUID> active = jdbc.sql("""
				select id from groups.groups
				 where operation_id = :operationId and archived_at is null and id in (:ids)""")
			.param("operationId", operationId).param("ids", groupIds)
			.query(UUID.class).list();
		for (UUID groupId : groupIds) {
			if (active.contains(groupId)) {
				result.put(groupId, new ArrayList<>());
			}
		}
		if (result.isEmpty()) {
			return result;
		}
		jdbc.sql("""
				select group_id, user_id from groups.group_memberships
				 where group_id in (:ids) and status = 'ACTIVE'
				 order by joined_at, user_id""")
			.param("ids", result.keySet())
			.query((rs, row) -> result.get(rs.getObject("group_id", UUID.class)).add(rs.getObject("user_id", UUID.class)))
			.list();
		return result;
	}

	@Override
	public List<MeetingPointRef> meetingPoints(java.util.Collection<UUID> operationIds) {
		if (operationIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				select id, operation_id, name, mp_lat, mp_lon, mp_address from groups.groups
				 where operation_id in (:ops) and archived_at is null and mp_lat is not null order by name, id""")
			.param("ops", operationIds)
			.query((rs, row) -> new MeetingPointRef(rs.getObject("id", UUID.class),
					rs.getObject("operation_id", UUID.class), rs.getString("name"), rs.getDouble("mp_lat"),
					rs.getDouble("mp_lon"), rs.getString("mp_address")))
			.list();
	}

	@Override
	public List<GroupRef> groups(UUID operationId) {
		return jdbc.sql("""
				select id, operation_id, name from groups.groups
				 where operation_id = :operationId and archived_at is null order by name, id""")
			.param("operationId", operationId)
			.query((rs, row) -> new GroupRef(rs.getObject("id", UUID.class), rs.getObject("operation_id", UUID.class),
					rs.getString("name")))
			.list();
	}

	@Override
	public List<Membership> memberships(UUID userId) {
		return jdbc.sql("""
				select m.group_id, g.operation_id, m.role from groups.group_memberships m
				  join groups.groups g on g.id = m.group_id
				 where m.user_id = :userId and m.status = 'ACTIVE' and g.archived_at is null and g.operation_id is not null""")
			.param("userId", userId)
			.query((rs, row) -> new Membership(rs.getObject("group_id", UUID.class), rs.getObject("operation_id", UUID.class),
					"LEADER".equals(rs.getString("role"))))
			.list();
	}

	@Override
	public List<UUID> leaders(UUID operationId) {
		return jdbc.sql("""
				select distinct m.user_id from groups.group_memberships m join groups.groups g on g.id = m.group_id
				 where g.operation_id = :operationId and g.archived_at is null and m.status = 'ACTIVE' and m.role = 'LEADER'""")
			.param("operationId", operationId).query(UUID.class).list();
	}

	@Override
	public Map<UUID, String> names(Collection<UUID> groupIds) {
		Map<UUID, String> names = new LinkedHashMap<>();
		if (!groupIds.isEmpty()) {
			jdbc.sql("select id, name from groups.groups where id in (:ids)").param("ids", groupIds)
				.query((rs, row) -> names.put(rs.getObject("id", UUID.class), rs.getString("name"))).list();
		}
		return names;
	}

}
