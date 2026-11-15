package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.identity.LoginApiTests.expectProblem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.gks.backend.access.WebAccess.WebLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ID-02 – ID-04, K-19: ADM/KOR web oturumu TOTP doğrulanmadan açılmaz. Bilet tek kullanımlık ve 5 dk'lıktır; yanlış
 * kod sayılır; aynı kod tekrar kullanılamaz; TOTP anahtarı şifreli saklanır.
 */
class MfaApiTests extends IdentityTestSupport {

	@BeforeEach
	void mfaRequired() {
		given(webAccess.webLogin(any())).willReturn(WebLogin.MFA_REQUIRED);
	}

	@Test
	void webLoginReturnsOnlyTicketNoSessionNoCookies() throws Exception {
		String email = newEmail();
		UUID userId = register(email);

		MockHttpServletResponse response = login(email, PASSWORD, "WEB")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.mfaRequired").value(true))
			.andExpect(jsonPath("$.mfaEnrollmentRequired").value(true))
			.andExpect(jsonPath("$.mfaTicket").isNotEmpty())
			.andExpect(jsonPath("$.session").doesNotExist())
			.andExpect(jsonPath("$.accessToken").doesNotExist())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andReturn().getResponse();

		assertThat(response.getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("__Host-access")
				|| c.startsWith("__Secure-refresh"));
		assertThat(sessionRows(userId)).isZero();
		// Bilet bir erişim belirteci değildir.
		String ticket = field(response.getContentAsString(), "$.mfaTicket");
		expectProblem(protectedCall(ticket), 401, "AUTH_UNAUTHENTICATED");
		// Ham bilet saklanmaz; yalnızca özeti.
		assertThat(jdbc.sql("select count(*) from identity.mfa_tickets where token_hash = ?").param(ticket)
			.query(Integer.class).single()).isZero();
	}

	@Test
	void enrollThenFirstCorrectCodeOpensMfaVerifiedWebSession() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String ticket = ticket(email);

		String secret = enroll(ticket).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.otpauthUri").value(org.hamcrest.Matchers.startsWith("otpauth://totp/")))
			.andReturn().getResponse().getContentAsString();
		String base32 = field(secret, "$.secret");
		String stored = jdbc.sql("select totp_secret from identity.users where id = ?").param(userId)
			.query(String.class).single();
		assertThat(stored).startsWith("v1:").doesNotContain(base32);

		MockHttpServletResponse response = verify(ticket, code(base32, 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mfaRequired").value(false))
			.andExpect(jsonPath("$.session.mfaVerified").value(true))
			.andExpect(jsonPath("$.session.clientType").value("WEB"))
			.andExpect(jsonPath("$.accessToken").doesNotExist())
			.andReturn().getResponse();
		assertThat(response.getCookie("__Host-access")).isNotNull();
		assertThat(response.getCookie("__Secure-refresh")).isNotNull();
		assertThat(jdbc.sql("select mfa_verified from identity.refresh_tokens where user_id = ?").param(userId)
			.query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("select totp_enrolled_at is not null from identity.users where id = ?").param(userId)
			.query(Boolean.class).single()).isTrue();

		// Çerezle gelen oturum geçerli; erişim kontrolü organization'a ulaşır (404).
		mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).cookie(response.getCookie("__Host-access")))
			.andExpect(status().isNotFound());

