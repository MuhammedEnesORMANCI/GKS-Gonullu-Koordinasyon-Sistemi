package org.gks.backend.groups;

import java.util.List;
import java.util.UUID;

import org.gks.backend.identity.MeContextSources;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** identity'nin {@code MeContext} grup kaynağının gerçekleştirmesi (bağımlılık groups → identity). */
@Component
class JdbcMeContextGroups implements MeContextSources.Groups {

	private final JdbcClient jdbc;

	JdbcMeContextGroups(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Arşivli grupta üyelik yetki vermez (DB §5.2); listede de yer almaz. */
	@Override
	public List<MeContextSources.GroupMembership> activeMemberships(UUID userId) {
		return jdbc.sql("""
				select g.id, g.name, g.operation_id, m.role
				  from groups.group_memberships m join groups.groups g on g.id = m.group_id
				 where m.user_id = :userId and m.status = 'ACTIVE' and g.archived_at is null
				 order by g.name, g.id""")
			.param("userId", userId)
			.query((rs, row) -> new MeContextSources.GroupMembership(rs.getObject("id", UUID.class), rs.getString("name"),
					rs.getObject("operation_id", UUID.class), rs.getString("role")))
			.list();
	}

}
