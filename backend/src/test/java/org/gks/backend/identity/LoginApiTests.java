package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

import org.gks.backend.access.WebAccess.WebLogin;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

/** ID-01, ID-02 (backend-api-design.md §2.2, §2.3; AC-03.1, AC-03.2; LD-03; T-AUTH-04, T-AUTH-06). */
class LoginApiTests extends IdentityTestSupport {

	@Nested
	class AndroidLogin {

		@Test
		void returnsTokensInBodyAndStoresOnlyRefreshTokenHash() throws Exception {
			String email = newEmail();
			UUID userId = register(email);

			MockHttpServletResponse response = login(email, PASSWORD, "ANDROID")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.mfaRequired").value(false))
				.andExpect(jsonPath("$.mustChangePassword").value(false))
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").value(matchesPattern("[A-Za-z0-9_-]{43}")))
				.andExpect(jsonPath("$.session.clientType").value("ANDROID"))
				.andExpect(jsonPath("$.session.mfaVerified").value(false))
				.andReturn().getResponse();
			String body = response.getContentAsString();
			String refresh = field(body, "$.refreshToken");
			UUID sessionId = UUID.fromString(field(body, "$.session.sessionId"));

			Map<String, Object> row = jdbc.sql("select * from identity.refresh_tokens where user_id = ?")
				.param(userId).query().singleRow();
			assertThat(row.get("token_hash")).isEqualTo(RefreshTokens.hash(refresh));
			assertThat(row.values()).noneMatch(value -> String.valueOf(value).contains(refresh));
			assertThat(row.get("id")).isEqualTo(sessionId);
			assertThat(row.get("family_id")).isEqualTo(sessionId);
			assertThat(row.get("client")).isEqualTo("ANDROID");
			assertThat(row.get("device_id")).isNull();
			assertThat(Duration.between(Instant.now(), Instant.parse(field(body, "$.session.expiresAt"))))
				.isBetween(Duration.ofDays(30).minusMinutes(1), Duration.ofDays(30));
			assertThat(Duration.between(Instant.now(), Instant.parse(field(body, "$.accessTokenExpiresAt"))))
				.isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));
			assertThat(response.getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith("__Host-access")
					|| c.startsWith("__Secure-refresh"));
		}

		@Test
		void accessTokenAuthenticatesFollowingRequests() throws Exception {
			String email = newEmail();
			register(email);
			String token = field(androidLogin(email), "$.accessToken");

			protectedCall(token).andExpect(status().isNotFound());   // kimlik geçti; operasyon yok
		}

		@Test
		void linksRegisteredDeviceOfInstallation() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			UUID deviceId = UUID.randomUUID();
			jdbc.sql("insert into identity.devices (id, user_id, installation_id, fcm_token) values (?, ?, ?, ?)")
				.params(deviceId, userId, INSTALLATION, "fcm-" + deviceId)
				.update();

			login(email, PASSWORD, "ANDROID").andExpect(status().isOk());

			assertThat(jdbc.sql("select device_id from identity.refresh_tokens where user_id = ?").param(userId)
				.query(UUID.class).single()).isEqualTo(deviceId);
		}

		@Test
		void emailIsMatchedCaseInsensitively() throws Exception {
			String email = newEmail();
			register(email);

			login("  " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD, "ANDROID").andExpect(status().isOk());
		}

		/** LD-42: {@code /auth/*} idempotency kullanmaz; aynı anahtarla iki giriş iki ayrı oturumdur, yanıt saklanmaz. */
		@Test
		void idempotencyKeyIsIgnoredAndNothingIsStored() throws Exception {
			String email = newEmail();
			register(email);
			String key = UUID.randomUUID().toString();
			String body = "{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"ANDROID\", \"installationId\": \"%s\"}"
				.formatted(email, PASSWORD, INSTALLATION);

			String first = mvc.perform(post("/api/v1/auth/login").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
			String second = mvc.perform(post("/api/v1/auth/login").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();

			assertThat(field(first, "$.session.sessionId")).isNotEqualTo(field(second, "$.session.sessionId"));
			assertThat(field(first, "$.refreshToken")).isNotEqualTo(field(second, "$.refreshToken"));
			assertThat(jdbc.sql("""
					select count(*) from platform.idempotency_keys
					 where principal_key = (select id::text from identity.users where email = ?)
					    or route_template like '%/auth/%'""").param(email).query(Integer.class).single())
				.isZero();
		}

	}

	@Nested
	class WebLoginTests {

		@Test
		void setsHardenedCookiesAndKeepsTokensOutOfBody() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			given(webAccess.webLogin(userId)).willReturn(WebLogin.ALLOWED);

			MockHttpServletResponse response = webLogin(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andExpect(jsonPath("$.session.clientType").value("WEB"))
				.andExpect(jsonPath("$.session.mfaVerified").value(false))
				.andReturn().getResponse();

			List<String> cookies = response.getHeaders("Set-Cookie");
			assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("__Host-access=")
				.contains("Path=/;").contains("Secure").contains("HttpOnly").contains("SameSite=Strict")
				.contains("Max-Age=43200").doesNotContain("Domain"));   // oturum boyu; belirteç ömrü JWT exp'te
			assertThat(cookies).anySatisfy(c -> assertThat(c).startsWith("__Secure-refresh=")
				.contains("Path=/api/v1/auth").contains("Secure").contains("HttpOnly").contains("SameSite=Strict")
				.contains("Max-Age=43200"));
			String body = response.getContentAsString();
			assertThat(Duration.between(Instant.now(), Instant.parse(field(body, "$.session.expiresAt"))))
				.isBetween(Duration.ofHours(12).minusMinutes(1), Duration.ofHours(12));
			assertThat(jdbc.sql("select client from identity.refresh_tokens where user_id = ?").param(userId)
				.query(String.class).single()).isEqualTo("WEB");

			// Web belirteci yalnızca çerezle kabul edilir (GET: CSRF gerekmez).
			Cookie access = response.getCookie("__Host-access");
			mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).cookie(access))
				.andExpect(status().isNotFound());
		}

		/** MFA gereken (ADM/KOR) web girişi {@link MfaApiTests} içinde doğrulanır. */
		@Test
		void withoutWebRoleNoSessionIsOpened() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			given(webAccess.webLogin(userId)).willReturn(WebLogin.NOT_ALLOWED);

			expectProblem(webLogin(email, PASSWORD), 403, "CLIENT_NOT_ALLOWED");
			assertThat(sessionCount(userId)).isZero();
		}

		@Test
		void loginWithCookiesRequiresCsrfToken() throws Exception {
			String email = newEmail();
			given(webAccess.webLogin(any())).willReturn(WebLogin.ALLOWED);
			register(email);

			expectProblem(mvc.perform(post("/api/v1/auth/login").cookie(new Cookie("XSRF-TOKEN", "abc"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"WEB\"}".formatted(email, PASSWORD))),
					403, "CSRF_TOKEN_INVALID");
		}

		@Test
		void csrfEndpointIssuesTokenAndCookie() throws Exception {
			MockHttpServletResponse response = mvc.perform(get("/api/v1/auth/csrf"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.headerName").value("X-CSRF-Token"))
				.andReturn().getResponse();

			assertThat(response.getCookie("XSRF-TOKEN")).isNotNull();
			assertThat(response.getCookie("XSRF-TOKEN").getValue())
				.isEqualTo(field(response.getContentAsString(), "$.token"));
		}

		private ResultActions webLogin(String email, String password) throws Exception {
			MockHttpServletResponse csrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
			String token = field(csrf.getContentAsString(), "$.token");
			return mvc.perform(post("/api/v1/auth/login")
				.cookie(csrf.getCookie("XSRF-TOKEN"))
				.header("X-CSRF-Token", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"WEB\"}".formatted(email, password)));
		}

	}

	@Nested
	class Failures {

		@Test
		void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
			String email = newEmail();
			UUID userId = register(email);

			String wrong = expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 401, "AUTH_INVALID_CREDENTIALS")
				.andReturn().getResponse().getContentAsString();
			String unknown = expectProblem(login(newEmail(), "Yanlis-Parola-123", "ANDROID"), 401,
					"AUTH_INVALID_CREDENTIALS").andReturn().getResponse().getContentAsString();

			assertThat(wrong.replaceAll("\"traceId\":\"[0-9a-f]+\"", ""))
				.isEqualTo(unknown.replaceAll("\"traceId\":\"[0-9a-f]+\"", ""));
			assertThat(failedCount(userId)).isEqualTo(1);
			assertThat(sessionCount(userId)).isZero();
		}

		@Test
		void fifthFailureLocksForFifteenMinutesEvenForCorrectPassword() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			for (int i = 0; i < 4; i++) {
				expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 401, "AUTH_INVALID_CREDENTIALS");
			}

			MockHttpServletResponse locked = expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 423,
					"ACCOUNT_LOCKED").andExpect(jsonPath("$.lockedUntil").isNotEmpty()).andReturn().getResponse();
			assertThat(Long.parseLong(locked.getHeader("Retry-After"))).isBetween(890L, 901L);
			assertThat(Duration.between(Instant.now(), Instant.parse(field(locked.getContentAsString(), "$.lockedUntil"))))
				.isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));

			expectProblem(login(email, PASSWORD, "ANDROID"), 423, "ACCOUNT_LOCKED");
			assertThat(sessionCount(userId)).isZero();
		}

		@Test
		void afterLockExpiresCorrectPasswordWorksAndResetsCounter() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			for (int i = 0; i < 5; i++) {
				login(email, "Yanlis-Parola-123", "ANDROID");
			}
			jdbc.sql("update identity.users set locked_until = now() - interval '1 second' where id = ?").param(userId)
				.update();

			login(email, PASSWORD, "ANDROID").andExpect(status().isOk());
			assertThat(failedCount(userId)).isZero();
		}

		@Test
		void afterLockExpiresCountingStartsAgain() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			for (int i = 0; i < 5; i++) {
				login(email, "Yanlis-Parola-123", "ANDROID");
			}
			jdbc.sql("update identity.users set locked_until = now() - interval '1 second' where id = ?").param(userId)
				.update();

			expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 401, "AUTH_INVALID_CREDENTIALS");
			assertThat(failedCount(userId)).isEqualTo(1);
		}

		/** T-AUTH-04: kayıtlı olmayan e-postada da 5. hatada aynı kilit yanıtı. */
		@Test
		void unknownEmailIsLockedTheSameWay() throws Exception {
			String email = newEmail();
			for (int i = 0; i < 4; i++) {
				expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 401, "AUTH_INVALID_CREDENTIALS");
			}
			MockHttpServletResponse locked = expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 423,
					"ACCOUNT_LOCKED").andReturn().getResponse();
			assertThat(locked.getHeader("Retry-After")).isNotNull();

			expectProblem(login(email, PASSWORD, "ANDROID"), 423, "ACCOUNT_LOCKED");
		}

		@Test
		void disabledAccountIsReportedOnlyWithCorrectPassword() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			jdbc.sql("update identity.users set status = 'DISABLED', disabled_at = now() where id = ?").param(userId)
				.update();

			expectProblem(login(email, "Yanlis-Parola-123", "ANDROID"), 401, "AUTH_INVALID_CREDENTIALS");
			expectProblem(login(email, PASSWORD, "ANDROID"), 403, "ACCOUNT_DISABLED");
			assertThat(sessionCount(userId)).isZero();
		}

		/** DB E-16: sayaç atomik artar, eşzamanlı denemeler sayım kaybetmez. */
		@Test
		void concurrentFailuresAreAllCounted() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			List<Callable<Integer>> attempts = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				attempts.add(() -> login(email, "Yanlis-Parola-123", "ANDROID").andReturn().getResponse().getStatus());
			}
			try (var executor = Executors.newFixedThreadPool(4)) {
				for (Future<Integer> result : executor.invokeAll(attempts)) {
					assertThat(result.get()).isEqualTo(401);
				}
			}
			assertThat(failedCount(userId)).isEqualTo(4);
		}

		@ParameterizedTest(name = "{0}")
		@CsvSource(delimiter = '|', textBlock = """
				missing client type      | {"email": "a@example.org", "password": "x"}                                          | clientType     | REQUIRED
				android without install  | {"email": "a@example.org", "password": "x", "clientType": "ANDROID"}                 | installationId | REQUIRED
				bad installation id      | {"email": "a@example.org", "password": "x", "clientType": "ANDROID", "installationId": "kısa"} | installationId | INVALID_FORMAT
				unknown client type      | {"email": "a@example.org", "password": "x", "clientType": "IOS"}                     | clientType     | NOT_ALLOWED_VALUE
				unknown field            | {"email": "a@example.org", "password": "x", "clientType": "WEB", "remember": true}   | remember       | UNKNOWN_FIELD
				missing password         | {"email": "a@example.org", "clientType": "WEB"}                                      | password       | REQUIRED
				""")
		void rejectsInvalidRequests(String caseName, String body, String field, String code) throws Exception {
			expectProblem(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)),
					400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[?(@.field == '%s')].code".formatted(field)).value(code));
		}

		@Test
		void tooLongPasswordIsRejectedBeforeLookup() throws Exception {
			expectProblem(login(newEmail(), "p".repeat(129), "ANDROID"), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("password"))
				.andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
		}

	}

	/** Parola ve belirteç değerleri hiçbir günlük satırına düşmez. */
	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void passwordsAndTokensAreNeverLogged(CapturedOutput output) throws Exception {
		String email = newEmail();
		register(email);
		String body = androidLogin(email);
		login(email, "Yanlis-Parola-123", "ANDROID");
		protectedCall(field(body, "$.accessToken"));
		protectedCall("bozuk.belirtec.degeri");

		assertThat(output.getAll())
			.doesNotContain(PASSWORD)
			.doesNotContain("Yanlis-Parola-123")
			.doesNotContain(field(body, "$.refreshToken"))
			.doesNotContain(field(body, "$.accessToken"))
			.doesNotContain("bozuk.belirtec.degeri");
	}

	private int failedCount(UUID userId) {
		return jdbc.sql("select failed_login_count from identity.users where id = ?").param(userId)
			.query(Integer.class).single();
	}

	private int sessionCount(UUID userId) {
		return jdbc.sql("select count(*) from identity.refresh_tokens where user_id = ?").param(userId)
			.query(Integer.class).single();
	}

	static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
		return result
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.type").value(matchesPattern("/problems/[a-z-]+")))
			.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
	}

}