		// Kurulu kullanıcı yeni bir anahtar alamaz (anahtar değiştirilemez).
		String next = ticket(email);
		assertThat(field(loginBody(email), "$.mfaEnrollmentRequired")).isEqualTo("false");
		expectProblem(enroll(next), 409, "INVALID_STATE_TRANSITION");
		assertThat(jdbc.sql("select totp_secret from identity.users where id = ?").param(userId)
			.query(String.class).single()).isEqualTo(stored);
	}

	@Test
	void ticketIsSingleUse() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String ticket = ticket(email);
		String base32 = field(enroll(ticket).andReturn().getResponse().getContentAsString(), "$.secret");
		verify(ticket, code(base32, 0)).andExpect(status().isOk());

		expectProblem(verify(ticket, code(base32, 1)), 401, "AUTH_MFA_REQUIRED");
		assertThat(sessionRows(userId)).isEqualTo(1);
	}

	@Test
	void expiredTicketIsRejected() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String ticket = ticket(email);
		String base32 = field(enroll(ticket).andReturn().getResponse().getContentAsString(), "$.secret");
		jdbc.sql("update identity.mfa_tickets set created_at = now() - interval '6 minutes', "
				+ "expires_at = now() - interval '1 minute' where user_id = ?").param(userId).update();

		expectProblem(verify(ticket, code(base32, 0)), 401, "AUTH_MFA_REQUIRED");
		expectProblem(enroll(ticket), 401, "AUTH_MFA_REQUIRED");
		assertThat(sessionRows(userId)).isZero();
	}

	@Test
	void verifyWithoutTicketOrWithForgedTicketOpensNoSession() throws Exception {
		expectProblem(mvc.perform(post("/api/v1/auth/mfa").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"123456\"}")), 401, "AUTH_MFA_REQUIRED");
		expectProblem(verify("uydurma-bilet-degeri-0000000000000000000000", "123456"), 401, "AUTH_MFA_REQUIRED");
	}

	@Test
	void verifyBeforeEnrollmentIsRejected() throws Exception {
		String email = newEmail();
		UUID userId = register(email);

		expectProblem(verify(ticket(email), "123456"), 409, "INVALID_STATE_TRANSITION");
		assertThat(sessionRows(userId)).isZero();
	}

	@Test
	void wrongCodesAreCountedAndFifthLocksAccount() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String ticket = ticket(email);
		String base32 = field(enroll(ticket).andReturn().getResponse().getContentAsString(), "$.secret");
		String wrong = code(base32, -10);   // pencere dışı adım

		for (int i = 0; i < 4; i++) {
			expectProblem(verify(ticket, wrong), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("code"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_CODE"));
		}
		expectProblem(verify(ticket, wrong), 423, "ACCOUNT_LOCKED");

		// Kilit sonrası doğru kod da ve yeni giriş de reddedilir; oturum açılmamıştır.
		expectProblem(verify(ticket, code(base32, 0)), 401, "AUTH_MFA_REQUIRED");
		expectProblem(login(email, PASSWORD, "WEB"), 423, "ACCOUNT_LOCKED");
		assertThat(sessionRows(userId)).isZero();
	}

	@Test
	void malformedCodeIsRejectedWithoutCounting() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String ticket = ticket(email);
		enroll(ticket);

		expectProblem(verify(ticket, "12ab"), 400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
		assertThat(jdbc.sql("select attempts from identity.mfa_tickets where user_id = ?").param(userId)
			.query(Integer.class).single()).isZero();
	}

	@Test
	void acceptedCodeCannotBeReplayedInNextLogin() throws Exception {
		String email = newEmail();
		UUID userId = register(email);
		String first = ticket(email);
		String base32 = field(enroll(first).andReturn().getResponse().getContentAsString(), "$.secret");
		String code = code(base32, 0);
		verify(first, code).andExpect(status().isOk());

		String second = ticket(email);
		expectProblem(verify(second, code), 400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.errors[0].code").value("INVALID_CODE"));
		assertThat(sessionRows(userId)).isEqualTo(1);

		// Sonraki adımın kodu (pencere içinde) kabul edilir.
		verify(second, code(base32, 1)).andExpect(status().isOk());
		assertThat(sessionRows(userId)).isEqualTo(2);
	}

	@Test
	void androidLoginOfSameUserIsNeverMfaVerified() throws Exception {
		String email = newEmail();
		register(email);

		String body = androidLogin(email);
		assertThat(field(body, "$.mfaRequired")).isEqualTo("false");
		assertThat(field(body, "$.session.mfaVerified")).isEqualTo("false");
	}

	private String loginBody(String email) throws Exception {
		return login(email, PASSWORD, "WEB").andReturn().getResponse().getContentAsString();
	}

	private String ticket(String email) throws Exception {
		return field(loginBody(email), "$.mfaTicket");
	}

	private ResultActions enroll(String ticket) throws Exception {
		return mvc.perform(post("/api/v1/auth/mfa/enroll").contentType(MediaType.APPLICATION_JSON)
			.content("{\"mfaTicket\": \"%s\"}".formatted(ticket)));
	}

	private ResultActions verify(String ticket, String code) throws Exception {
		return mvc.perform(post("/api/v1/auth/mfa").contentType(MediaType.APPLICATION_JSON)
			.content("{\"mfaTicket\": \"%s\", \"code\": \"%s\"}".formatted(ticket, code)));
	}

	/** Şimdiki adıma göre {@code offset} adım sonraki kod (doğrulayıcı ±1 adım kabul eder). */
	static String code(String base32, int offset) {
		long step = Instant.now().getEpochSecond() / Totp.STEP_SECONDS + offset;
		return Totp.code(Totp.fromBase32(base32), step);
	}

	private int sessionRows(UUID userId) {
		List<Integer> rows = jdbc.sql("select count(*) from identity.refresh_tokens where user_id = ?").param(userId)
			.query(Integer.class).list();
		return rows.getFirst();
	}

}
