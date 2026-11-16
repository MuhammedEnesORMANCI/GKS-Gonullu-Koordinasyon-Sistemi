package org.gks.backend.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcUserDirectory implements UserDirectory {

	private final JdbcClient jdbc;

	JdbcUserDirectory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, String> displayNames(Collection<UUID> userIds) {
		Map<UUID, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		jdbc.sql("select id, display_name from identity.users where id in (:ids)")
			.param("ids", userIds)
			.query((rs, row) -> names.put(rs.getObject("id", UUID.class), rs.getString("display_name")))
			.list();
		return names;
	}

	@Override
	public java.util.List<UUID> activeUserIds() {
		return jdbc.sql("select id from identity.users where status = 'ACTIVE' order by id").query(UUID.class).list();
	}

	@Override
	public java.util.Set<UUID> activeAmong(Collection<UUID> userIds) {
		if (userIds.isEmpty()) {
			return java.util.Set.of();
		}
		return new java.util.HashSet<>(jdbc.sql("select id from identity.users where status = 'ACTIVE' and id in (:ids)")
			.param("ids", userIds).query(UUID.class).list());
	}

	@Override
	public Map<UUID, UserSummary> summaries(Collection<UUID> userIds) {
		Map<UUID, UserSummary> summaries = new HashMap<>();
		if (userIds.isEmpty()) {
			return summaries;
		}
		jdbc.sql("select id, display_name, status, full_name, email, phone from identity.users where id in (:ids)")
			.param("ids", userIds)
			.query((rs, row) -> summaries.put(rs.getObject("id", UUID.class), new UserSummary(
					rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("status"),
					rs.getString("full_name"), rs.getString("email"), rs.getString("phone"))))
			.list();
		return summaries;
	}

}
