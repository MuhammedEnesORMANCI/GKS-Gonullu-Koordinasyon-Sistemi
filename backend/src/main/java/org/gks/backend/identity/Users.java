package org.gks.backend.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** {@code identity.users} erişimi (DB §3.1). */
@Component
class Users {

	static final int LOCK_THRESHOLD = 5;   // AC-03.2

	private final JdbcClient jdbc;

	Users(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	enum Status {
		ACTIVE, DISABLED, CLOSED
	}

	/** Girişin ihtiyaç duyduğu alanlar; parola özeti metne dönüşmez. */
	record Credentials(UUID id, String passwordHash, Status status, boolean mustChangePassword, Instant lockedUntil) {
		@Override
		public String toString() {
			return "Credentials[id=" + id + ", status=" + status + "]";
		}
	}

	void insert(UUID id, String email, String passwordHash, String fullName, String displayName, String phone,
			boolean mustChangePassword, Instant now) {
		jdbc.sql("""
				insert into identity.users
				    (id, email, password_hash, full_name, display_name, phone, must_change_password, created_at, updated_at)
				values (:id, :email, :hash, :fullName, :displayName, :phone, :mustChange, :now, :now)""")
			.param("id", id)
			.param("email", email)
			.param("hash", passwordHash)
			.param("fullName", fullName)
			.param("displayName", displayName)
			.param("phone", phone)
			.param("mustChange", mustChangePassword)
			.param("now", Timestamp.from(now))
			.update();
	}

	Optional<Credentials> findByEmail(String email) {
		return jdbc.sql("""
				select id, password_hash, status, must_change_password, locked_until
				  from identity.users where email = :email""")
			.param("email", email)
			.query((rs, row) -> new Credentials(rs.getObject("id", UUID.class), rs.getString("password_hash"),
					Status.valueOf(rs.getString("status")), rs.getBoolean("must_change_password"),
					instant(rs.getTimestamp("locked_until"))))
			.optional();
	}

	/** Erişim belirtecinin kullanıcısı ve oturumu: hesap durumu ve oturumun canlılığı (§2.2 adım 3–4). */
	record AuthenticationState(Status status, boolean sessionLive, boolean mustChangePassword) {
	}

	/**
	 * LD-47: oturum denetimi her istekte veritabanından yapılır; bellekte iptal listesi tutulmaz. Oturum geçerlidir ⇔
	 * aynı kullanıcıya ait zincirde ({@code family_id = sid}) iptal edilmemiş, değiştirilmemiş ve süresi geçmemiş bir
	 * yenileme belirteci vardır. Hesap durumu aynı sorguda okunur; istek başına tek veritabanı gidişi
	 * ({@code pk_users} + {@code ix_refresh_tokens_family}).
	 *
	 * @return kullanıcı yoksa boş
	 */
	Optional<AuthenticationState> authenticationState(UUID userId, UUID sessionId, Instant now) {
		return jdbc.sql("""
				select u.status, u.must_change_password,
				       exists (select 1 from identity.refresh_tokens r
				                where r.family_id = :sessionId and r.user_id = u.id
				                  and r.revoked_at is null and r.replaced_by is null
				                  and r.expires_at > :now) as session_live
				  from identity.users u
				 where u.id = :userId""")
			.param("userId", userId)
			.param("sessionId", sessionId)
			.param("now", Timestamp.from(now))
			.query((rs, row) -> new AuthenticationState(Status.valueOf(rs.getString("status")),
					rs.getBoolean("session_live"), rs.getBoolean("must_change_password")))
			.optional();
	}

	/**
	 * Hatalı parola sayacı tek atomik {@code UPDATE} ile artar (DB E-16); eşzamanlı denemeler sayım kaybetmez.
	 * Süresi geçmiş bir kilitten sonraki ilk hata sayacı 1'den başlatır.
	 *
	 * @return kilit bitişi, hesap bu denemeyle kilitlendiyse
	 */
	Optional<Instant> recordFailure(UUID id, Instant now, Instant lockUntil) {
		return jdbc.sql("""
				update identity.users set
				    failed_login_count = case when locked_until <= :now then 1 else failed_login_count + 1 end,
				    locked_until = case
				        when (case when locked_until <= :now then 1 else failed_login_count + 1 end) >= :threshold
				            then :lockUntil
				        when locked_until <= :now then null
				        else locked_until end,
				    updated_at = :now
				 where id = :id
				returning locked_until""")
			.param("id", id)
			.param("now", Timestamp.from(now))
			.param("lockUntil", Timestamp.from(lockUntil))
			.param("threshold", LOCK_THRESHOLD)
			.query((rs, row) -> Optional.ofNullable(instant(rs.getTimestamp("locked_until"))))
			.single();
	}

	void resetFailures(UUID id, Instant now) {
		jdbc.sql("""
				update identity.users set failed_login_count = 0, locked_until = null, updated_at = :now
				 where id = :id and (failed_login_count <> 0 or locked_until is not null)""")
			.param("id", id)
			.param("now", Timestamp.from(now))
			.update();
	}

	/** TOTP durumu (DB §3.1): {@code totp_secret} doluysa ve {@code totp_enrolled_at} boşsa kurulum bekliyor. */
	record Totp(String encryptedSecret, Instant enrolledAt, Long lastUsedStep) {
		boolean enrolled() {
			return enrolledAt != null;
		}

		@Override
		public String toString() {
			return "Totp[enrolled=" + enrolled() + "]";
		}
	}

	/** Satır kilitlenerek okunur: aynı kullanıcının eşzamanlı TOTP doğrulamaları sıraya girer (adım tekrarı yok). */
	Totp lockTotp(UUID id) {
		return jdbc.sql("select totp_secret, totp_enrolled_at, totp_last_used_step from identity.users where id = :id for update")
			.param("id", id)
			.query((rs, row) -> new Totp(rs.getString("totp_secret"), instant(rs.getTimestamp("totp_enrolled_at")),
					(Long) rs.getObject("totp_last_used_step")))
			.single();
	}

	/** ID-03: bekleyen kurulum anahtarı (kalıcılaşması ID-04'teki ilk doğru koddadır). Kurulu TOTP'nin üzerine yazmaz. */
	int setPendingTotp(UUID id, String encryptedSecret, Instant now) {
		return jdbc.sql("""
				update identity.users set totp_secret = :secret, totp_last_used_step = null, updated_at = :now
				 where id = :id and totp_enrolled_at is null""")
			.param("id", id).param("secret", encryptedSecret).param("now", Timestamp.from(now))
			.update();
	}

	/** Doğru kod: adım kaydedilir; kurulum sürüyorsa kalıcılaşır (ID-04). */
	void acceptTotp(UUID id, long step, Instant now) {
		jdbc.sql("""
				update identity.users
				   set totp_last_used_step = :step, totp_enrolled_at = coalesce(totp_enrolled_at, :now), updated_at = :now
				 where id = :id""")
			.param("id", id).param("step", step).param("now", Timestamp.from(now))
			.update();
	}

	record Account(UUID id, String email, String passwordHash, String fullName, String displayName, String phone,
			Status status, boolean mustChangePassword, boolean mfaEnrolled, Instant createdAt) {
		@Override
		public String toString() {
			return "Account[id=" + id + ", status=" + status + "]";
		}
	}

	Optional<Account> find(UUID id) {
		return jdbc.sql("""
				select id, email, password_hash, full_name, display_name, phone, status, must_change_password,
				       totp_enrolled_at, created_at
				  from identity.users where id = :id""")
			.param("id", id)
			.query((rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
					rs.getString("password_hash"), rs.getString("full_name"), rs.getString("display_name"),
					rs.getString("phone"), Status.valueOf(rs.getString("status")), rs.getBoolean("must_change_password"),
					rs.getTimestamp("totp_enrolled_at") != null, instant(rs.getTimestamp("created_at"))))
			.optional();
	}

	/** ID-08: yeni özet, zorunlu değişim işareti kalkar (AC-03.5). */
	void changePassword(UUID id, String passwordHash, Instant now) {
		jdbc.sql("""
				update identity.users
				   set password_hash = :hash, must_change_password = false, password_changed_at = :now, updated_at = :now
				 where id = :id""")
			.param("id", id).param("hash", passwordHash).param("now", Timestamp.from(now))
			.update();
	}

	/** ID-10. */
	void updateProfile(UUID id, String fullName, String displayName, String phone, Instant now) {
		jdbc.sql("""
				update identity.users set full_name = :fullName, display_name = :displayName, phone = :phone, updated_at = :now
				 where id = :id""")
			.param("id", id).param("fullName", fullName).param("displayName", displayName).param("phone", phone)
			.param("now", Timestamp.from(now))
			.update();
	}

	// ---------------------------------------------------------------- yönetim (ID-19 – ID-25)

	/** ID-19 satırı; izdüşüm servis katmanında uygulanır. */
	record Listed(UUID id, String email, String fullName, String displayName, String phone, Status status) {
		@Override
		public String toString() {
			return "Listed[id=" + id + ", status=" + status + "]";
		}
	}

	/**
	 * Kimlik sırasıyla imleçli liste ({@code limit + 1} satır okunur). {@code ids} {@code null} ise tüm kurum,
	 * değilse yalnızca bu kişiler (operasyon katılımcıları).
	 */
	List<Listed> list(Status status, Collection<UUID> ids, UUID after, int limit) {
		if (ids != null && ids.isEmpty()) {
			return List.of();
		}
		StringBuilder sql = new StringBuilder(
				"select id, email, full_name, display_name, phone, status from identity.users where true");
		if (status != null) {
			sql.append(" and status = :status");
		}
		if (ids != null) {
			sql.append(" and id in (:ids)");
		}
		if (after != null) {
			sql.append(" and id > :after");
		}
		sql.append(" order by id limit :limit");
		var query = jdbc.sql(sql.toString()).param("limit", limit + 1);
		if (status != null) {
			query = query.param("status", status.name());
		}
		if (ids != null) {
			query = query.param("ids", ids);
		}
		if (after != null) {
			query = query.param("after", after);
		}
		return query.query((rs, row) -> new Listed(rs.getObject("id", UUID.class), rs.getString("email"),
				rs.getString("full_name"), rs.getString("display_name"), rs.getString("phone"),
				Status.valueOf(rs.getString("status")))).list();
	}

	long count(Status status, Collection<UUID> ids) {
		if (ids != null && ids.isEmpty()) {
			return 0;
		}
		StringBuilder sql = new StringBuilder("select count(*) from identity.users where true");
		if (status != null) {
			sql.append(" and status = :status");
		}
		if (ids != null) {
			sql.append(" and id in (:ids)");
		}
		var query = jdbc.sql(sql.toString());
		if (status != null) {
			query = query.param("status", status.name());
		}
		if (ids != null) {
			query = query.param("ids", ids);
		}
		return query.query(Long.class).single();
	}

	/** ID-20 – ID-25: yönetim işlemleri satırı kilitleyerek okur (eşzamanlı iki işlem sıraya girer). */
	record Managed(UUID id, String email, String fullName, String displayName, String phone, Status status,
			boolean mfaEnrolled, boolean totpPresent, Instant lockedUntil, Instant createdAt) {
		@Override
		public String toString() {
			return "Managed[id=" + id + ", status=" + status + "]";
		}
	}

	Optional<Managed> managed(UUID id, boolean forUpdate) {
		return jdbc.sql("""
				select id, email, full_name, display_name, phone, status, totp_secret is not null as totp_present,
				       totp_enrolled_at, locked_until, created_at
				  from identity.users where id = :id""" + (forUpdate ? " for update" : ""))
			.param("id", id)
			.query((rs, row) -> new Managed(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
					rs.getString("display_name"), rs.getString("phone"), Status.valueOf(rs.getString("status")),
					rs.getTimestamp("totp_enrolled_at") != null, rs.getBoolean("totp_present"),
					instant(rs.getTimestamp("locked_until")), instant(rs.getTimestamp("created_at"))))
			.optional();
	}

	/** ID-21 (devre dışı) / ID-22 (etkin). */
	void setStatus(UUID id, Status status, Instant now) {
		jdbc.sql("""
				update identity.users set status = :status, disabled_at = :disabledAt, updated_at = :now
				 where id = :id""")
			.param("id", id).param("status", status.name())
			.param("disabledAt", status == Status.DISABLED ? Timestamp.from(now) : null)
			.param("now", Timestamp.from(now))
			.update();
	}

	/** ID-24: yönetici sıfırlaması; ilk girişte değişim zorunlu (AC-03.5), kilit ve hatalı deneme sayacı temizlenir. */
	void resetPasswordByAdmin(UUID id, String passwordHash, Instant now) {
		jdbc.sql("""
				update identity.users
				   set password_hash = :hash, must_change_password = true, password_changed_at = :now,
				       failed_login_count = 0, locked_until = null, updated_at = :now
				 where id = :id""")
			.param("id", id).param("hash", passwordHash).param("now", Timestamp.from(now))
			.update();
	}

	/** ID-25: TOTP silinir; sonraki yönetim girişinde yeniden kurulur (ID-03). */
	void clearTotp(UUID id, Instant now) {
		jdbc.sql("""
				update identity.users
				   set totp_secret = null, totp_enrolled_at = null, totp_last_used_step = null, updated_at = :now
				 where id = :id""")
			.param("id", id).param("now", Timestamp.from(now))
			.update();
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp != null ? timestamp.toInstant() : null;
	}

}
