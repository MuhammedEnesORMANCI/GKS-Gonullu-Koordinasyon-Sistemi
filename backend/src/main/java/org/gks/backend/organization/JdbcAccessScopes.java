package org.gks.backend.organization;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.access.AccessDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** access'in kapsam (kurum, operasyon) bilgisini okuduğu arayüzün gerçekleştirmesi. */
@Component
class JdbcAccessScopes implements AccessDirectory.Scopes {

	private final JdbcClient jdbc;
	private final Operations operations;

	JdbcAccessScopes(JdbcClient jdbc, Operations operations) {
		this.jdbc = jdbc;
		this.operations = operations;
	}

	/** MVP'de tek kurum vardır (A-01); birden fazla satır yapılandırma hatasıdır. */
	@Override
	public UUID organizationId() {
		List<UUID> ids = jdbc.sql("select id from organization.organizations").query(UUID.class).list();
		if (ids.size() != 1) {
			throw new IllegalStateException("Tam olarak bir kurum bekleniyordu");
		}
		return ids.getFirst();
	}

	@Override
	public Optional<AccessDirectory.ScopeInfo> operationForShare(UUID operationId) {
		return operations.findForShare(operationId)
			.map(op -> new AccessDirectory.ScopeInfo(op.id(), op.name(), op.state().isClosed(), op.state().name()));
	}

	@Override
	public List<UUID> operationIds() {
		return jdbc.sql("select id from organization.operations order by created_at, id").query(UUID.class).list();
	}

	@Override
	public Map<UUID, String> names(Collection<UUID> scopeIds) {
		Map<UUID, String> names = new HashMap<>(operations.names(scopeIds));
		if (!scopeIds.isEmpty()) {
			jdbc.sql("select id, name from organization.organizations where id in (:ids)").param("ids", scopeIds)
				.query((rs, row) -> names.put(rs.getObject("id", UUID.class), rs.getString("name"))).list();
		}
		return names;
	}

}
