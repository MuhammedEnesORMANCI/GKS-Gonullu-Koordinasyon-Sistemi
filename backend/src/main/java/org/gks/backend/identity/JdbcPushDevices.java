package org.gks.backend.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.notifications.NotificationDirectories;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * notifications modülünün push cihaz dizini ({@code identity.devices}, DB §3.3). Cihaz kaydı (ID-26) FCM ile
 * birlikte gelecek; o zamana kadar tablo boş kalır ve push kuyruğuna satır girmez.
 */
@Component
class JdbcPushDevices implements NotificationDirectories.PushDevices {

	private final JdbcClient jdbc;
	private final Clock clock;

	JdbcPushDevices(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public Map<UUID, List<UUID>> activeDevices(Collection<UUID> userIds) {
		Map<UUID, List<UUID>> result = new LinkedHashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		jdbc.sql("select user_id, id from identity.devices where active and user_id in (:ids) order by user_id, id")
			.param("ids", userIds)
			.query((rs, row) -> result.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new java.util.ArrayList<>())
				.add(rs.getObject("id", UUID.class)))
			.list();
		return result;
	}

	@Override
	public Optional<String> token(UUID deviceId) {
		return jdbc.sql("select fcm_token from identity.devices where id = :id and active").param("id", deviceId)
			.query(String.class).optional();
	}

	@Override
	public void deactivate(UUID deviceId, String reason) {
		jdbc.sql("""
				update identity.devices set active = false, deactivated_at = :now, deactivation_reason = :reason
				 where id = :id and active""")
			.param("id", deviceId).param("reason", reason).param("now", Timestamp.from(clock.instant())).update();
	}

}
