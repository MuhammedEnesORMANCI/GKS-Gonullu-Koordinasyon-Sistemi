package org.gks.backend.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.access.AccessDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** access'in kullanıcı bilgisini okuduğu arayüzün gerçekleştirmesi (bağımlılık identity → access yönünde kalır). */
@Component
class JdbcAccessUsers implements AccessDirectory.Users {

	private final JdbcClient jdbc;
	private final UserDirectory directory;

	JdbcAccessUsers(JdbcClient jdbc, UserDirectory directory) {
		this.jdbc = jdbc;
		this.directory = directory;
	}

	@Override
	public Optional<AccessDirectory.UserInfo> find(UUID userId) {
		return jdbc.sql("select id, display_name, status from identity.users where id = :id")
			.param("id", userId)
			.query((rs, row) -> new AccessDirectory.UserInfo(rs.getObject("id", UUID.class), rs.getString("display_name"),
					"ACTIVE".equals(rs.getString("status"))))
			.optional();
	}

	@Override
	public List<UUID> activeUserIds() {
		return jdbc.sql("select id from identity.users where status = 'ACTIVE' order by id").query(UUID.class).list();
	}

	@Override
	public Map<UUID, String> displayNames(Collection<UUID> userIds) {
		return directory.displayNames(userIds);
	}

}
