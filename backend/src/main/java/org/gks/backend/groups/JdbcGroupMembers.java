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

}
