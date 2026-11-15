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

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.gks.backend.access.WebAccess.WebLogin;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** ID-05 – ID-07: yenileme rotasyonu, tekrar kullanım tespiti, taşıma ayrımı, çıkış. */
class SessionApiTests extends IdentityTestSupport {

	@Nested
	class AndroidRefresh {

		@Test
		void rotatesAndOldTokenReuseRevokesWholeSession() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String login = androidLogin(email);
			String oldRefresh = field(login, "$.refreshToken");

			String rotated = refresh(oldRefresh).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.sessionId").value(field(login, "$.session.sessionId")))
				.andReturn().getResponse().getContentAsString();
			String newAccess = field(rotated, "$.accessToken");
			String newRefresh = field(rotated, "$.refreshToken");
			assertThat(newRefresh).isNotEqualTo(oldRefresh);
			protectedCall(newAccess).andExpect(status().isNotFound());

			// Halef kullanıldı → eski belirteç artık tolerans kapsamında değil: zincir iptal.
			refresh(newRefresh).andExpect(status().isOk());
			expectProblem(refresh(oldRefresh), 401, "AUTH_SESSION_REVOKED");

			expectProblem(protectedCall(newAccess), 401, "AUTH_SESSION_REVOKED");
			assertThat(activeSessionCount(userId)).isZero();
			assertThat(jdbc.sql("select count(*) from identity.refresh_tokens where user_id = ? and revoke_reason = 'REUSE_DETECTED'")
				.param(userId).query(Integer.class).single()).isPositive();
		}

		@Test
		void retryWithinToleranceGetsFreshPairAndUnusedSuccessorDies() throws Exception {
			String email = newEmail();
			register(email);
			String oldRefresh = field(androidLogin(email), "$.refreshToken");
			String lost = field(refresh(oldRefresh).andReturn().getResponse().getContentAsString(), "$.refreshToken");

			// Yanıt istemciye ulaşmadı; aynı belirteçle 10 sn içinde yeniden deneme (LD-18).
			String retried = field(refresh(oldRefresh).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.refreshToken");

			assertThat(retried).isNotEqualTo(lost);
			expectProblem(refresh(lost), 401, "AUTH_SESSION_REVOKED");
		}

		@Test
		void reuseAfterToleranceWindowRevokesSession() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String oldRefresh = field(androidLogin(email), "$.refreshToken");
			refresh(oldRefresh).andExpect(status().isOk());
			jdbc.sql("update identity.refresh_tokens set last_used_at = now() - interval '11 seconds' "
					+ "where user_id = ? and replaced_by is not null").param(userId).update();

			expectProblem(refresh(oldRefresh), 401, "AUTH_SESSION_REVOKED");
			assertThat(activeSessionCount(userId)).isZero();
		}

		@Test
		void unknownOrRevokedTokenIsRejected() throws Exception {
			expectProblem(refresh("bilinmeyen-belirtec-000000000000000000000000"), 401, "AUTH_UNAUTHENTICATED");

			String email = newEmail();
			register(email);
			String login = androidLogin(email);
			logout(login).andExpect(status().isNoContent());
			expectProblem(refresh(field(login, "$.refreshToken")), 401, "AUTH_SESSION_REVOKED");
		}

		@Test
		void disabledAccountCannotRefresh() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String refreshToken = field(androidLogin(email), "$.refreshToken");
			jdbc.sql("update identity.users set status = 'DISABLED' where id = ?").param(userId).update();

