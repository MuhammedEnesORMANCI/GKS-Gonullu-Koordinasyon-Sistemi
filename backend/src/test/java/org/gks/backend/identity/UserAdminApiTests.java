package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ID-19 – ID-25 gerçek rol atamalarıyla (access taklit edilmez): kapsam ve izdüşüm (§3.8, AC-06.5), hesap kapatma,
 * oturum iptali, parola ve MFA sıfırlama. Yalnızca e-posta gönderici taklit edilir (geçici parolayı okumak için).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserAdminApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
	static final String PASSWORD = "Deprem-Tatbikati-2026";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	PasswordEncoder passwordEncoder;

	@MockitoBean
	UserAdminMailer mailer;

	TestSessions sessions;
	UUID admin;
	UUID volunteer;
	UUID operationId;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = register("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		volunteer = register("Gönüllü");
		operationId = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', 'ACTIVE', now(), ?)""").params(operationId, ORGANIZATION_ID, admin).update();
		role(volunteer, "GON", "OPERATION", operationId);
	}

	@Test
	void directoryProjectionFollowsRole() throws Exception {
		UUID tek = register("Teknik");
		role(tek, "TEK", "ORGANIZATION", ORGANIZATION_ID);
		UUID kor = register("Koordinatör");
		role(kor, "KOR", "OPERATION", operationId);

		// ADM: tam izdüşüm; durum süzgeci ve toplam
		users(sessions.web(admin, true), "?status=ACTIVE&includeTotal=true&operationId=" + operationId).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalCount").value(1))
			.andExpect(jsonPath("$.items[?(@.id == '%s')].email".formatted(volunteer)).isNotEmpty());
		mvc.perform(get("/api/v1/users/{id}", volunteer).with(authenticate(sessions.web(admin, true))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").isNotEmpty())
			.andExpect(jsonPath("$.roles[0].role").value("GON"))
			.andExpect(jsonPath("$.mfaEnrolled").value(false));

		// TEK: yalnızca ad ve durum (AC-06.5)
		users(sessions.web(tek, false), "?operationId=" + operationId).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].displayName").isNotEmpty())
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$.items[0].email").doesNotExist())
			.andExpect(jsonPath("$.items[0].fullName").doesNotExist());
		mvc.perform(get("/api/v1/users/{id}", volunteer).with(authenticate(sessions.web(tek, false))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.roles").doesNotExist());

		// KOR: kurum dizini yok; kendi operasyonunun katılımcıları tam izdüşümle
		users(sessions.web(kor, true), "").andExpect(status().isForbidden());
		users(sessions.web(kor, true), "?operationId=" + operationId).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].email").isNotEmpty());
		users(sessions.web(kor, true), "?operationId=" + UUID.randomUUID()).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/users/{id}", volunteer).with(authenticate(sessions.web(kor, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.roles[0].role").value("GON"))
			.andExpect(jsonPath("$.mfaEnrolled").doesNotExist());
		mvc.perform(get("/api/v1/users/{id}", admin).with(authenticate(sessions.web(kor, true)))).andExpect(status().isForbidden());

		// Gönüllü ve MFA'sız oturumlar
		users(sessions.android(volunteer), "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
		users(sessions.web(admin, false), "").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
		users(sessions.web(admin, true), "?status=GONE").andExpect(status().isBadRequest());
	}

	@Test
	void disableRevokesSessionsAndEnableRestores() throws Exception {
		String volunteerSession = sessions.android(volunteer);
		String adminToken = sessions.web(admin, true);
		action(adminToken, volunteer, "disable", "{\"reason\": \"Test gerekçesi\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED"));
		assertThat(liveSessions(volunteer)).isZero();
		mvc.perform(get("/api/v1/me").with(authenticate(volunteerSession))).andExpect(status().isUnauthorized());
		action(adminToken, volunteer, "disable", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

		action(adminToken, volunteer, "enable", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		action(adminToken, volunteer, "enable", null).andExpect(status().isConflict());

		// Kendi hesabı kapatılamaz (S-09); gerekçe sınırı
		action(adminToken, admin, "disable", null).andExpect(status().isForbidden());
		action(adminToken, volunteer, "disable", "{\"reason\": \"%s\"}".formatted("x".repeat(301))).andExpect(status().isBadRequest());
		action(adminToken, UUID.randomUUID(), "disable", null).andExpect(status().isNotFound());
	}

	@Test
	void supportCanDisableAndRevokeButNotAdminsOrResets() throws Exception {
		UUID tek = register("Teknik");
		role(tek, "TEK", "ORGANIZATION", ORGANIZATION_ID);
		String tekToken = sessions.web(tek, false);
		sessions.android(volunteer);

		action(tekToken, volunteer, "revoke-sessions", null).andExpect(status().isNoContent());
		assertThat(liveSessions(volunteer)).isZero();
		action(tekToken, volunteer, "disable", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.email").doesNotExist());
		action(tekToken, volunteer, "enable", null).andExpect(status().isForbidden());           // ID-22 yalnızca ADM
		action(tekToken, admin, "disable", null).andExpect(status().isForbidden());              // ADM hesabı TEK'e kapalı
		action(tekToken, volunteer, "reset-password", null).andExpect(status().isForbidden());
		action(tekToken, volunteer, "reset-mfa", null).andExpect(status().isForbidden());
		// Gönüllü ve KOR hiçbir hesap işlemi yapamaz
		UUID kor = register("Koordinatör");
		role(kor, "KOR", "OPERATION", operationId);
		action(sessions.web(kor, true), volunteer, "revoke-sessions", null).andExpect(status().isForbidden());
	}

	@Test
	void passwordResetMailsTemporaryPasswordAndForcesChange() throws Exception {
		sessions.android(volunteer);
		action(sessions.web(admin, true), volunteer, "reset-password", null).andExpect(status().isNoContent());

		ArgumentCaptor<String> temporary = ArgumentCaptor.forClass(String.class);
		verify(mailer).sendTemporaryPassword(eq(email(volunteer)), anyString(), temporary.capture());
		String hash = jdbc.sql("select password_hash from identity.users where id = ?").param(volunteer).query(String.class).single();
		assertThat(passwordEncoder.matches(temporary.getValue(), hash)).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isFalse();
		assertThat(jdbc.sql("select must_change_password from identity.users where id = ?").param(volunteer)
			.query(Boolean.class).single()).isTrue();
		assertThat(liveSessions(volunteer)).isZero();
		assertThat(temporary.getValue()).hasSize(14).doesNotContain("0", "O", "1", "l", "I");

		action(sessions.web(admin, true), admin, "reset-password", null).andExpect(status().isForbidden());
		verify(mailer, never()).sendTemporaryPassword(eq(email(admin)), anyString(), anyString());
	}

	@Test
	void mfaResetClearsTotpAndSessions() throws Exception {
		UUID kor = register("Koordinatör");
		role(kor, "KOR", "OPERATION", operationId);
		jdbc.sql("update identity.users set totp_secret = 'sifreli', totp_enrolled_at = now() where id = ?").param(kor).update();
		sessions.web(kor, true);

		action(sessions.web(admin, true), kor, "reset-mfa", null).andExpect(status().isNoContent());
		assertThat(jdbc.sql("select totp_secret is null and totp_enrolled_at is null from identity.users where id = ?").param(kor)
			.query(Boolean.class).single()).isTrue();
		assertThat(liveSessions(kor)).isZero();
		action(sessions.web(admin, true), kor, "reset-mfa", null).andExpect(status().isConflict());   // TOTP yok
		action(sessions.web(admin, true), admin, "reset-mfa", null).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- yardımcılar

	ResultActions users(String token, String query) throws Exception {
		return mvc.perform(get("/api/v1/users" + query).with(authenticate(token)));
	}

	ResultActions action(String token, UUID userId, String name, String body) throws Exception {
		var request = post("/api/v1/users/{id}/" + name, userId).with(authenticate(token));
		if (body != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("yonetim-" + UUID.randomUUID() + "@example.org", PASSWORD, name + " Kişi",
				name, null));
	}

	String email(UUID userId) {
		return jdbc.sql("select email from identity.users where id = ?").param(userId).query(String.class).single();
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

	int liveSessions(UUID userId) {
		return jdbc.sql("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is null")
			.param(userId).query(Integer.class).single();
	}

}
