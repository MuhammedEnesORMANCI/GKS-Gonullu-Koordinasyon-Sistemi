package org.gks.backend.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Acil alarm (§11.1, emergency-alert-design; ADR-018): taslak → önizleme → onaylı gönderim, kapsam ve hedef kitle
 * kuralları, alıcının görmesi ve yanıtı, hatırlatma ve yeniden gönderim, iptal/düzeltme, operasyon kapanışı, denetim.
 */
@Import(TestcontainersConfiguration.class)
// Zamanlayıcı testte elle çalıştırılır (arka plan turu yarışmasın).
@SpringBootTest(properties = "gks.alerts.reminder-interval=PT1H")
@AutoConfigureMockMvc
class AlertApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	AlertService service;

	TestSessions sessions;
	UUID admin;
	UUID kor;
	UUID v1;
	UUID v2;
	UUID v3;
	UUID outsider;
	UUID operationId;
	UUID groupId;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = register("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		kor = register("Koordinatör");
		v1 = register("Ayşe");
		v2 = register("Burak");
		v3 = register("Cem");
		outsider = register("Dışarıdan");
		operationId = operation("ACTIVE");
		role(kor, "KOR", "OPERATION", operationId);
		for (UUID v : new UUID[] { v1, v2, v3 }) {
			role(v, "GON", "OPERATION", operationId);
		}
		groupId = UUID.randomUUID();
		jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, 'Arama ekibi', ?)")
			.params(groupId, ORGANIZATION_ID, operationId, kor).update();
		member(v2, "LEADER");
		member(v3, "MEMBER");
	}

	@Test
	void draftPreviewAndConfirmedSendReachRecipientsInbox() throws Exception {
		String preview = draft(kor, "{\"message\": \"Su baskını, yüksek kata çıkın\", \"requestedAction\": \"Yanıt verin\","
				+ " \"targets\": {\"userIds\": [\"%s\", \"%s\"], \"groupIds\": [\"%s\"]}}".formatted(v1, kor, groupId))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.recipientCount").value(3))   // gönderen hariç (K-07), tekil
			.andExpect(jsonPath("$.operationName").value("Sel müdahalesi"))
			.andReturn().getResponse().getContentAsString();
		String alertId = JsonPath.read(preview, "$.alertId");
		String hash = JsonPath.read(preview, "$.audienceHash");
		assertThat(preview).contains("Arama ekibi");

		send(kor, alertId, 1, "yanlis-ozet", UUID.randomUUID().toString())
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDIENCE_CHANGED"))
			.andExpect(jsonPath("$.preview.audienceHash").value(hash));
		send(kor, alertId, 2, hash, UUID.randomUUID().toString())
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		String key = UUID.randomUUID().toString();
		send(kor, alertId, 1, hash, key).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.counts.total").value(3))
			.andExpect(jsonPath("$.counts.pending").value(3)).andExpect(jsonPath("$.responseOptions[1]").value("NEED_HELP"));
		send(kor, alertId, 1, hash, key).andExpect(status().isAccepted())
			.andExpect(header().string("Idempotent-Replayed", "true"));
		send(kor, alertId, 1, hash, UUID.randomUUID().toString())
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));

		mvc.perform(get("/api/v1/me/notifications").with(authenticate(sessions.android(v3))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].category").value("ALERT"))
			.andExpect(jsonPath("$.items[0].priority").value("CRITICAL"))
			.andExpect(jsonPath("$.items[0].link.type").value("ALERT"))
			.andExpect(jsonPath("$.items[0].link.id").value(alertId));
		assertThat(basis("ALERT_SENT")).isEqualTo("KOR");
		assertThat(jdbc.sql("select details::text from audit.audit_entries where action = 'ALERT_SENT' and resource_id = ?")
			.param(UUID.fromString(alertId)).query(String.class).single()).doesNotContain("baskın");
		mvc.perform(get("/api/v1/operations/{id}/alerts", operationId).with(authenticate(sessions.web(kor, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(alertId))
			.andExpect(jsonPath("$.items[0].counts.total").value(3));
		mvc.perform(get("/api/v1/alerts").with(authenticate(sessions.web(admin, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id", hasItem(alertId)));
	}

	@Test
	void scopeAndAudienceRules() throws Exception {
		String body = "{\"message\": \"Test\", \"targets\": {\"userIds\": [\"%s\"]}}";
		draft(sessions.android(v1), body.formatted(v2)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));   // MFA'sız gönüllü oturumu
		UUID otherKor = register("Başka KOR");
		role(otherKor, "KOR", "OPERATION", operation("ACTIVE"));
		draft(otherKor, body.formatted(v2)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		draft(kor, body.formatted(outsider)).andExpect(status().isForbidden());   // katılımcı olmayan hedef (AC-20.7)
		draft(kor, "{\"message\": \"Test\", \"targets\": {\"groupIds\": [\"%s\"]}}".formatted(UUID.randomUUID()))
			.andExpect(status().isForbidden());                                   // kapsam dışı grup
		draft(kor, "{\"message\": \"\", \"targets\": {}}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field", hasItem("message"))).andExpect(jsonPath("$.errors[*].field", hasItem("targets")));
		UUID planned = operation("PLANNED");
		role(kor, "KOR", "OPERATION", planned);
		draft(kor, body.formatted(v1)).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/operations/{id}/alerts", planned).with(authenticate(sessions.web(kor, true)))
			.contentType(MediaType.APPLICATION_JSON).content(body.formatted(v1)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_NOT_ACTIVE"));
	}

	@Test
	void admManagesAlertsAndCorrectionsWithoutKorAssignment() throws Exception {
		mvc.perform(get("/api/v1/me/context").with(authenticate(sessions.web(admin, true))))
			.andExpect(jsonPath("$.permissions.operations['" + operationId + "']", hasItem("alert.send")))
			.andExpect(jsonPath("$.permissions.operations['" + operationId + "']", hasItem("alert.cancel")));
		String first = sendTo(admin, v1);
		assertThat(basis("ALERT_SENT")).as("ADR-018").isEqualTo("ADM");
		mvc.perform(post("/api/v1/alerts/{id}/cancel", first).with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"Yanlış bölge\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledBy.displayName").value("Yönetici"));
		mvc.perform(get("/api/v1/me/notifications").with(authenticate(sessions.android(v1))))
			.andExpect(jsonPath("$.items[0].kind").value("ALERT_CANCELLED"));
		String recipient = myRecipient(v1, "CANCELLED");
		respond(v1, recipient, "RECEIVED", UUID.randomUUID().toString())
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_NOT_OPEN"))
			.andExpect(jsonPath("$.alertState").value("CANCELLED"));

		String correction = "{\"message\": \"Düzeltme: doğu yakası\", \"targets\": {\"userIds\": [\"%s\"]}, \"correctsAlertId\": \"%s\"}";
		String second = JsonPath.read(draft(kor, correction.formatted(v1, first)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.alertId");
		draft(kor, correction.formatted(v1, first)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.conflict").value("ALERT_ALREADY_CORRECTED"));
		String open = sendTo(kor, v2);
		draft(kor, correction.formatted(v1, open)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
		mvc.perform(get("/api/v1/alerts/{id}", first).with(authenticate(sessions.web(kor, true))))
			.andExpect(jsonPath("$.correctedByAlertId").value(second));
	}

	@Test
	void recipientSeesRespondsAndChangesResponse() throws Exception {
		String alertId = sendTo(kor, v1, v2);
		String recipient = myRecipient(v1, "OPEN");
		mvc.perform(get("/api/v1/alerts/{id}", alertId).with(authenticate(sessions.android(v1))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.recipient.id").value(recipient))
			.andExpect(jsonPath("$.counts").doesNotExist());               // alıcı sayıları ve diğer alıcıları görmez
		mvc.perform(get("/api/v1/alerts/{id}", alertId).with(authenticate(sessions.android(v3)))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/alert-recipients/{id}/seen", recipient).with(authenticate(sessions.android(v1))))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/alert-recipients/{id}/delivered", recipient).with(authenticate(sessions.android(v1))))
			.andExpect(status().isNoContent());                             // geri alınmaz (B-03)
		respond(v2, recipient, "RECEIVED", UUID.randomUUID().toString()).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/alert-recipients/{id}/responses", recipient).with(authenticate(sessions.web(v1, false)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"option\": \"RECEIVED\", \"respondedAtDevice\": \"2026-11-16T09:00:00Z\"}"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
		respond(v1, recipient, "NEED_HELP", UUID.randomUUID().toString()).andExpect(status().isOk())
			.andExpect(jsonPath("$.recipient.state").value("RESPONDED"))
			.andExpect(jsonPath("$.recipient.responseOption").value("NEED_HELP"));

		mvc.perform(get("/api/v1/alerts/{id}/recipients", alertId).with(authenticate(sessions.web(kor, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].user.id").value(v1.toString()))
			.andExpect(jsonPath("$.items[0].seenAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].deliveredAt").value(nullValue()))   // bilinmiyor da yazılır (AC-24.2)
			.andExpect(jsonPath("$.items[1].state").value("SENT"));
		mvc.perform(get("/api/v1/alerts/{id}", alertId).with(authenticate(sessions.web(kor, true))))
			.andExpect(jsonPath("$.counts.responded").value(1)).andExpect(jsonPath("$.counts.needHelp").value(1))
			.andExpect(jsonPath("$.counts.pending").value(1));

		respond(v1, recipient, "ON_MY_WAY", UUID.randomUUID().toString()).andExpect(status().isOk());
		assertThat(jdbc.sql("""
				select details->>'previousOption' from audit.audit_entries
				 where action = 'ALERT_RESPONDED' and resource_id = ? order by id desc limit 1""")
			.param(UUID.fromString(recipient)).query(String.class).single()).isEqualTo("NEED_HELP");
	}

	@Test
	void remindersMarkUnansweredAndResendRequeues() throws Exception {
		String alertId = sendTo(kor, v1);
		UUID id = UUID.fromString(alertId);
		jdbc.sql("update alerts.alert_recipients set reminder_count = 5, next_reminder_at = now() - interval '1 second' where alert_id = ?")
			.param(id).update();
		assertThat(service.advanceReminders(100)).isEqualTo(1);
		mvc.perform(get("/api/v1/alerts/{id}", alertId).with(authenticate(sessions.web(kor, true))))
			.andExpect(jsonPath("$.counts.unanswered").value(1));

		String inboxItem = JsonPath.read(mvc.perform(get("/api/v1/me/notifications").with(authenticate(sessions.android(v1))))
			.andReturn().getResponse().getContentAsString(), "$.items[0].id");
		mvc.perform(post("/api/v1/me/notifications/{id}/read", inboxItem).with(authenticate(sessions.android(v1))))
			.andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/alerts/{id}/resend", alertId).with(authenticate(sessions.web(kor, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isAccepted()).andExpect(jsonPath("$.requeuedCount").value(1))
			.andExpect(jsonPath("$.alert.counts.pending").value(1));
		mvc.perform(get("/api/v1/me/notifications/unread-count").with(authenticate(sessions.android(v1))))
			.andExpect(jsonPath("$.unreadCount").value(1));                  // gelen kutusunda yeniden öne çıktı
		assertThat(jdbc.sql("select resend_count from alerts.alert_recipients where alert_id = ?").param(id)
			.query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void closingOperationClosesOpenAlertsAndManualCloseIsAudited() throws Exception {
		String manual = sendTo(kor, v1);
		mvc.perform(post("/api/v1/alerts/{id}/close", manual).with(authenticate(sessions.web(kor, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.closeReason").value("MANUAL"));
		mvc.perform(post("/api/v1/alerts/{id}/close", manual).with(authenticate(sessions.web(kor, true))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALERT_NOT_OPEN"));

		String open = sendTo(kor, v2);
		mvc.perform(post("/api/v1/operations/{id}/state", operationId).with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"targetState\": \"COMPLETED\"}"))
			.andExpect(status().isOk());
		mvc.perform(get("/api/v1/alerts/{id}", open).with(authenticate(sessions.web(admin, true))))
			.andExpect(jsonPath("$.state").value("CLOSED")).andExpect(jsonPath("$.closeReason").value("OPERATION_CLOSED"))
			.andExpect(jsonPath("$.closedBy").doesNotExist());
		assertThat(jdbc.sql("select count(*) from audit.audit_entries where action = 'ALERT_CLOSED' and operation_id = ?")
			.param(operationId).query(Integer.class).single()).isEqualTo(2);
	}

	// ---- yardımcılar ----

	ResultActions draft(UUID actor, String body) throws Exception {
		return draft(sessions.web(actor, true), body);
	}

	ResultActions draft(String token, String body) throws Exception {
		return mvc.perform(post("/api/v1/operations/{id}/alerts", operationId).with(authenticate(token))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions send(UUID actor, String alertId, int version, String hash, String key) throws Exception {
		return mvc.perform(post("/api/v1/alerts/{id}/send", alertId).with(authenticate(sessions.web(actor, true)))
			.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\": %d, \"audienceHash\": \"%s\"}".formatted(version, hash)));
	}

	String sendTo(UUID actor, UUID... users) throws Exception {
		StringBuilder ids = new StringBuilder();
		for (UUID user : users) {
			ids.append(ids.isEmpty() ? "" : ", ").append('"').append(user).append('"');
		}
		String preview = draft(actor, "{\"message\": \"Acil toplanma\", \"targets\": {\"userIds\": [" + ids + "]}}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String alertId = JsonPath.read(preview, "$.alertId");
		send(actor, alertId, 1, JsonPath.read(preview, "$.audienceHash"), UUID.randomUUID().toString())
			.andExpect(status().isAccepted());
		return alertId;
	}

	String myRecipient(UUID user, String state) throws Exception {
		return JsonPath.read(mvc.perform(get("/api/v1/me/alerts").param("state", state).with(authenticate(sessions.android(user))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[0].recipient.id");
	}

	ResultActions respond(UUID user, String recipientId, String option, String key) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/alert-recipients/{id}/responses", recipientId)
			.with(authenticate(sessions.android(user))).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
			.content("{\"option\": \"%s\", \"respondedAtDevice\": \"2026-11-16T09:00:00Z\"}".formatted(option));
		return mvc.perform(request);
	}

	String basis(String action) {
		return jdbc.sql("select actor_basis from audit.audit_entries where action = ? and operation_id = ? order by id desc limit 1")
			.params(action, operationId).query(String.class).single();
	}

	UUID operation(String state) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', ?, case when ? = 'ACTIVE' then now() end, ?)""")
			.params(id, ORGANIZATION_ID, state, state, admin).update();
		return id;
	}

	void member(UUID userId, String role) {
		jdbc.sql("insert into groups.group_memberships (id, group_id, user_id, role, added_by) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), groupId, userId, role, kor).update();
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("alarm-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				name + " Kişi", name, null));
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

}