			expectProblem(refresh(refreshToken), 403, "ACCOUNT_DISABLED");
		}

		private ResultActions refresh(String token) throws Exception {
			return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\": \"%s\"}".formatted(token)));
		}

	}

	@Nested
	class WebRefresh {

		@Test
		void cookieRefreshRequiresCsrfAndKeepsTokensOutOfBody() throws Exception {
			MockHttpServletResponse login = webLogin();
			Cookie refresh = login.getCookie("__Secure-refresh");

			expectProblem(mvc.perform(post("/api/v1/auth/refresh").cookie(refresh)), 403, "CSRF_TOKEN_INVALID");

			MockHttpServletResponse rotated = webRefresh(refresh).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(jsonPath("$.accessTokenExpiresAt").isNotEmpty())
				.andReturn().getResponse();
			Cookie newRefresh = rotated.getCookie("__Secure-refresh");
			assertThat(newRefresh.getValue()).isNotEqualTo(refresh.getValue());
			// Web: 12 sa mutlak süre korunur, yenileme uzatmaz (LD-19).
			assertThat(newRefresh.getMaxAge()).isLessThanOrEqualTo(refresh.getMaxAge());
			mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).cookie(rotated.getCookie("__Host-access")))
				.andExpect(status().isNotFound());
		}

		@Test
		void absoluteLifetimeEndsSession() throws Exception {
			MockHttpServletResponse login = webLogin();
			jdbc.sql("update identity.refresh_tokens set expires_at = now() - interval '1 second' where token_hash is not null "
					+ "and family_id = ?").param(UUID.fromString(field(login.getContentAsString(), "$.session.sessionId")))
				.update();

			expectProblem(webRefresh(login.getCookie("__Secure-refresh")), 401, "AUTH_SESSION_REVOKED");
		}

		@Test
		void tokensAreBoundToTheirTransport() throws Exception {
			MockHttpServletResponse web = webLogin();
			String webRefresh = web.getCookie("__Secure-refresh").getValue();
			String webAccess = web.getCookie("__Host-access").getValue();

			// Web yenileme belirteci Android gövdesiyle, web erişim belirteci Bearer ile kullanılamaz.
			expectProblem(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\": \"%s\"}".formatted(webRefresh))), 401, "AUTH_UNAUTHENTICATED");
			expectProblem(protectedCall(webAccess), 401, "AUTH_UNAUTHENTICATED");

			// Android yenileme belirteci çerezle kullanılamaz.
			String email = newEmail();
			register(email);
			String androidRefresh = field(androidLogin(email), "$.refreshToken");
			expectProblem(webRefresh(new Cookie("__Secure-refresh", androidRefresh)), 401, "AUTH_UNAUTHENTICATED");
		}

		@Test
		void refreshWithoutAnyTokenIsUnauthenticated() throws Exception {
			expectProblem(mvc.perform(post("/api/v1/auth/refresh")), 401, "AUTH_UNAUTHENTICATED");
		}

	}

	@Nested
	class Logout {

		@Test
		void logoutRevokesOnlyThisSession() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String first = androidLogin(email);
			String second = androidLogin(email);

			logout(first).andExpect(status().isNoContent());

			expectProblem(protectedCall(field(first, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
			protectedCall(field(second, "$.accessToken")).andExpect(status().isNotFound());
			assertThat(activeSessionCount(userId)).isEqualTo(1);
		}

		@Test
		void logoutAllRevokesEverySessionOfUser() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String first = androidLogin(email);
			String second = androidLogin(email);

			mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + field(first, "$.accessToken")))
				.andExpect(status().isNoContent());

			expectProblem(protectedCall(field(first, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
			expectProblem(protectedCall(field(second, "$.accessToken")), 401, "AUTH_SESSION_REVOKED");
			assertThat(activeSessionCount(userId)).isZero();
		}

		@Test
		void webLogoutClearsCookies() throws Exception {
			MockHttpServletResponse login = webLogin();
			Cookie access = login.getCookie("__Host-access");

			MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/logout").cookie(access,
					new Cookie("XSRF-TOKEN", "c"))
				.header("X-CSRF-Token", "c")).andExpect(status().isNoContent()).andReturn().getResponse();

			assertThat(response.getHeaders("Set-Cookie"))
				.anySatisfy(c -> assertThat(c).startsWith("__Host-access=;").contains("Max-Age=0"))
				.anySatisfy(c -> assertThat(c).startsWith("__Secure-refresh=;").contains("Max-Age=0")
					.contains("Path=/api/v1/auth"));
			mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).cookie(access))
				.andExpect(status().isUnauthorized());
		}

		@Test
		void logoutRequiresAuthentication() throws Exception {
			expectProblem(mvc.perform(post("/api/v1/auth/logout")), 401, "AUTH_UNAUTHENTICATED");
		}

	}

	private ResultActions logout(String androidLoginBody) throws Exception {
		return mvc.perform(post("/api/v1/auth/logout")
			.header("Authorization", "Bearer " + field(androidLoginBody, "$.accessToken")));
	}

	/** Web rolü olan, MFA gerektirmeyen (TEK/UYM benzeri) kullanıcı. */
	private MockHttpServletResponse webLogin() throws Exception {
		given(webAccess.webLogin(any())).willReturn(WebLogin.ALLOWED);
		String email = newEmail();
		register(email);
		return login(email, PASSWORD, "WEB").andExpect(status().isOk()).andReturn().getResponse();
	}

	private ResultActions webRefresh(Cookie refresh) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").cookie(refresh, new Cookie("XSRF-TOKEN", "c"))
			.header("X-CSRF-Token", "c"));
	}

}
