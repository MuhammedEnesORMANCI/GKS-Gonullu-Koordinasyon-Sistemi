package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.gks.backend.identity.LoginApiTests.expectProblem;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** ID-08 – ID-11: parola değişikliği, zorunlu değişiklik kapısı, profil ve {@code /me/context}. */
class AccountApiTests extends IdentityTestSupport {

	static final String NEW_PASSWORD = "Yeni-Guclu-Parola-2027";

	@Nested
	class PasswordChange {

		@Test
		void wrongCurrentPasswordIsValidationErrorNot401() throws Exception {
			String email = newEmail();
			register(email);
			String token = field(androidLogin(email), "$.accessToken");

			expectProblem(changePassword(token, "Yanlis-Parola-123", NEW_PASSWORD), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("currentPassword"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID"));
			login(email, PASSWORD, "ANDROID").andExpect(status().isOk());
		}

		@Test
		void newPasswordMustDifferAndFollowPolicy() throws Exception {
			String email = newEmail();
			register(email);
			String token = field(androidLogin(email), "$.accessToken");

			expectProblem(changePassword(token, PASSWORD, PASSWORD), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("newPassword"))
				.andExpect(jsonPath("$.errors[0].code").value("NOT_ALLOWED_VALUE"));
			expectProblem(changePassword(token, PASSWORD, "kisa"), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("newPassword"));
		}

		@Test
		void successRevokesAllOldTokensAndGivesCallerNewPair() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String currentLogin = androidLogin(email);
			String current = field(currentLogin, "$.accessToken");
			String other = field(androidLogin(email), "$.accessToken");

			String body = changePassword(current, PASSWORD, NEW_PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

			// Parola öncesi belirteçlerin hepsi (çağıranınki dahil) hemen geçersiz.
			expectProblem(protectedCall(current), 401, "AUTH_SESSION_REVOKED");
			expectProblem(protectedCall(other), 401, "AUTH_SESSION_REVOKED");
			expectProblem(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\": \"%s\"}".formatted(field(currentLogin, "$.refreshToken")))), 401,
					"AUTH_SESSION_REVOKED");
			// Yeni çift çalışır; yeni oturumdur.
			assertThat(field(body, "$.sessionId")).isNotEqualTo(field(currentLogin, "$.session.sessionId"));
			protectedCall(field(body, "$.accessToken")).andExpect(status().isNotFound());
			mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\": \"%s\"}".formatted(field(body, "$.refreshToken"))))
				.andExpect(status().isOk());
			assertThat(activeSessionCount(userId)).isEqualTo(1);
			expectProblem(login(email, PASSWORD, "ANDROID"), 401, "AUTH_INVALID_CREDENTIALS");
			login(email, NEW_PASSWORD, "ANDROID").andExpect(status().isOk());
		}

		@Test
		void webSessionKeepsMfaAndAbsoluteExpiryAndGetsNewCookies() throws Exception {
			UUID userId = register(newEmail());
			String token = sessions().web(userId, true);
			java.time.Instant expiry = jdbc.sql("select expires_at from identity.refresh_tokens where user_id = ?")
				.param(userId).query(java.sql.Timestamp.class).single().toInstant();

			var response = mvc.perform(post("/api/v1/me/password").with(authenticate(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(PASSWORD, NEW_PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.refreshToken").doesNotExist())
				.andReturn().getResponse();

			String newAccess = response.getCookie("__Host-access").getValue();
			assertThat(response.getCookie("__Secure-refresh")).isNotNull();
			expectProblem(mvc.perform(get("/api/v1/me").with(authenticate(token))), 401, "AUTH_SESSION_REVOKED");
			mvc.perform(get("/api/v1/me/context").with(authenticate(newAccess)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.session.mfaVerified").value(true));
			assertThat(jdbc.sql("""
					select expires_at from identity.refresh_tokens where user_id = ? and revoked_at is null""")
				.param(userId).query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(expiry);
		}

		@Test
		void requiresAuthentication() throws Exception {
			expectProblem(mvc.perform(post("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\": \"a\", \"newPassword\": \"b\"}")), 401, "AUTH_UNAUTHENTICATED");
		}

	}

	@Nested
	class ForcedPasswordChange {

		@Test
		void onlyProfileContextPasswordAndLogoutAreReachableUntilChanged() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			jdbc.sql("update identity.users set must_change_password = true where id = ?").param(userId).update();
			String login = androidLogin(email);
			assertThat(field(login, "$.mustChangePassword")).isEqualTo("true");
			String token = field(login, "$.accessToken");

			expectProblem(protectedCall(token), 403, "AUTH_PASSWORD_CHANGE_REQUIRED");
			expectProblem(mvc.perform(patch("/api/v1/me").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\": \"Yeni\"}")), 403, "AUTH_PASSWORD_CHANGE_REQUIRED");
			expectProblem(mvc.perform(get("/api/v1/me/assignments").with(authenticate(token))), 403,
					"AUTH_PASSWORD_CHANGE_REQUIRED");
			mvc.perform(get("/api/v1/me").with(authenticate(token))).andExpect(status().isOk());
			mvc.perform(get("/api/v1/me/context").with(authenticate(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.session.mustChangePassword").value(true));

			String renewed = field(changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(), "$.accessToken");

			protectedCall(renewed).andExpect(status().isNotFound());
			assertThat(jdbc.sql("select must_change_password from identity.users where id = ?").param(userId)
				.query(Boolean.class).single()).isFalse();
		}

		@Test
		void logoutIsAllowedWhileChangeRequired() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			jdbc.sql("update identity.users set must_change_password = true where id = ?").param(userId).update();
			String token = field(androidLogin(email), "$.accessToken");

			mvc.perform(post("/api/v1/auth/logout").with(authenticate(token))).andExpect(status().isNoContent());
			assertThat(activeSessionCount(userId)).isZero();
		}

	}

	@Nested
	class Profile {

		@Test
		void readAndUpdateOwnProfile() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			String token = field(androidLogin(email), "$.accessToken");

			mvc.perform(get("/api/v1/me").with(authenticate(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(userId.toString()))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.mfaEnrolled").value(false))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

			mvc.perform(patch("/api/v1/me").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\": \"Ayşe Y.\", \"phone\": \"+905551112233\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Ayşe Y."))
				.andExpect(jsonPath("$.phone").value("+905551112233"));
		}

		@Test
		void readOnlyFieldsCannotBeChanged() throws Exception {
			String email = newEmail();
			register(email);
			String token = field(androidLogin(email), "$.accessToken");

			expectProblem(mvc.perform(patch("/api/v1/me").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"baska@example.org\", \"status\": \"ACTIVE\"}")), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("READ_ONLY", "READ_ONLY")));
			mvc.perform(get("/api/v1/me").with(authenticate(token))).andExpect(jsonPath("$.email").value(email));
		}

		@Test
		void requiresAuthentication() throws Exception {
			expectProblem(mvc.perform(get("/api/v1/me")), 401, "AUTH_UNAUTHENTICATED");
			expectProblem(mvc.perform(get("/api/v1/me/context")), 401, "AUTH_UNAUTHENTICATED");
		}

	}

	/** Etkin izinler <b>oturuma</b> göredir: kurumsal ve KOR yetkileri yalnızca MFA'lı web oturumunda. */
	@Nested
	class Context {

		@Test
		void coordinatorPermissionsOnlyInMfaVerifiedWebSession() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			UUID operationId = insertOperation("Deprem Tatbikatı", userId);
			grant(userId, "KOR", operationId);

			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().web(userId, true))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(userId.toString()))
				.andExpect(jsonPath("$.session.clientType").value("WEB"))
				.andExpect(jsonPath("$.session.mfaVerified").value(true))
				.andExpect(jsonPath("$.operations[0].id").value(operationId.toString()))
				.andExpect(jsonPath("$.operations[0].myRoles[0]").value("KOR"))
				.andExpect(jsonPath("$.roles[0].role").value("KOR"))
				.andExpect(jsonPath("$.permissions.operations['" + operationId + "']").value(hasItem("task.manage")));

			// Aynı kullanıcının Android ve MFA'sız web oturumu KOR yetkisi taşımaz; operasyon yine listelenir.
			for (String token : new String[] { sessions().android(userId), sessions().web(userId, false) }) {
				mvc.perform(get("/api/v1/me/context").with(authenticate(token)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.operations[0].id").value(operationId.toString()))
					.andExpect(jsonPath("$.permissions.operations['" + operationId + "']").doesNotExist());
			}
		}

		@Test
		void volunteerPermissionsOnAnyClient() throws Exception {
			String email = newEmail();
			UUID userId = register(email);
			UUID operationId = insertOperation("Sel Müdahalesi", userId);
			grant(userId, "GON", operationId);

			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().android(userId))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.permissions.operations['" + operationId + "']").value(hasItem("task.respond")))
				.andExpect(jsonPath("$.permissions.organization").value(not(hasItem("operation.create"))));
		}

		@Test
		void corporateRolesNeedWebAndAdminNeedsMfa() throws Exception {
			UUID tek = register(newEmail());
			grant(tek, "TEK", organizationId());
			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().web(tek, false))))
				.andExpect(jsonPath("$.permissions.organization").value(hasItem("session.revoke")));
			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().android(tek))))
				.andExpect(jsonPath("$.permissions.organization").value(not(hasItem("session.revoke"))));

			UUID adm = register(newEmail());
			grant(adm, "ADM", organizationId());
			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().web(adm, true))))
				.andExpect(jsonPath("$.permissions.organization").value(hasItem("operation.create")));
			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().web(adm, false))))
				.andExpect(jsonPath("$.permissions.organization").value(not(hasItem("operation.create"))));
		}

		@Test
		void groupsShowActiveMembershipsWithRolePermissions() throws Exception {
			UUID userId = register(newEmail());
			UUID operationId = insertOperation("Arama Kurtarma", userId);
			grant(userId, "GON", operationId);
			UUID led = group(operationId, "Alfa Ekibi", userId);
			UUID member = group(operationId, "Bravo Ekibi", userId);
			UUID archived = group(operationId, "Eski Ekip", userId);
			UUID left = group(operationId, "Charlie Ekibi", userId);
			membership(led, userId, "LEADER", true);
			membership(member, userId, "MEMBER", true);
			membership(archived, userId, "MEMBER", true);
			membership(left, userId, "MEMBER", false);
			jdbc.sql("update groups.groups set archived_at = now() where id = ?").param(archived).update();

			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().android(userId))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.groups.length()").value(2))
				.andExpect(jsonPath("$.groups[0].id").value(led.toString()))
				.andExpect(jsonPath("$.groups[0].name").value("Alfa Ekibi"))
				.andExpect(jsonPath("$.groups[0].operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.groups[0].role").value("LEADER"))
				.andExpect(jsonPath("$.groups[1].role").value("MEMBER"))
				.andExpect(jsonPath("$.permissions.groups['" + led + "']").value(hasItem("group.update")))
				.andExpect(jsonPath("$.permissions.groups['" + member + "']").value(not(hasItem("group.update"))))
				.andExpect(jsonPath("$.permissions.groups['" + member + "']").value(hasItem("group.view")))
				.andExpect(jsonPath("$.permissions.groups['" + archived + "']").doesNotExist())
				.andExpect(jsonPath("$.permissions.groups['" + left + "']").doesNotExist());
		}

		@Test
		void closedOperationsAreNotListed() throws Exception {
			UUID userId = register(newEmail());
			UUID operationId = insertOperation("Kapanmış", userId);
			grant(userId, "GON", operationId);
			jdbc.sql("update organization.operations set state = 'CANCELLED', closed_at = now() where id = ?")
				.param(operationId).update();

			mvc.perform(get("/api/v1/me/context").with(authenticate(sessions().android(userId))))
				.andExpect(jsonPath("$.operations").isEmpty())
				.andExpect(jsonPath("$.pendingInvitations").isEmpty());
		}

	}

	private UUID group(UUID operationId, String name, UUID createdBy) {
		UUID id = org.gks.backend.Ids.newId();
		jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, ?, ?)")
			.params(id, organizationId(), operationId, name, createdBy).update();
		return id;
	}

	private void membership(UUID groupId, UUID userId, String role, boolean active) {
		jdbc.sql("""
				insert into groups.group_memberships (id, group_id, user_id, role, status, added_by, ended_at, ended_by, end_reason)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(org.gks.backend.Ids.newId(), groupId, userId, role, active ? "ACTIVE" : "REMOVED", userId,
					active ? null : java.sql.Timestamp.from(java.time.Instant.now()), active ? null : userId,
					active ? null : "REMOVED_BY_COORDINATOR")
			.update();
	}

	private ResultActions changePassword(String token, String current, String next) throws Exception {
		return mvc.perform(post("/api/v1/me/password").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(current, next)));
	}

}
