package org.gks.backend.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.AuditEvent;
import org.gks.backend.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code audit.audit_entries}: yalnızca ekleme ve okuma (güncelleme/silme yöntemi bilinçli olarak yok). */
@Component
class AuditStore {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;

	AuditStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	void insert(AuditEvent e) {
		jdbc.sql("""
				insert into audit.audit_entries (id, actor_id, actor_basis, action, resource_type, resource_id, operation_id,
				                                 client, trace_id, at, details)
				values (:id, :actor, :basis, :action, :type, :resource, :operation, :client, :trace, :at, cast(:details as jsonb))""")
			.param("id", Ids.newId())
			.param("actor", e.actorId())
			.param("basis", e.actorBasis())
			.param("action", e.action())
			.param("type", e.resourceType())
			.param("resource", e.resourceId())
			.param("operation", e.operationId())
			.param("client", e.client())
			.param("trace", e.traceId())
			.param("at", Timestamp.from(e.at()))
			.param("details", e.details().isEmpty() ? null : json.writeValueAsString(e.details()))
			.update();
	}

	record Row(UUID id, UUID actorId, String actorBasis, String action, String resourceType, UUID resourceId,
			UUID operationId, String client, Instant at, Map<String, Object> details) {
	}

	record Filter(Instant from, Instant to, UUID actorId, Set<String> actions, String resourceType, UUID resourceId,
			UUID operationId, String actorBasis) {
	}

	/** Zaman azalan (UUIDv7 kimlik sırası), imleçten önceki kayıtlar; {@code limit + 1} satır. */
	List<Row> list(Filter f, UUID before, int limit) {
		StringBuilder sql = new StringBuilder("""
				select id, actor_id, actor_basis, action, resource_type, resource_id, operation_id, client, at, details
				  from audit.audit_entries where true""");
		if (f.from() != null) {
			sql.append(" and at >= :from");
		}
		if (f.to() != null) {
			sql.append(" and at < :to");
		}
		if (f.actorId() != null) {
			sql.append(" and actor_id = :actor");
		}
		if (!f.actions().isEmpty()) {
			sql.append(" and action in (:actions)");
		}
		if (f.resourceType() != null) {
			sql.append(" and resource_type = :type");
		}
		if (f.resourceId() != null) {
			sql.append(" and resource_id = :resource");
		}
		if (f.operationId() != null) {
			sql.append(" and operation_id = :operation");
		}
		if (f.actorBasis() != null) {
			sql.append(" and actor_basis = :basis");
		}
		if (before != null) {
			sql.append(" and id < :before");
		}
		sql.append(" order by id desc limit :limit");
		var q = jdbc.sql(sql.toString()).param("limit", limit + 1);
		if (f.from() != null) {
			q = q.param("from", Timestamp.from(f.from()));
		}
		if (f.to() != null) {
			q = q.param("to", Timestamp.from(f.to()));
		}
		if (f.actorId() != null) {
			q = q.param("actor", f.actorId());
		}
		if (!f.actions().isEmpty()) {
			q = q.param("actions", f.actions());
		}
		if (f.resourceType() != null) {
			q = q.param("type", f.resourceType());
		}
		if (f.resourceId() != null) {
			q = q.param("resource", f.resourceId());
		}
		if (f.operationId() != null) {
			q = q.param("operation", f.operationId());
		}
		if (f.actorBasis() != null) {
			q = q.param("basis", f.actorBasis());
		}
		if (before != null) {
			q = q.param("before", before);
		}
		return q.query((rs, row) -> {
			String details = rs.getString("details");
			return new Row(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getString("actor_basis"),
					rs.getString("action"), rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
					rs.getObject("operation_id", UUID.class), rs.getString("client"), rs.getTimestamp("at").toInstant(),
					details == null ? Map.of() : json.readValue(details, MAP));
		}).list();
	}

}
