package org.gks.backend.tasks;

import java.sql.Timestamp;

import org.gks.backend.access.ParticipationRevoked;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Katılım kaldırılınca kişinin o operasyondaki bekleyen görev atamaları {@code REMOVED} olur (backend §6 N-10).
 * Kabul edilmiş atamalar ve {@code accepted_count} değişmez. Aynı işlemde ve eşzamanlı çalışır.
 */
@Component("tasksParticipationRevokedListener")
class ParticipationRevokedListener {

	private final JdbcClient jdbc;

	ParticipationRevokedListener(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener
	void on(ParticipationRevoked event) {
		jdbc.sql("""
				update tasks.task_assignments a
				   set status = 'REMOVED', removed_at = :at, removed_by = :by, updated_at = :at
				  from tasks.tasks t
				 where a.task_id = t.id and t.operation_id = :operationId
				   and a.user_id = :userId and a.status = 'PENDING'""")
			.param("operationId", event.operationId()).param("userId", event.userId())
			.param("by", event.revokedBy()).param("at", Timestamp.from(event.at()))
			.update();
	}

}
