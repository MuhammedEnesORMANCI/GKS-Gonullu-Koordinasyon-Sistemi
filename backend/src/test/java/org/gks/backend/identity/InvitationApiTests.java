package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.gks.backend.identity.LoginApiTests.expectProblem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.gks.backend.access.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * ID-12 – ID-18: davet oluşturma yetkisi, kodun gizliliği, tek kullanım, süre, ID-16 idempotency (sır içeren yanıt
 * tekrar edilmez) ve katılımın oluşması.
 */
class InvitationApiTests extends IdentityTestSupport {

	@MockitoBean
	InvitationMailer mailer;

	UUID coordinator;
	UUID operationId;
	String coordinatorToken;

	@BeforeEach
	void coordinatorWithOperation() {
		coordinator = register(newEmail());
		operationId = insertOperation("Deprem Müdahalesi", coordinator);
		coordinatorToken = sessions().web(coordinator, true);
		given(operationAccess.canManage(any(), any())).willReturn(Decision.FORBIDDEN);
		given(operationAccess.canManage(any(), eq(operationId))).willReturn(Decision.ALLOW);
		given(operationAccess.canCreate(any())).willReturn(Decision.FORBIDDEN);
	}

	@Test
	void newUserInvitationEndToEnd() throws Exception {
		String email = newEmail();

		String view = invite(email, operationId).andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/invitations/")))
			.andExpect(jsonPath("$.kind").value("NEW_USER"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.scopeName").value("Deprem Müdahalesi"))
			.andExpect(jsonPath("$.code").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String code = mailedCode(email);
		assertThat(view).doesNotContain(code);
		assertThat(jdbc.sql("select count(*) from identity.invitations where code_hash = ? or token_hash = ?")
			.params(code, code).query(Integer.class).single()).isZero();

		// Kimliksiz önizleme kişisel veri içermez.
		lookup(code).andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("NEW_USER"))
			.andExpect(jsonPath("$.scopeName").value("Deprem Müdahalesi"))
			.andExpect(jsonPath("$.organizationName").isNotEmpty())
			.andExpect(jsonPath("$.email").doesNotExist());

		String key = UUID.randomUUID().toString();
		String body = accept(code, key).andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.session.clientType").value("ANDROID"))
			.andReturn().getResponse().getContentAsString();
		UUID userId = jdbc.sql("select id from identity.users where email = ?").param(email).query(UUID.class).single();
		assertThat(jdbc.sql("""
				select count(*) from access.role_assignments
				 where user_id = ? and role = 'GON' and scope_id = ? and revoked_at is null""")
			.params(userId, operationId).query(Integer.class).single()).isEqualTo(1);
		mvc.perform(get("/api/v1/me").with(authenticate(field(body, "$.accessToken"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
		lookup(code).andExpect(status().isGone()).andExpect(jsonPath("$.invitationStatus").value("ACCEPTED"));

		// Aynı anahtarla tekrar: ilk yanıt (belirteçler) yeniden verilmez, yeni oturum açılmaz.
		expectProblem(accept(code, key), 409, "ALREADY_COMPLETED")
			.andExpect(jsonPath("$.resourceId").value(userId.toString()))
			.andExpect(jsonPath("$.next").value("LOGIN"));
		assertThat(jdbc.sql("select response_body is null and body_redacted from platform.idempotency_keys "
				+ "where idem_key = ?").param(key).query(Boolean.class).single()).isTrue();
		// Farklı anahtarla ikinci kabul.
		expectProblem(accept(code, UUID.randomUUID().toString()), 410, "INVITATION_INVALID");
		assertThat(activeSessionCount(userId)).isEqualTo(1);
	}

	@Test
	void unknownExpiredAndRevokedCodesAreRejected() throws Exception {
		expectProblem(lookup("ABCD-EFGH"), 404, "NOT_FOUND");
		expectProblem(lookup("kısa"), 404, "NOT_FOUND");

		String expiredEmail = newEmail();
		invite(expiredEmail, operationId).andExpect(status().isCreated());
		String expired = mailedCode(expiredEmail);
		jdbc.sql("update identity.invitations set expires_at = now() - interval '1 minute', created_at = now() - interval '73 hours' "
				+ "where email = ?").param(expiredEmail).update();
		expectProblem(lookup(expired), 410, "INVITATION_INVALID").andExpect(jsonPath("$.invitationStatus").value("EXPIRED"));
		expectProblem(accept(expired, UUID.randomUUID().toString()), 410, "INVITATION_INVALID");

		String revokedEmail = newEmail();
		String id = field(invite(revokedEmail, operationId).andReturn().getResponse().getContentAsString(), "$.id");
		String revoked = mailedCode(revokedEmail);
		mvc.perform(post("/api/v1/invitations/{id}/revoke", id).with(authenticate(coordinatorToken)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
		expectProblem(accept(revoked, UUID.randomUUID().toString()), 410, "INVITATION_INVALID")
			.andExpect(jsonPath("$.invitationStatus").value("REVOKED"));
		expectProblem(mvc.perform(post("/api/v1/invitations/{id}/revoke", id).with(authenticate(coordinatorToken))), 409,
				"INVALID_STATE_TRANSITION");
		assertThat(jdbc.sql("select count(*) from identity.users where email in (?, ?)").params(expiredEmail, revokedEmail)
			.query(Integer.class).single()).isZero();
	}

	@Test
	void onlyManagersOnWebCanInvite() throws Exception {
		UUID other = insertOperation("Başka Operasyon", coordinator);
		String email = newEmail();

		expectProblem(invite(email, other), 403, "FORBIDDEN");
		expectProblem(mvc.perform(post("/api/v1/invitations").with(authenticate(sessions().android(coordinator)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content(body(email, operationId))), 403, "CLIENT_NOT_ALLOWED");
		expectProblem(mvc.perform(post("/api/v1/invitations").with(authenticate(coordinatorToken))
			.contentType(MediaType.APPLICATION_JSON).content(body(email, operationId))), 400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
		expectProblem(mvc.perform(post("/api/v1/invitations").contentType(MediaType.APPLICATION_JSON)
			.content(body(email, operationId))), 401, "AUTH_UNAUTHENTICATED");

		assertThat(jdbc.sql("select count(*) from identity.invitations where email = ?").param(email)
			.query(Integer.class).single()).isZero();
		verify(mailer, never()).sendNewUser(eq(email), anyString(), anyString(), any());
	}

	@Test
	void conflictsAndClosedOperation() throws Exception {
		String email = newEmail();
		invite(email, operationId).andExpect(status().isCreated());
		expectProblem(invite(email, operationId), 409, "ALREADY_EXISTS")
			.andExpect(jsonPath("$.conflict").value("INVITATION_PENDING"));

		UUID participant = register(newEmail());
		grant(participant, "GON", operationId);
		String participantEmail = jdbc.sql("select email from identity.users where id = ?").param(participant)
			.query(String.class).single();
		expectProblem(invite(participantEmail, operationId), 409, "ALREADY_EXISTS")
			.andExpect(jsonPath("$.conflict").value("ALREADY_PARTICIPANT"));

		jdbc.sql("update organization.operations set state = 'CANCELLED', closed_at = now() where id = ?")
			.param(operationId).update();
		expectProblem(invite(newEmail(), operationId), 409, "OPERATION_CLOSED");
	}

	@Test
	void registeredUserAcceptsOrDeclinesOnAndroid() throws Exception {
		String email = newEmail();
		UUID volunteer = register(email);
		String invitationId = field(invite(email, operationId).andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("OPERATION")).andReturn().getResponse().getContentAsString(), "$.id");
		verify(mailer).sendOperation(eq(email), eq("Deprem Müdahalesi"), any());
		String android = sessions().android(volunteer);

		mvc.perform(get("/api/v1/me/context").with(authenticate(android)))
			.andExpect(jsonPath("$.pendingInvitations[0].id").value(invitationId))
			.andExpect(jsonPath("$.pendingInvitations[0].scopeName").value("Deprem Müdahalesi"));

		// Başkası kabul edemez; web oturumu kabul edemez; anahtar zorunlu.
		expectProblem(respond(sessions().android(register(newEmail())), invitationId, "accept"), 404, "NOT_FOUND");
		expectProblem(respond(sessions().web(volunteer, false), invitationId, "accept"), 403, "CLIENT_NOT_ALLOWED");
		expectProblem(mvc.perform(post("/api/v1/invitations/{id}/accept", invitationId).with(authenticate(android))), 400,
				"VALIDATION_FAILED");

		respond(android, invitationId, "accept").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
		assertThat(jdbc.sql("select count(*) from access.role_assignments where user_id = ? and role = 'GON' "
				+ "and scope_id = ? and revoked_at is null").params(volunteer, operationId).query(Integer.class).single())
			.isEqualTo(1);
		mvc.perform(get("/api/v1/me/context").with(authenticate(android)))
			.andExpect(jsonPath("$.pendingInvitations").isEmpty())
			.andExpect(jsonPath("$.operations[0].id").value(operationId.toString()));
		expectProblem(respond(android, invitationId, "decline"), 410, "INVITATION_INVALID");

		// Ret katılım oluşturmaz.
		UUID second = insertOperation("İkinci Operasyon", coordinator);
		given(operationAccess.canManage(any(), eq(second))).willReturn(Decision.ALLOW);
		String declined = field(invite(email, second).andReturn().getResponse().getContentAsString(), "$.id");
		respond(android, declined, "decline").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
		assertThat(jdbc.sql("select count(*) from access.role_assignments where user_id = ? and scope_id = ?")
			.params(volunteer, second).query(Integer.class).single()).isZero();
	}

	@Test
	void coordinatorListsOnlyOwnOperationInvitations() throws Exception {
		grant(coordinator, "KOR", operationId);
		UUID foreign = insertOperation("Yabancı Operasyon", coordinator);
		String mine = newEmail();
		invite(mine, operationId).andExpect(status().isCreated());
		jdbc.sql("""
				insert into identity.invitations (id, kind, email, scope_type, scope_id, token_hash, code_hash, expires_at, created_by)
				values (?, 'NEW_USER', ?, 'OPERATION', ?, ?, ?, now() + interval '1 day', ?)""")
			.params(org.gks.backend.Ids.newId(), newEmail(), foreign, "t-" + UUID.randomUUID(), "c-" + UUID.randomUUID(),
					coordinator)
			.update();

		mvc.perform(get("/api/v1/invitations").with(authenticate(coordinatorToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].email").value(mine));
		expectProblem(mvc.perform(get("/api/v1/invitations").param("scopeId", foreign.toString())
			.with(authenticate(coordinatorToken))), 403, "FORBIDDEN");
	}

	private ResultActions invite(String email, UUID scope) throws Exception {
		return mvc.perform(post("/api/v1/invitations").with(authenticate(coordinatorToken))
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON).content(body(email, scope)));
	}

	private static String body(String email, UUID scope) {
		return "{\"email\": \"%s\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}".formatted(email, scope);
	}

	private ResultActions lookup(String code) throws Exception {
		return mvc.perform(post("/api/v1/invitations/lookup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\": \"%s\"}".formatted(code)));
	}

	private ResultActions accept(String code, String key) throws Exception {
		return mvc.perform(post("/api/v1/invitations/accept").header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "password": "%s", "fullName": "Mehmet Kaya", "displayName": "Mehmet",
					 "installationId": "%s"}""".formatted(code, PASSWORD, INSTALLATION)));
	}

	private ResultActions respond(String token, String invitationId, String action) throws Exception {
		return mvc.perform(post("/api/v1/invitations/{id}/" + action, invitationId).with(authenticate(token))
			.header("Idempotency-Key", UUID.randomUUID().toString()));
	}

	private String mailedCode(String email) {
		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(mailer).sendNewUser(eq(email), anyString(), code.capture(), any());
		return code.getValue();
	}

}
