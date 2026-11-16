package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.TestSessions;
import org.gks.backend.access.WebAccess.WebLogin;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * ADR-020: kimlik olayları denetimde — başarılı/başarısız giriş (IP ve istemci türüyle), çıkış, MFA kurulumu ve TOTP
 * hatası, yenileme belirtecinin tekrar kullanımı (zincir iptali). Parola, belirteç, bilet, TOTP kodu ve e-posta
 * kayda girmez.
 */
class IdentityAuditTests extends IdentityTestSupport {

	@Test
	void loginOutcomesAreAuditedWithIpAndClientWithoutSecrets() throws Exception {
		String email = newEmail();
		UUID userId = register(email);

		login(email, "Yanlis-Parola-2026", "WEB").andExpect(status().isUnauthorized());
		login("yok-" + email, PASSWORD, "WEB").andExpect(status().isUnauthorized());
		String session = androidLogin(email);

		Map<String, Object> failed = entry("LOGIN_FAILED", userId);
		assertThat(failed).containsEntry("client", "WEB").containsEntry("ip", "127.0.0.1").containsEntry("basis", "SELF");
		assertThat((String) failed.get("details")).contains("AUTH_INVALID_CREDENTIALS").contains("\"accountKnown\": true");
		String unknown = jdbc.sql("select details::text from audit.audit_entries where action = 'LOGIN_FAILED' and actor_id is null "
				+ "order by id desc limit 1").query(String.class).single();
		assertThat(unknown).contains("\"accountKnown\": false");

		Map<String, Object> ok = entry("LOGIN_SUCCEEDED", userId);
		assertThat(ok).containsEntry("client", "ANDROID").containsEntry("ip", "127.0.0.1");
		assertThat(ok.get("resource_id").toString()).isEqualTo(field(session, "$.session.sessionId"));

		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + field(session, "$.accessToken")))
			.andExpect(status().is2xxSuccessful());
		assertThat(entry("LOGOUT", userId)).containsEntry("client", "ANDROID");

		String all = jdbc.sql("select string_agg(details::text || coalesce(resource_type, ''), ' ') from audit.audit_entries "
				+ "where actor_id = ? or (actor_id is null and action = 'LOGIN_FAILED')").param(userId).query(String.class).single();
		assertThat(all).doesNotContain(PASSWORD).doesNotContain("Yanlis-Parola").doesNotContain(email)
			.doesNotContain(field(session, "$.refreshToken")).doesNotContain(field(session, "$.accessToken"));
	}

	@Test
	void mfaEnrollmentAndWrongCodeAreAudited() throws Exception {
		given(webAccess.webLogin(any())).willReturn(WebLogin.MFA_REQUIRED);
		String email = newEmail();
		UUID userId = register(email);
		String ticket = field(login(email, PASSWORD, "WEB").andReturn().getResponse().getContentAsString(), "$.mfaTicket");
		String base32 = field(mvc.perform(post("/api/v1/auth/mfa/enroll").contentType(MediaType.APPLICATION_JSON)
			.content("{\"mfaTicket\": \"%s\"}".formatted(ticket))).andReturn().getResponse().getContentAsString(), "$.secret");

		String wrong = MfaApiTests.code(base32, 0).equals("000000") ? "111111" : "000000";
		verify(ticket, wrong).andExpect(status().isBadRequest());
		Map<String, Object> failed = entry("LOGIN_FAILED", userId);
		assertThat((String) failed.get("details")).contains("\"stage\": \"MFA\"").doesNotContain(wrong);

		verify(ticket, MfaApiTests.code(base32, 0)).andExpect(status().isOk());
		assertThat(entry("MFA_ENROLLED", userId)).containsEntry("client", "WEB");
		assertThat((String) entry("LOGIN_SUCCEEDED", userId).get("details")).contains("\"mfa\": true");
		String all = jdbc.sql("select string_agg(details::text, ' ') from audit.audit_entries where actor_id = ?").param(userId)
			.query(String.class).single();
		assertThat(all).doesNotContain(base32).doesNotContain(ticket);
	}

	@Test
	void refreshTokenReuseRevokingTheChainIsAudited() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String login = androidLogin(email);
		String oldRefresh = field(login, "$.refreshToken");
		String newRefresh = field(refresh(oldRefresh).andReturn().getResponse().getContentAsString(), "$.refreshToken");
		refresh(newRefresh).andExpect(status().isOk());
		refresh(oldRefresh).andExpect(status().isUnauthorized());

		Map<String, Object> reuse = entry("REFRESH_TOKEN_REUSED", userId);
		assertThat(reuse).containsEntry("basis", "SYSTEM").containsEntry("client", "ANDROID");
		assertThat(reuse.get("resource_id").toString()).isEqualTo(field(login, "$.session.sessionId"));
		assertThat((String) reuse.get("details")).doesNotContain(oldRefresh);

		List<String> mine = jdbc.sql("select action from audit.audit_entries where actor_id = ?").param(userId)
			.query(String.class).list();
		assertThat(mine).contains("LOGIN_SUCCEEDED", "REFRESH_TOKEN_REUSED");
		assertThat(new TestSessions(jdbc, jwtEncoder)).isNotNull();
	}

	private Map<String, Object> entry(String action, UUID userId) {
		return jdbc.sql("""
				select actor_basis as basis, client, host(ip) as ip, resource_id, details::text as details
				  from audit.audit_entries where action = ? and actor_id = ? order by id desc limit 1""")
			.params(action, userId).query().singleRow();
	}

	private org.springframework.test.web.servlet.ResultActions verify(String ticket, String code) throws Exception {
		return mvc.perform(post("/api/v1/auth/mfa").contentType(MediaType.APPLICATION_JSON)
			.content("{\"mfaTicket\": \"%s\", \"code\": \"%s\"}".formatted(ticket, code)));
	}

	private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\": \"%s\"}".formatted(token)));
	}

}
