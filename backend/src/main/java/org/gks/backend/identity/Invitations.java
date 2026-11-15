package org.gks.backend.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code identity.invitations} (DB §3.2). Kod ve belirteç yalnızca özetle. */
@Repository
class Invitations {

	private static final String COLUMNS = """
			id, kind, email, user_id, scope_type, scope_id, expires_at, status, used_at, created_by, created_at""";

	private final JdbcClient jdbc;

	Invitations(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	enum Kind {
		NEW_USER, OPERATION
	}

	enum Status {
		PENDING, ACCEPTED, DECLINED, EXPIRED, REVOKED
	}

	record Row(UUID id, Kind kind, String email, UUID userId, String scopeType, UUID scopeId, Instant expiresAt,
			Status status, Instant usedAt, UUID createdBy, Instant createdAt) {

		/** Geçerlilik her zaman durum ve süre birlikte denetlenerek okunur (DB §3.2). */
		Status effectiveStatus(Instant now) {
			return status == Status.PENDING && !now.isBefore(expiresAt) ? Status.EXPIRED : status;
		}
	}

	void insert(UUID id, Kind kind, String email, UUID userId, String scopeType, UUID scopeId, String tokenHash,
			String codeHash, Instant expiresAt, UUID createdBy, Instant now) {
		jdbc.sql("""
				insert into identity.invitations
				    (id, kind, email, user_id, scope_type, scope_id, token_hash, code_hash, expires_at, created_by, created_at)
				values (:id, :kind, :email, :userId, :scopeType, :scopeId, :token, :code, :expires, :createdBy, :now)""")
			.param("id", id).param("kind", kind.name()).param("email", email).param("userId", userId)
			.param("scopeType", scopeType).param("scopeId", scopeId).param("token", tokenHash).param("code", codeHash)
			.param("expires", Timestamp.from(expiresAt)).param("createdBy", createdBy).param("now", Timestamp.from(now))
			.update();
	}

	/** Süresi dolmuş bekleyen davet benzersizlik indeksini tutmasın diye önce {@code EXPIRED} yapılır (DB §3.2). */
	void expireStale(String email, String scopeType, UUID scopeId, Instant now) {
		jdbc.sql("""
				update identity.invitations set status = 'EXPIRED'
				 where email = :email and scope_type = :scopeType and scope_id = :scopeId
				   and status = 'PENDING' and expires_at <= :now""")
			.param("email", email).param("scopeType", scopeType).param("scopeId", scopeId)
			.param("now", Timestamp.from(now)).update();
	}

	Optional<Row> find(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from identity.invitations where id = :id")
			.param("id", id).query(Invitations::row).optional();
	}

	Optional<Row> lock(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from identity.invitations where id = :id for update")
			.param("id", id).query(Invitations::row).optional();
	}

	Optional<Row> findByCode(String codeHash, boolean forUpdate) {
		return jdbc.sql("select " + COLUMNS + " from identity.invitations where code_hash = :code"
				+ (forUpdate ? " for update" : ""))
			.param("code", codeHash).query(Invitations::row).optional();
	}

	void close(UUID id, Status status, UUID userId, Instant now) {
		jdbc.sql("""
				update identity.invitations
				   set status = :status, used_at = :now, user_id = coalesce(user_id, :userId)
				 where id = :id""")
			.param("id", id).param("status", status.name()).param("userId", userId).param("now", Timestamp.from(now))
			.update();
	}

	void revoke(UUID id) {
		jdbc.sql("update identity.invitations set status = 'REVOKED' where id = :id").param("id", id).update();
	}

	/**
	 * ID-13: {@code scopes} {@code null} ise tüm kapsamlar (ADM). Sıralama {@code id DESC} (UUIDv7, en yeni önce).
	 */
	List<Row> list(Collection<UUID> scopes, String scopeType, UUID scopeId, Collection<Status> statuses, UUID after,
			int limit, Instant now) {
		StringBuilder sql = new StringBuilder("select " + COLUMNS + " from identity.invitations where true");
		if (scopes != null) {
			if (scopes.isEmpty()) {
				return List.of();
			}
			sql.append(" and scope_id in (:scopes)");
		}
		if (scopeType != null) {
			sql.append(" and scope_type = :scopeType");
		}
		if (scopeId != null) {
			sql.append(" and scope_id = :scopeId");
		}
		if (statuses != null && !statuses.isEmpty()) {
			// EXPIRED: kayıtta EXPIRED veya süresi dolmuş PENDING; PENDING: süresi dolmamış.
			sql.append(" and (false");
			if (statuses.contains(Status.PENDING)) {
				sql.append(" or (status = 'PENDING' and expires_at > :now)");
			}
			if (statuses.contains(Status.EXPIRED)) {
				sql.append(" or status = 'EXPIRED' or (status = 'PENDING' and expires_at <= :now)");
			}
			for (Status status : List.of(Status.ACCEPTED, Status.DECLINED, Status.REVOKED)) {
				if (statuses.contains(status)) {
					sql.append(" or status = '").append(status.name()).append("'");
				}
			}
			sql.append(")");
		}
		if (after != null) {
			sql.append(" and id < :after");
		}
		sql.append(" order by id desc limit :limit");
		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit).param("now", Timestamp.from(now));
		if (scopes != null) {
			spec = spec.param("scopes", scopes);
		}
		if (scopeType != null) {
			spec = spec.param("scopeType", scopeType);
		}
		if (scopeId != null) {
			spec = spec.param("scopeId", scopeId);
		}
		if (after != null) {
			spec = spec.param("after", after);
		}
		return spec.query(Invitations::row).list();
	}

	/** {@code MeContext.pendingInvitations}: kullanıcıya gelmiş, geçerli operasyon davetleri. */
	List<Row> pendingFor(UUID userId, Instant now) {
		return jdbc.sql("select " + COLUMNS + " from identity.invitations"
				+ " where user_id = :userId and kind = 'OPERATION' and status = 'PENDING' and expires_at > :now"
				+ " order by id desc")
			.param("userId", userId).param("now", Timestamp.from(now)).query(Invitations::row).list();
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		Timestamp used = rs.getTimestamp("used_at");
		return new Row(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")), rs.getString("email"),
				rs.getObject("user_id", UUID.class), rs.getString("scope_type"), rs.getObject("scope_id", UUID.class),
				rs.getTimestamp("expires_at").toInstant(), Status.valueOf(rs.getString("status")),
				used == null ? null : used.toInstant(), rs.getObject("created_by", UUID.class),
				rs.getTimestamp("created_at").toInstant());
	}

}
