package org.gks.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Ertelenmiş kısıt tetikleyicisi {@code ct_idempotency_keys_completed} ve durum kısıtı (DB §8.3, LD-45;
 * T-IDEM-11 – T-IDEM-14). İşlem sınırları açıkça yönetilsin diye düz JDBC bağlantısı kullanılır.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyTriggerTests {

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcClient jdbc;

	/** T-IDEM-11: durum yazılmadan commit → reddedilir; iş değişikliği ve satır geri alınır. */
	@Test
	void commitWithNullStatusIsRejectedAndBusinessChangeRolledBack() throws Exception {
		String principal = UUID.randomUUID().toString();
		UUID user = UUID.randomUUID();
		try (Connection connection = transaction()) {
			insertKey(connection, principal, "anahtar-11", null, Instant.now().plus(Duration.ofHours(24)));
			insertUser(connection, user);
			assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
				.hasMessageContaining("ct_idempotency_keys_completed");
		}
		assertThat(keyCount(principal)).isZero();
		assertThat(userCount(user)).isZero();
	}

	/** T-IDEM-12: satır eklenip aynı işlemde silinir → commit başarılı, iş değişikliği kalıcı. */
	@Test
	void deletedRowPassesAndBusinessChangeCommits() throws Exception {
		String principal = UUID.randomUUID().toString();
		UUID user = UUID.randomUUID();
		try (Connection connection = transaction()) {
			insertKey(connection, principal, "anahtar-12", null, Instant.now().plus(Duration.ofHours(24)));
			insertUser(connection, user);
			try (PreparedStatement delete = connection.prepareStatement(
					"delete from platform.idempotency_keys where principal_key = ? and idem_key = ?")) {
				delete.setString(1, principal);
				delete.setString(2, "anahtar-12");
				delete.executeUpdate();
			}
			connection.commit();
		}
		assertThat(keyCount(principal)).isZero();
		assertThat(userCount(user)).isOne();
	}

	/** T-IDEM-13 (a): geri alınan işlemde tetikleyici çalışmaz; hata yok, satır yok. */
	@Test
	void rollbackDiscardsDeferredCheck() throws Exception {
		String principal = UUID.randomUUID().toString();
		try (Connection connection = transaction()) {
			insertKey(connection, principal, "anahtar-13a", null, Instant.now().plus(Duration.ofHours(24)));
			connection.rollback();
		}
		assertThat(keyCount(principal)).isZero();
	}

	/** T-IDEM-13 (b): geri alınan savepoint içindeki satır denetlenmez; dış satır denetlenir ve geçer. */
	@Test
	void rolledBackSavepointRowIsNotChecked() throws Exception {
		String principal = UUID.randomUUID().toString();
		try (Connection connection = transaction()) {
			insertKey(connection, principal, "anahtar-dis", 200, Instant.now().plus(Duration.ofHours(24)));
			Savepoint savepoint = connection.setSavepoint();
			insertKey(connection, principal, "anahtar-ic", null, Instant.now().plus(Duration.ofHours(24)));
			connection.rollback(savepoint);
			connection.commit();
		}
		assertThat(jdbc.sql("select idem_key from platform.idempotency_keys where principal_key = ?")
			.param(principal).query(String.class).list()).containsExactly("anahtar-dis");
	}

	/**
	 * T-IDEM-14: süresi dolmuş satır silinip aynı anahtarla yeniden eklenir ve {@code 200} yazılır → commit başarılı;
	 * yeni satırın durumu denetlenir. {@code 409} yazmak {@code ck_idempotency_keys_status} ihlalidir.
	 */
	@Test
	void expiredRowReplacedInSameTransactionIsCheckedAsNewRow() throws Exception {
		String principal = UUID.randomUUID().toString();
		Instant past = Instant.now().minus(Duration.ofHours(25));
		try (Connection connection = transaction()) {
			insertKey(connection, principal, "anahtar-14", 201, past);
			connection.commit();
		}
		try (Connection connection = transaction()) {
			try (PreparedStatement delete = connection.prepareStatement("""
					delete from platform.idempotency_keys
					 where principal_key = ? and idem_key = ? and expires_at <= now()""")) {
				delete.setString(1, principal);
				delete.setString(2, "anahtar-14");
				assertThat(delete.executeUpdate()).isOne();
			}
			insertKey(connection, principal, "anahtar-14", null, Instant.now().plus(Duration.ofHours(24)));
			setStatus(connection, principal, "anahtar-14", 200);
			connection.commit();
		}
		assertThat(jdbc.sql("""
				select response_status from platform.idempotency_keys where principal_key = ? and idem_key = ?""")
			.params(principal, "anahtar-14").query(Integer.class).single()).isEqualTo(200);

		try (Connection connection = transaction()) {
			insertKey(connection, principal, "anahtar-409", null, Instant.now().plus(Duration.ofHours(24)));
			assertThatThrownBy(() -> setStatus(connection, principal, "anahtar-409", 409))
				.isInstanceOf(SQLException.class).hasMessageContaining("ck_idempotency_keys_status");
			connection.rollback();
		}
	}

	/** Şema güvenceleri: anahtar uzunluğu, sır içeren yanıtta gövde yok, pozitif sürüm (DB §8.3, T-IDEM-07). */
	@Test
	void schemaConstraints() throws Exception {
		String principal = UUID.randomUUID().toString();
		try (Connection connection = transaction()) {
			assertThatThrownBy(() -> insertKey(connection, principal, "kisa", 200, Instant.now()))
				.hasMessageContaining("ck_idempotency_keys_key_len");
			connection.rollback();
		}
		try (Connection connection = transaction();
				PreparedStatement insert = connection.prepareStatement("""
						insert into platform.idempotency_keys (principal_key, idem_key, method, route_template,
						       request_hash, hash_key_version, response_status, response_body, body_redacted, expires_at)
						values (?, 'anahtar-redacted', 'POST', '/r', 'h', 1, 201, '{"accessToken":"x"}', true, now())""")) {
			insert.setString(1, principal);
			assertThatThrownBy(insert::executeUpdate).hasMessageContaining("ck_idempotency_keys_redacted");
			connection.rollback();
		}
		try (Connection connection = transaction();
				PreparedStatement insert = connection.prepareStatement("""
						insert into platform.idempotency_keys (principal_key, idem_key, method, route_template,
						       request_hash, hash_key_version, response_status, expires_at)
						values (?, 'anahtar-surum', 'POST', '/r', 'h', 0, 200, now())""")) {
			insert.setString(1, principal);
			assertThatThrownBy(insert::executeUpdate).hasMessageContaining("ck_idempotency_keys_hash_key_version");
			connection.rollback();
		}
	}

	// --- yardımcılar ---

	private Connection transaction() throws SQLException {
		Connection connection = dataSource.getConnection();
		connection.setAutoCommit(false);
		return connection;
	}

	private static void insertKey(Connection connection, String principal, String key, Integer status,
			Instant expiresAt) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("""
				insert into platform.idempotency_keys (principal_key, idem_key, method, route_template, request_hash,
				       hash_key_version, response_status, created_at, expires_at)
				values (?, ?, 'POST', '/r', 'h', 1, ?, ?, ?)""")) {
			insert.setString(1, principal);
			insert.setString(2, key);
			insert.setObject(3, status);
			insert.setTimestamp(4, Timestamp.from(expiresAt.minus(Duration.ofHours(24))));
			insert.setTimestamp(5, Timestamp.from(expiresAt));
			insert.executeUpdate();
		}
	}

	private static void setStatus(Connection connection, String principal, String key, int status) throws SQLException {
		try (PreparedStatement update = connection.prepareStatement("""
				update platform.idempotency_keys set response_status = ? where principal_key = ? and idem_key = ?""")) {
			update.setInt(1, status);
			update.setString(2, principal);
			update.setString(3, key);
			update.executeUpdate();
		}
	}

	private static void insertUser(Connection connection, UUID id) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', 'Tetik Testi', 'Tetik')""")) {
			insert.setObject(1, id);
			insert.setString(2, id + "@example.org");
			insert.executeUpdate();
		}
	}

	private int keyCount(String principal) {
		return jdbc.sql("select count(*) from platform.idempotency_keys where principal_key = ?").param(principal)
			.query(Integer.class).single();
	}

	private int userCount(UUID id) {
		return jdbc.sql("select count(*) from identity.users where id = ?").param(id).query(Integer.class).single();
	}

}
