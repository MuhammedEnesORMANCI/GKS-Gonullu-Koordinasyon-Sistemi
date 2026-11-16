package org.gks.backend.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gks.backend.RealtimeBus;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * RT-01 (§12.1–12.2): yetki her bağlantıda denetlenir (kısmi abonelik yok), olaylar işlem tamamlandıktan sonra ve
 * içeriksiz gider, yetki düşünce konu çıkarılır, oturum iptali akışı kapatır, {@code Last-Event-ID} ile yeniden
 * oynatma ve bilinmeyen kimlikte {@code resync}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class StreamApiTests {

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
	RealtimeBus realtime;

	@Autowired
	TransactionTemplate transactions;

	TestSessions sessions;
	UUID admin;
	UUID kor;
	UUID korAssignment;
	UUID volunteer;
	UUID operationId;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = register("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		kor = register("Koordinatör");
		volunteer = register("Gönüllü");
		operationId = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', 'ACTIVE', now(), ?)""").params(operationId, ORGANIZATION_ID, admin).update();
		korAssignment = role(kor, "KOR", "OPERATION", operationId);
		role(volunteer, "GON", "OPERATION", operationId);
	}

	@Test
	void topicsAreAuthorizedOnEveryConnectionWithoutPartialSubscription() throws Exception {
		String operation = "operation:" + operationId;
		open(sessions.web(kor, true), operation);
		open(sessions.web(admin, true), operation + ",organization");   // ADR-015: KOR ataması olmadan
		refuse(sessions.android(volunteer), operation, 403, "FORBIDDEN");
		refuse(sessions.web(kor, true), operation + ",organization", 403, "FORBIDDEN");   // kısmi abonelik yok
		refuse(sessions.web(kor, true), "user:" + admin, 403, "FORBIDDEN");
		refuse(sessions.web(kor, false), operation, 403, "CLIENT_NOT_ALLOWED");   // MFA'sız KOR web oturumu
		refuse(sessions.web(kor, true), "task:" + operationId, 400, "VALIDATION_FAILED");
		refuse(sessions.web(kor, true), (operation + ",").repeat(5) + String.join(",",
				java.util.stream.Stream.generate(() -> "operation:" + UUID.randomUUID()).limit(10).toList()), 400,
				"VALIDATION_FAILED");
		mvc.perform(get("/api/v1/stream")).andExpect(status().isUnauthorized());
	}

	@Test
	void inboxAndAnnouncementEventsArriveAfterCommitWithoutContent() throws Exception {
		MvcResult volunteerStream = open(sessions.android(volunteer), null);
		MvcResult korStream = open(sessions.web(kor, true), "operation:" + operationId);
		mvc.perform(post("/api/v1/announcements").with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"operationId\": \"%s\", \"title\": \"Gizli başlık\", \"body\": \"Gizli metin\"}".formatted(operationId)))
			.andExpect(status().isAccepted());

		String inbox = body(volunteerStream);
		assertThat(inbox).contains("retry:3000").contains("event:inbox.changed").contains("\"change\":\"CREATED\"")
			.contains("\"topic\":\"user:" + volunteer + "\"").doesNotContain("Gizli");
		String panel = body(korStream);
		assertThat(panel).contains("event:announcement.sent").contains("\"operationId\":\"" + operationId + "\"")
			.contains("\"recipientCount\":1").doesNotContain("Gizli");

		// Geri alınan işlemin olayı hiç gönderilmez.
		transactions.executeWithoutResult(status -> {
			realtime.publish(RealtimeBus.userTopic(volunteer), "inbox.changed", null, Map.of("change", "GERI_ALINDI"));
			status.setRollbackOnly();
		});
		assertThat(body(volunteerStream)).doesNotContain("GERI_ALINDI");
	}

	@Test
	void revokedRoleDropsTopicAndClosingOperationDropsSubscriptions() throws Exception {
		String operation = "operation:" + operationId;
		MvcResult korStream = open(sessions.web(kor, true), operation);
		MvcResult adminStream = open(sessions.web(admin, true), operation);
		mvc.perform(delete("/api/v1/role-assignments/{id}", korAssignment).with(authenticate(sessions.web(admin, true))))
			.andExpect(status().is2xxSuccessful());
		assertThat(body(korStream)).contains("event:subscription.revoked").contains("\"reason\":\"PERMISSION_REVOKED\"")
			.contains("\"topic\":\"" + operation + "\"");
		assertThat(body(adminStream)).doesNotContain("subscription.revoked");
		refuse(sessions.web(kor, true), operation, 403, "FORBIDDEN");   // sonraki bağlantı yeniden denetlenir

		realtime.publish(RealtimeBus.operationTopic(operationId), "operation.state.changed", operationId, Map.of("state", "X"));
		assertThat(body(korStream)).doesNotContain("operation.state.changed");

		mvc.perform(post("/api/v1/operations/{id}/state", operationId).with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"targetState\": \"COMPLETED\"}"))
			.andExpect(status().isOk());
		assertThat(body(adminStream)).contains("\"state\":\"COMPLETED\"").contains("\"reason\":\"OPERATION_CLOSED\"");
	}

	@Test
	void sessionRevocationClosesTheStream() throws Exception {
		String token = sessions.web(kor, true);
		MvcResult stream = open(token, null);
		String other = sessions.web(kor, true);
		MvcResult otherStream = open(other, null);
		mvc.perform(post("/api/v1/auth/logout").with(authenticate(token))).andExpect(status().is2xxSuccessful());
		assertThat(body(stream)).contains("event:session.revoked");
		assertThat(body(otherStream)).as("yalnızca çıkış yapılan oturum").doesNotContain("session.revoked");
		mvc.perform(get("/api/v1/stream").with(authenticate(token))).andExpect(status().isUnauthorized());

		mvc.perform(post("/api/v1/users/{id}/revoke-sessions", kor).with(authenticate(sessions.web(admin, true))))
			.andExpect(status().is2xxSuccessful());
		assertThat(body(otherStream)).contains("event:session.revoked");
	}

	@Test
	void lastEventIdReplaysMissedEventsAndUnknownIdTriggersResync() throws Exception {
		String token = sessions.web(kor, true);
		MvcResult first = open(token, null);
		realtime.publish(RealtimeBus.userTopic(kor), "inbox.changed", null, Map.of("change", "BIR"));
		realtime.publish(RealtimeBus.userTopic(kor), "inbox.changed", null, Map.of("change", "IKI"));
		realtime.publish(RealtimeBus.userTopic(admin), "inbox.changed", null, Map.of("change", "BASKASI"));
		String content = body(first);
		assertThat(content).contains("BIR").contains("IKI").doesNotContain("BASKASI");
		Matcher id = Pattern.compile("id:(\\d+-\\d{6})\\nevent:inbox.changed\\ndata:\\{[^\\n]*BIR").matcher(content);
		assertThat(id.find()).isTrue();

		MvcResult header = mvc.perform(get("/api/v1/stream").with(authenticate(token)).header("Last-Event-ID", id.group(1))
			.param("lastEventId", "0-000000")).andExpect(request().asyncStarted()).andReturn();
		assertThat(body(header)).contains("IKI").doesNotContain("BIR").doesNotContain("resync");

		MvcResult unknown = mvc.perform(get("/api/v1/stream").with(authenticate(token)).param("lastEventId", "1-000001"))
			.andExpect(request().asyncStarted()).andReturn();
		assertThat(body(unknown)).contains("event:resync").contains("\"topics\":[\"user:" + kor + "\"]").doesNotContain("IKI");
	}

	MvcResult open(String token, String topics) throws Exception {
		var builder = get("/api/v1/stream").with(authenticate(token)).accept(MediaType.TEXT_EVENT_STREAM);
		if (topics != null) {
			builder.param("topics", topics);
		}
		return mvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
	}

	void refuse(String token, String topics, int status, String code) throws Exception {
		mvc.perform(get("/api/v1/stream").param("topics", topics).with(authenticate(token)).accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
	}

	static String body(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("akis-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				name + " Kişi", name, null));
	}

	UUID role(UUID userId, String role, String scopeType, UUID scopeId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(id, userId, role, scopeType, scopeId).update();
		return id;
	}

}
