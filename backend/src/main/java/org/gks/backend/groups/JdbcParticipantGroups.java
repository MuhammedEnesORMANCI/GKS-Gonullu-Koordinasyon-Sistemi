package org.gks.backend.groups;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.organization.ParticipantDetails;
import org.gks.backend.organization.ParticipantDetails.GroupRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** organization'ın katılımcı ayrıntısı arayüzünün groups gerçekleştirmesi (bağımlılık groups → organization). */
@Component
class JdbcParticipantGroups implements ParticipantDetails.Groups {

	private final JdbcClient jdbc;

	JdbcParticipantGroups(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, List<GroupRef>> activeGroups(UUID operationId, Collection<UUID> userIds) {
		Map<UUID, List<GroupRef>> groups = new HashMap<>();
		if (userIds.isEmpty()) {
			return groups;
		}
		jdbc.sql("""
				select m.user_id, g.id, g.name
				  from groups.group_memberships m join groups.groups g on g.id = m.group_id
				 where g.operation_id = :operationId and g.archived_at is null
				   and m.status = 'ACTIVE' and m.user_id in (:userIds)
				 order by g.name, g.id""")
			.param("operationId", operationId).param("userIds", userIds)
			.query((rs, row) -> groups.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new ArrayList<>())
				.add(new GroupRef(rs.getObject("id", UUID.class), rs.getString("name"))))
			.list();
		return groups;
	}

	@Override
	public Optional<Set<UUID>> activeMembers(UUID operationId, UUID groupId) {
		boolean exists = jdbc.sql("""
				select exists (select 1 from groups.groups
				                where id = :groupId and operation_id = :operationId and archived_at is null)""")
			.param("groupId", groupId).param("operationId", operationId).query(Boolean.class).single();
		if (!exists) {
			return Optional.empty();
		}
		return Optional.of(new HashSet<>(jdbc.sql("""
				select user_id from groups.group_memberships where group_id = :groupId and status = 'ACTIVE'""")
			.param("groupId", groupId).query(UUID.class).list()));
	}

}
