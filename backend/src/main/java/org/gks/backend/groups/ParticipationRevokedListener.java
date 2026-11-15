package org.gks.backend.groups;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.access.ParticipationRevoked;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Katılım kaldırılınca kişinin o operasyondaki aktif üyelikleri sonlanır (backend §6 N-10, DB §5.2
 * {@code PARTICIPATION_REVOKED}). Aynı işlemde ve eşzamanlı çalışır; kişi bir grubun aktif lideriyse
 * {@code 409 LEADER_REQUIRED} fırlatılır ve katılım kaldırma geri alınır (K-32). Arşivli grupların üyelikleri
 * değiştirilmez (DB §5.2).
 */
@Component
class ParticipationRevokedListener {

	private final JdbcClient jdbc;

	ParticipationRevokedListener(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record Membership(UUID groupId, String role) {
	}

	@EventListener
	void on(ParticipationRevoked event) {
		// Grup satırları kilit noktasıdır (E-5); üyelik değişiklikleriyle sıraya girer.
		List<Membership> memberships = jdbc.sql("""
				select g.id as group_id, m.role
				  from groups.groups g
				  join groups.group_memberships m on m.group_id = g.id
				 where g.operation_id = :operationId and g.archived_at is null
				   and m.user_id = :userId and m.status = 'ACTIVE'
				 order by g.id
				   for update of g""")
			.param("operationId", event.operationId()).param("userId", event.userId())
			.query((rs, row) -> new Membership(rs.getObject("group_id", UUID.class), rs.getString("role")))
			.list();
		if (memberships.stream().anyMatch(m -> m.role().equals("LEADER"))) {
			throw ApiException.of(Code.LEADER_REQUIRED);
		}
		for (Membership membership : memberships) {
			jdbc.sql("""
					update groups.group_memberships
					   set status = 'REMOVED', ended_at = :at, ended_by = :by, end_reason = 'PARTICIPATION_REVOKED',
					       updated_at = :at
					 where group_id = :groupId and user_id = :userId and status = 'ACTIVE'""")
				.param("groupId", membership.groupId()).param("userId", event.userId())
				.param("by", event.revokedBy()).param("at", Timestamp.from(event.at()))
				.update();
		}
	}

}
