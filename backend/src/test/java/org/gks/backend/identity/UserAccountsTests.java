package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiProblems;
import org.gks.backend.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Hesap oluşturma, parola özeti ve identity şeması (DB §3.1, §3.4; ADR-012). */
class UserAccountsTests extends IdentityTestSupport {

	@Autowired
	PasswordEncoder passwordEncoder;

	@Test
	void storesNormalizedEmailAndArgon2idHashOnly() {
		String email = newEmail();
		UUID id = accounts.register(new NewUserAccount("  " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD,
				" Ayşe Yılmaz ", "Ayşe", "+905551112233"));

		Map<String, Object> row = jdbc.sql("select * from identity.users where id = ?").param(id).query().singleRow();
		assertThat(row.get("email")).isEqualTo(email);
		assertThat(row.get("full_name")).isEqualTo("Ayşe Yılmaz");
		assertThat(row.get("status")).isEqualTo("ACTIVE");
		String hash = (String) row.get("password_hash");
		assertThat(hash).startsWith("{argon2}$argon2id$").doesNotContain(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
		assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains(PASSWORD));
	}

	@Test
	void sameEmailInAnotherCaseIsAlreadyRegistered() {
		String email = newEmail();
		register(email);

		assertThatThrownBy(() -> register(email.toUpperCase(java.util.Locale.ROOT)))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ApiProblems.Code.ALREADY_EXISTS);
				assertThat(ex.extensions()).containsEntry("conflict", "EMAIL_REGISTERED");
			});
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			short password       | kisi@example.org | Kisa-123        | Ayşe  | password    | TOO_SHORT
			common password      | kisi@example.org | qwertyuiop      | Ayşe  | password    | NOT_ALLOWED_VALUE
			password has email   | ayse.yilmaz@example.org | Ayse.Yilmaz-2026 | Ayşe | password | NOT_ALLOWED_VALUE
			bad email            | not-an-email     | Deprem-2026-xx  | Ayşe  | email       | INVALID_FORMAT
			display name short   | kisi@example.org | Deprem-2026-xx  | A     | displayName | TOO_SHORT
			display name long    | kisi@example.org | Deprem-2026-xx  | %41   | displayName | TOO_LONG
			""")
	void rejectsInvalidAccount(String caseName, String email, String password, String displayName, String field,
			String code) {
		String name = displayName.equals("%41") ? "a".repeat(41) : displayName;
		assertThatThrownBy(() -> accounts.register(new NewUserAccount(email, password, "Ayşe Yılmaz", name, null)))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.code()).isEqualTo(ApiProblems.Code.VALIDATION_FAILED);
				assertThat(ex.errors()).anySatisfy(error -> {
					assertThat(error.field()).isEqualTo(field);
					assertThat(error.code()).isEqualTo(code);
				});
			});
	}

	@Test
	void rejectsTooLongPasswordBadPhoneAndBlankName() {
		assertThatThrownBy(() -> accounts.register(
				new NewUserAccount(newEmail(), "p".repeat(129), " ", "Ayşe", "05551112233")))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errors())
				.extracting(ApiException.FieldError::field, ApiException.FieldError::code)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("password", "TOO_LONG"),
						org.assertj.core.groups.Tuple.tuple("phone", "INVALID_FORMAT"),
						org.assertj.core.groups.Tuple.tuple("fullName", "REQUIRED")));
	}

	/** bcrypt 72 baytı aşan parolayı keserdi; Argon2id 128 Türkçe karakterin tamamını kullanır. */
	@Test
	void longMultibytePasswordIsUsedInFull() throws Exception {
		String email = newEmail();
		String password = "ğüşıöç".repeat(21) + "Ab";   // 128 karakter, 254 bayt
		accounts.register(new NewUserAccount(email, password, "Ayşe Yılmaz", "Ayşe", null));
		String samePrefix = password.substring(0, 127) + "c";

		login(email, password, "ANDROID").andExpect(status().isOk());
		login(email, samePrefix, "ANDROID").andExpect(status().isUnauthorized());
	}

	@Test
	void generatedIdsAreVersion7() {
		UUID first = Ids.newId();
		UUID second = Ids.newId();

		assertThat(first.version()).isEqualTo(7);
		assertThat(first.variant()).isEqualTo(2);
		assertThat(first).isNotEqualTo(second);
		assertThat(first.getMostSignificantBits() >>> 16).isLessThanOrEqualTo(second.getMostSignificantBits() >>> 16);
	}

	@Test
	void schemaRejectsUppercaseEmailAndInvalidPhone() {
		assertThatThrownBy(() -> insertUser("Kisi@Example.org", null))
			.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_users_email_lower");
		assertThatThrownBy(() -> insertUser(newEmail(), "0555"))
			.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_users_lengths");
	}

	@Test
	/** ADR-014: MFA'lı oturum Android'de de açılabilir ama hiçbir MFA'lı zincir 12 saati aşamaz; web'de cihaz yok. */
	void schemaLimitsMfaSessionsToTwelveHoursAndForbidsDeviceOnWebSessions() {
		UUID userId = register(newEmail());
		UUID deviceId = UUID.randomUUID();
		jdbc.sql("insert into identity.devices (id, user_id, installation_id, fcm_token) values (?, ?, ?, ?)")
			.params(deviceId, userId, "install-" + deviceId, "fcm-" + deviceId).update();

		insertRefreshToken(userId, null, "ANDROID", true, "1 hour");   // yönetim oturumu
		assertThatThrownBy(() -> insertRefreshToken(userId, null, "ANDROID", true, "30 days"))
			.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_refresh_tokens_mfa_absolute");
		assertThatThrownBy(() -> insertRefreshToken(userId, null, "WEB", true, "13 hours"))
			.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_refresh_tokens_mfa_absolute");
		insertRefreshToken(userId, null, "ANDROID", false, "30 days");   // gönüllü oturumu kayan 30 gün
		assertThatThrownBy(() -> insertRefreshToken(userId, deviceId, "WEB", false))
			.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_refresh_tokens_web_no_device");
	}

	private void insertUser(String email, String phone) {
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name, phone)
				values (?, ?, '{argon2}x', 'Ayşe Yılmaz', 'Ayşe', ?)""")
			.params(UUID.randomUUID(), email, phone).update();
	}

	private void insertRefreshToken(UUID userId, UUID deviceId, String client, boolean mfa) {
		insertRefreshToken(userId, deviceId, client, mfa, "1 hour");
	}

	private void insertRefreshToken(UUID userId, UUID deviceId, String client, boolean mfa, String lifetime) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.refresh_tokens (id, user_id, device_id, family_id, client, token_hash, mfa_verified, expires_at)
				values (?, ?, ?, ?, ?, ?, ?, now() + cast(? as interval))""")
			.params(id, userId, deviceId, id, client, "hash-" + id, mfa, lifetime).update();
	}

}
