package org.gks.backend.location;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code location.location_sharings} ve {@code location.last_locations} (DB §6.1 – §6.2). Geçmiş yok (K-16). */
@Component
class LocationStore {

	private final JdbcClient jdbc;
	private final RowMapper<SharingRow> sharing = (rs, row) -> new SharingRow(uuid(rs, "user_id"), uuid(rs, "operation_id"),
			rs.getString("state"), instant(rs, "started_at"), instant(rs, "stopped_at"), rs.getString("availability_reason"),
			rs.getString("stop_reason"), instant(rs, "last_sent_at"));

	LocationStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record SharingRow(UUID userId, UUID operationId, String state, Instant startedAt, Instant stoppedAt,
			String availabilityReason, String stopReason, Instant lastSentAt) {

		boolean sharing() {
			return "ACTIVE".equals(state) || "UNAVAILABLE".equals(state);
		}
	}

	record LastLocation(UUID userId, double lat, double lon, Double accuracy, Instant measuredAt, Instant receivedAt,
			boolean clockSkew) {
	}

	Optional<SharingRow> lockSharing(UUID userId, UUID operationId) {
		return jdbc.sql("select * from location.location_sharings where user_id = :u and operation_id = :op for update")
			.param("u", userId).param("op", operationId).query(sharing).optional();
	}

	/** LC-01: başlat veya kullanılabilirliği güncelle; zaten paylaşılıyorsa {@code started_at} korunur. */
	void start(UUID userId, UUID operationId, String state, String availabilityReason, Instant now) {
		jdbc.sql("""
				insert into location.location_sharings (user_id, operation_id, state, started_at, availability_reason, updated_at)
				values (:u, :op, :state, :now, :reason, :now)
				on conflict (user_id, operation_id) do update
				   set state = excluded.state, availability_reason = excluded.availability_reason, stop_reason = null,
				       started_at = case when location.location_sharings.state in ('ACTIVE', 'UNAVAILABLE')
				                         then location.location_sharings.started_at else excluded.started_at end,
				       updated_at = excluded.updated_at""")
			.param("u", userId).param("op", operationId).param("state", state).param("reason", availabilityReason)
			.param("now", Timestamp.from(now)).update();
	}

	/** @return durdurulan paylaşımcılar ({@code userId == null}: operasyonun tümü) */
	List<UUID> stop(UUID operationId, UUID userId, String reason, Instant now) {
		return jdbc.sql("""
				update location.location_sharings
				   set state = 'STOPPED', stopped_at = :now, stop_reason = :reason, availability_reason = null, updated_at = :now
				 where operation_id = :op and state in ('ACTIVE', 'UNAVAILABLE')
				   and (cast(:u as uuid) is null or user_id = :u)
				returning user_id""")
			.param("op", operationId).param("u", userId).param("reason", reason).param("now", Timestamp.from(now))
			.query(UUID.class).list();
	}

	List<SharingRow> sharingsOf(UUID userId) {
		return jdbc.sql("select * from location.location_sharings where user_id = :u order by operation_id")
			.param("u", userId).query(sharing).list();
	}

	List<SharingRow> sharingsIn(UUID operationId) {
		return jdbc.sql("select * from location.location_sharings where operation_id = :op").param("op", operationId)
			.query(sharing).list();
	}

	/**
	 * Tek ifadeli upsert: yalnızca gelen ölçüm mevcut olandan yeniyse yazılır (sıra dışı ölçüm eskiyi ezmez).
	 * Ölçüm gelince paylaşım {@code ACTIVE} olur (konum alınabiliyor) ve {@code last_sent_at} güncellenir.
	 */
	void record(UUID userId, UUID operationId, double lat, double lon, double accuracy, Instant measuredAt, Instant now,
			boolean clockSkew) {
		jdbc.sql("""
				insert into location.last_locations (user_id, operation_id, lat, lon, accuracy, measured_at, received_at, clock_skew_flag)
				values (:u, :op, :lat, :lon, :acc, :measured, :now, :skew)
				on conflict (user_id, operation_id) do update
				   set lat = excluded.lat, lon = excluded.lon, accuracy = excluded.accuracy, measured_at = excluded.measured_at,
				       received_at = excluded.received_at, clock_skew_flag = excluded.clock_skew_flag
				 where location.last_locations.measured_at < excluded.measured_at""")
			.param("u", userId).param("op", operationId).param("lat", lat).param("lon", lon).param("acc", accuracy)
			.param("measured", Timestamp.from(measuredAt)).param("now", Timestamp.from(now)).param("skew", clockSkew).update();
		jdbc.sql("""
				update location.location_sharings set state = 'ACTIVE', availability_reason = null, last_sent_at = :now, updated_at = :now
				 where user_id = :u and operation_id = :op""")
			.param("u", userId).param("op", operationId).param("now", Timestamp.from(now)).update();
	}

	List<LastLocation> lastLocations(UUID operationId) {
		return jdbc.sql("select * from location.last_locations where operation_id = :op").param("op", operationId)
			.query((rs, row) -> new LastLocation(uuid(rs, "user_id"), rs.getDouble("lat"), rs.getDouble("lon"),
					(Double) rs.getObject("accuracy"), instant(rs, "measured_at"), instant(rs, "received_at"),
					rs.getBoolean("clock_skew_flag")))
			.list();
	}

	/** ADR-019: kapanışta son konumların saklama süresi başlar. */
	void retainUntil(UUID operationId, Instant until) {
		jdbc.sql("update location.last_locations set retain_until = :until where operation_id = :op and retain_until is null")
			.param("op", operationId).param("until", Timestamp.from(until)).update();
	}

	void deleteLastLocation(UUID userId, UUID operationId) {
		jdbc.sql("delete from location.last_locations where user_id = :u and operation_id = :op")
			.param("u", userId).param("op", operationId).update();
	}

	int purgeExpired(Instant now) {
		return jdbc.sql("delete from location.last_locations where retain_until <= :now").param("now", Timestamp.from(now))
			.update();
	}

	private static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

}
