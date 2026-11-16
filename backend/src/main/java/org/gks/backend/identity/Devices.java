package org.gks.backend.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.gks.backend.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ID-26 cihaz kaydı ({@code identity.devices}, DB §3.3). Satır silinmez; eski kayıt pasifleşir (nedeniyle). */
@Component
class Devices {

	private final JdbcClient jdbc;
	private final Clock clock;

	Devices(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	record Registration(UUID deviceId, boolean created, Instant registeredAt, Instant lastSeenAt) {
	}

	/**
	 * Aynı kullanıcı + kurulum için tek satır (belirteç yenilenir, pasifse yeniden etkinleşir). Aynı belirteci taşıyan
	 * başka etkin kayıt {@code TOKEN_REPLACED}, aynı kurulumdaki başka kullanıcının kaydı {@code USER_SWITCH} ile
	 * pasifleşir. Kayıt çağıranın oturumuna bağlanır (çıkışta pasifleşmesi için).
	 */
	@Transactional
	Registration register(UUID userId, UUID sessionId, String installationId, String fcmToken, String appVersion) {
		Timestamp now = Timestamp.from(clock.instant());
		Optional<UUID> existing = jdbc.sql("""
				select id from identity.devices where user_id = :u and installation_id = :i for update""")
			.param("u", userId).param("i", installationId).query(UUID.class).optional();
		jdbc.sql("""
				update identity.devices set active = false, deactivated_at = :now, deactivation_reason = 'TOKEN_REPLACED'
				 where active and fcm_token = :t and not (user_id = :u and installation_id = :i)""")
			.param("t", fcmToken).param("u", userId).param("i", installationId).param("now", now).update();
		jdbc.sql("""
				update identity.devices set active = false, deactivated_at = :now, deactivation_reason = 'USER_SWITCH'
				 where active and installation_id = :i and user_id <> :u""")
			.param("i", installationId).param("u", userId).param("now", now).update();
		UUID id;
		boolean created;
		if (existing.isPresent()) {
			id = existing.get();
			created = false;
			jdbc.sql("""
					update identity.devices
					   set fcm_token = :t, app_version = :v, active = true, deactivated_at = null, deactivation_reason = null,
					       last_seen_at = :now
					 where id = :id""")
				.param("t", fcmToken).param("v", appVersion).param("now", now).param("id", id).update();
		}
		else {
			id = Ids.newId();
			created = true;
			jdbc.sql("""
					insert into identity.devices (id, user_id, installation_id, fcm_token, app_version, registered_at, last_seen_at)
					values (:id, :u, :i, :t, :v, :now, :now)""")
				.param("id", id).param("u", userId).param("i", installationId).param("t", fcmToken).param("v", appVersion)
				.param("now", now).update();
		}
		jdbc.sql("update identity.refresh_tokens set device_id = :d where family_id = :s and revoked_at is null")
			.param("d", id).param("s", sessionId).update();
		return jdbc.sql("select registered_at, last_seen_at from identity.devices where id = :id").param("id", id)
			.query((rs, row) -> new Registration(id, created, rs.getTimestamp("registered_at").toInstant(),
					rs.getTimestamp("last_seen_at").toInstant()))
			.single();
	}

}
