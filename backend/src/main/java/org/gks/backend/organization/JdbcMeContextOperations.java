package org.gks.backend.organization;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.identity.MeContextSources;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** identity'nin {@code MeContext} operasyon kaynağının gerçekleştirmesi (bağımlılık organization → identity). */
@Component
class JdbcMeContextOperations implements MeContextSources.Operations {

	private final JdbcClient jdbc;

	JdbcMeContextOperations(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, MeContextSources.OperationSummary> find(Collection<UUID> operationIds) {
		Map<UUID, MeContextSources.OperationSummary> found = new HashMap<>();
		if (operationIds.isEmpty()) {
			return found;
		}
		jdbc.sql("select id, name, state from organization.operations where id in (:ids)")
			.param("ids", operationIds)
			.query((rs, row) -> found.put(rs.getObject("id", UUID.class), new MeContextSources.OperationSummary(
					rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("state"))))
			.list();
		return found;
	}

}
