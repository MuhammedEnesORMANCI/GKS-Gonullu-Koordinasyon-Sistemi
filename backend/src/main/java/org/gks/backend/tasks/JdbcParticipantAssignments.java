package org.gks.backend.tasks;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.organization.ParticipantDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** organization'ın katılımcı ayrıntısı arayüzünün tasks gerçekleştirmesi (bağımlılık tasks → organization). */
@Component
class JdbcParticipantAssignments implements ParticipantDetails.Assignments {

	private final JdbcClient jdbc;

	JdbcParticipantAssignments(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, Integer> openCounts(UUID operationId, Collection<UUID> userIds) {
		Map<UUID, Integer> counts = new HashMap<>();
		if (userIds.isEmpty()) {
			return counts;
		}
		jdbc.sql("""
				select a.user_id, count(*) as open_count
				  from tasks.task_assignments a join tasks.tasks t on t.id = a.task_id
				 where t.operation_id = :operationId and a.status in ('PENDING', 'ACCEPTED') and a.user_id in (:userIds)
				 group by a.user_id""")
			.param("operationId", operationId).param("userIds", userIds)
			.query((rs, row) -> counts.put(rs.getObject("user_id", UUID.class), rs.getInt("open_count")))
			.list();
		return counts;
	}

	@Override
	public int pendingCount(UUID operationId) {
		return jdbc.sql("""
				select count(*) from tasks.task_assignments a join tasks.tasks t on t.id = a.task_id
				 where t.operation_id = :operationId and a.status = 'PENDING'""")
			.param("operationId", operationId).query(Integer.class).single();
	}

}
