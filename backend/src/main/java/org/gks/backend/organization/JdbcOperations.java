package org.gks.backend.organization;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcOperations implements Operations {

	private static final RowMapper<OperationRef> ROW = (rs, row) -> new OperationRef(rs.getObject("id", UUID.class),
			rs.getObject("organization_id", UUID.class), rs.getString("name"),
			OperationState.valueOf(rs.getString("state")));

	private final JdbcClient jdbc;

	JdbcOperations(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<OperationRef> find(UUID id) {
		return jdbc.sql("select id, organization_id, name, state from organization.operations where id = :id")
			.param("id", id).query(ROW).optional();
	}

	@Override
	public Optional<OperationRef> findForShare(UUID id) {
		return jdbc.sql("select id, organization_id, name, state from organization.operations where id = :id for share")
			.param("id", id).query(ROW).optional();
	}

	@Override
	public Map<UUID, String> names(Collection<UUID> ids) {
		Map<UUID, String> names = new HashMap<>();
		if (ids.isEmpty()) {
			return names;
		}
		jdbc.sql("select id, name from organization.operations where id in (:ids)")
			.param("ids", ids)
			.query((rs, row) -> names.put(rs.getObject("id", UUID.class), rs.getString("name")))
			.list();
		return names;
	}

}
