package org.gks.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.gks.backend.RealtimeBus;
import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-024 mesajlaşma uçtan uca (gerçek yetki kararları): kanal erişimi üyelikten anlık hesaplanır, yalnızca
 * yöneticiler modu, silme süresi, moderasyon ve denetimi, SSE olayı, içeriksiz push, tekilleştirme, kapalı operasyon,
 * oran sınırı ve sistem mesajı.
 */
@Import({ TestcontainersConfiguration.class, MessagingApiTests.FakePush.class })
@SpringBootTest(properties = { "gks.messaging.rate-limit-per-minute=8", "gks.notifications.worker.critical-delay=PT1H",
		"gks.notifications.worker.standard-delay=PT1H" })
@AutoConfigureMockMvc
class MessagingApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	/** Push etkin (sağlayıcı sahte): teslim satırları oluşur, ağa çıkılmaz. */
	@TestConfiguration
	static class FakePush {
		@Bean
		@Primary
		org.gks.backend.notifications.PushGatewayForTests push() {
			return new org.gks.backend.notifications.PushGatewayForTests();
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	SystemMessages systemMessages;

	@Autowired
	TransactionTemplate tx;

	@MockitoSpyBean
	RealtimeBus realtime;

	TestSessions sessions;
	UUID admin;
	UUID coordinator;
	UUID leader;
	UUID member;
	UUID volunteer;
	UUID operationId;
	UUID otherOperation;
	UUID groupId;
	UUID otherGroup;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = user("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		coordinator = user("Koordinatör");
		operationId = operation("ACTIVE");
		otherOperation = operation("ACTIVE");
		role(coordinator, "KOR", "OPERATION", operationId);
		leader = participant("Lider");
		member = participant("Üye");
		volunteer = participant("Gönüllü");
		groupId = group(operationId, "Arama ekibi");
		otherGroup = group(operationId, "Lojistik");
		membership(groupId, leader, "LEADER");
		membership(groupId, member, "MEMBER");
		membership(otherGroup, volunteer, "MEMBER");
	}

	@Test
	void channelsFollowRolesAndMembershipAndRemovalRevokesImmediately() throws Exception {
		String memberToken = sessions.android(member);
		List<String> kinds = kinds(memberToken);
		assertThat(kinds).containsExactlyInAnyOrder("OPERATION", "GROUP:Arama ekibi");

		assertThat(kinds(sessions.android(leader))).containsExactlyInAnyOrder("OPERATION", "COORDINATION", "GROUP:Arama ekibi");
		assertThat(kinds(sessions.web(coordinator, true))).containsExactlyInAnyOrder("OPERATION", "COORDINATION",
				"GROUP:Arama ekibi", "GROUP:Lojistik");
		// KOR yalnızca kendi operasyonunun kanallarını görür; ADM tümünü.
		String adminChannels = mvc.perform(get("/api/v1/channels").with(authenticate(sessions.web(admin, true))))
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(adminChannels, "$[*].operationId")).contains(operationId.toString(), otherOperation.toString());

		UUID group = channel(memberToken, "GROUP");
		UUID coordination = channel(sessions.web(coordinator, true), "COORDINATION");
		// Üye olmayan: okuyamaz ve yazamaz (404; kanalın varlığı sızmaz).
		mvc.perform(get("/api/v1/channels/{c}/messages", coordination).with(authenticate(memberToken))).andExpect(status().isNotFound());
		send(sessions.android(volunteer), group, "merhaba").andExpect(status().isNotFound());

		send(memberToken, group, "Toplanma noktasına geliyorum").andExpect(status().isCreated());
		// Gruptan çıkarılan erişimi anında kaybeder.
		jdbc.sql("update groups.group_memberships set status = 'REMOVED', ended_at = now(), ended_by = ?, end_reason = 'REMOVED_BY_COORDINATOR' where group_id = ? and user_id = ?")
			.params(coordinator, groupId, member).update();
		mvc.perform(get("/api/v1/channels/{c}/messages", group).with(authenticate(memberToken))).andExpect(status().isNotFound());
		send(memberToken, group, "tekrar").andExpect(status().isNotFound());
		assertThat(kinds(memberToken)).containsExactly("OPERATION");
		// Operasyondan çıkarılan operasyon kanalını da kaybeder.
		jdbc.sql("update access.role_assignments set revoked_at = now() where user_id = ? and role = 'GON'").param(member).update();
		assertThat(kinds(memberToken)).isEmpty();
	}

	@Test
	void adminsOnlyModeIsCoordinatorControlledAndAudited() throws Exception {
		String kor = sessions.web(coordinator, true);
		UUID op = channel(kor, "OPERATION");
		String gon = sessions.android(volunteer);
		send(gon, op, "Herkese merhaba").andExpect(status().isCreated());
		mvc.perform(patch("/api/v1/channels/{c}", op).with(authenticate(gon)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"adminsOnly\":true}")).andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/channels/{c}", op).with(authenticate(kor)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"adminsOnly\":true}")).andExpect(status().isNoContent());
		send(gon, op, "Yazamam").andExpect(status().isForbidden());
		send(kor, op, "Duyuru: 10:00 brifing").andExpect(status().isCreated());
		assertThat(jdbc.sql("select count(*) from audit.audit_entries where action = 'CHANNEL_SETTINGS_CHANGED' and resource_id = ?")
			.param(op).query(Integer.class).single()).isEqualTo(1);
		mvc.perform(get("/api/v1/channels").with(authenticate(gon)))
			.andExpect(jsonPath("$[?(@.kind=='OPERATION')].canWrite").value(org.hamcrest.Matchers.contains(false)))
			.andExpect(jsonPath("$[?(@.kind=='OPERATION')].adminsOnly").value(org.hamcrest.Matchers.contains(true)));
	}

	@Test
	void ownDeleteWithinWindowAndModerationHideIsAudited() throws Exception {
		String memberToken = sessions.android(member);
		UUID group = channel(memberToken, "GROUP");
		UUID first = id(send(memberToken, group, "Yanlış mesaj"));
		UUID second = id(send(memberToken, group, "Uygunsuz içerik"));
		// Başkasının mesajını silemez.
		mvc.perform(delete("/api/v1/channels/{c}/messages/{m}", group, first).with(authenticate(sessions.android(leader))))
			.andExpect(status().isForbidden());
		mvc.perform(delete("/api/v1/channels/{c}/messages/{m}", group, first).with(authenticate(memberToken)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DELETED")).andExpect(jsonPath("$.body").doesNotExist());
		// Süre dolunca silinemez.
		jdbc.sql("update messaging.messages set created_at = now() - interval '6 minutes' where id = ?").param(second).update();
		mvc.perform(delete("/api/v1/channels/{c}/messages/{m}", group, second).with(authenticate(memberToken)))
			.andExpect(status().isConflict());
		// Moderasyon: lider değil, KOR/ADM gizler.
		mvc.perform(post("/api/v1/channels/{c}/messages/{m}/hide", group, second).with(authenticate(sessions.android(leader))))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/channels/{c}/messages/{m}/hide", group, second).with(authenticate(sessions.web(coordinator, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("HIDDEN")).andExpect(jsonPath("$.body").doesNotExist());
		mvc.perform(get("/api/v1/channels/{c}/messages", group).with(authenticate(memberToken)))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].state").value("HIDDEN"))
			.andExpect(jsonPath("$.items[1].state").value("DELETED"));
		String details = jdbc.sql("select details::text from audit.audit_entries where action = 'MESSAGE_HIDDEN' and resource_id = ?")
			.param(second).query(String.class).single();
		assertThat(details).doesNotContain("Uygunsuz");
	}

	@Test
	void sendPublishesSseAndCreatesContentFreePushExceptForSenderAndMutedUsers() throws Exception {
		device(leader);
		device(member);
		device(coordinator);
		String memberToken = sessions.android(member);
		UUID group = channel(memberToken, "GROUP");
		mvc.perform(put("/api/v1/channels/{c}/mute", group).with(authenticate(sessions.android(leader)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"muted\":true}")).andExpect(status().isNoContent());
		UUID message = id(send(memberToken, group, "Gizli içerik: 3. kat"));

		verify(realtime, atLeastOnce()).publish(eq(RealtimeBus.userTopic(leader)), eq("message.created"), eq(operationId), anyMap());
		verify(realtime, never()).publish(eq(RealtimeBus.userTopic(volunteer)), eq("message.created"), eq(operationId), anyMap());

		Map<String, Object> n = jdbc.sql("""
				select category, kind, title, body, source_type, source_id::text as source, audience_expr->>'ref' as ref, id::text as nid
				  from notifications.notifications where idempotency_key = ?""").param("message:" + message).query().singleRow();
		assertThat(n).containsEntry("category", "MESSAGE").containsEntry("title", "Yeni mesaj").containsEntry("source_type", "CHANNEL")
			.containsEntry("source", group.toString()).containsEntry("ref", message.toString());
		assertThat(n.get("body")).isNull();
		List<UUID> pushed = jdbc.sql("select user_id from notifications.deliveries where notification_id = ?::uuid and channel = 'PUSH'")
			.param(n.get("nid")).query(UUID.class).list();
		assertThat(pushed).containsExactly(coordinator);   // gönderen ve sessize alan (lider) hariç
		assertThat(jdbc.sql("select count(*) from notifications.deliveries where notification_id = ?::uuid and channel = 'IN_APP'")
			.param(n.get("nid")).query(Integer.class).single()).isZero();   // gelen kutusuna düşmez
		// Okunmamış: lider için 1, gönderen için 0; okundu işareti sayacı sıfırlar.
		String leaderToken = sessions.android(leader);
		mvc.perform(get("/api/v1/channels").with(authenticate(leaderToken)))
			.andExpect(jsonPath("$[?(@.kind=='GROUP')].unread").value(org.hamcrest.Matchers.contains(1)))
			.andExpect(jsonPath("$[?(@.kind=='GROUP')].muted").value(org.hamcrest.Matchers.contains(true)));
		mvc.perform(post("/api/v1/channels/{c}/read", group).with(authenticate(leaderToken)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"messageId\":\"" + message + "\"}")).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/channels").with(authenticate(leaderToken)))
			.andExpect(jsonPath("$[?(@.kind=='GROUP')].unread").value(org.hamcrest.Matchers.contains(0)));
	}

	@Test
	void offlineRetryWithSameKeyIsDeduplicatedAndValidationRateLimit() throws Exception {
		String gon = sessions.android(volunteer);
		UUID op = channel(gon, "OPERATION");
		String key = UUID.randomUUID().toString();
		UUID first = id(mvc.perform(post("/api/v1/channels/{c}/messages", op).with(authenticate(gon)).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Su lazım\"}")).andExpect(status().isCreated()));
		UUID again = id(mvc.perform(post("/api/v1/channels/{c}/messages", op).with(authenticate(gon)).header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Su lazım\"}")).andExpect(status().isCreated()));
		assertThat(again).isEqualTo(first);
		assertThat(jdbc.sql("select count(*) from messaging.messages where client_key = ?").param(key).query(Integer.class).single()).isEqualTo(1);
		mvc.perform(post("/api/v1/channels/{c}/messages", op).with(authenticate(gon)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"anahtarsız\"}")).andExpect(status().isBadRequest());
		send(gon, op, "   ").andExpect(status().isBadRequest());
		send(gon, op, "x".repeat(2001)).andExpect(status().isBadRequest());
		for (int i = 0; i < 7; i++) {
			send(gon, op, "mesaj " + i).andExpect(status().isCreated());
		}
		send(gon, op, "fazla").andExpect(status().isTooManyRequests());
	}

	@Test
	void closedOperationIsReadOnlyAndSystemMessageReachesChannel() throws Exception {
		String kor = sessions.web(coordinator, true);
		UUID op = channel(kor, "OPERATION");
		tx.executeWithoutResult(s -> systemMessages.post(operationId, null, "ACİL ALARM: Tahliye edin"));
		mvc.perform(get("/api/v1/channels/{c}/messages", op).with(authenticate(sessions.android(volunteer))))
			.andExpect(jsonPath("$.items[0].kind").value("SYSTEM"))
			.andExpect(jsonPath("$.items[0].body").value("ACİL ALARM: Tahliye edin"))
			.andExpect(jsonPath("$.items[0].sender").doesNotExist());
		jdbc.sql("update organization.operations set state = 'COMPLETED', closed_at = now() where id = ?").param(operationId).update();
		send(kor, op, "Kapandı mı?").andExpect(status().isConflict());
		mvc.perform(get("/api/v1/channels/{c}/messages", op).with(authenticate(kor))).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- yardımcılar

	ResultActions send(String token, UUID channel, String body) throws Exception {
		return mvc.perform(post("/api/v1/channels/{c}/messages", channel).with(authenticate(token))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"" + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"));
	}

	UUID id(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	List<String> kinds(String token) throws Exception {
		String json = mvc.perform(get("/api/v1/channels").with(authenticate(token))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> list = JsonPath.read(json, "$[?(@.operationId=='" + operationId + "')]");
		return list.stream().map(c -> c.get("kind") + (c.get("groupName") == null ? "" : ":" + c.get("groupName"))).toList();
	}

	UUID channel(String token, String kind) throws Exception {
		String json = mvc.perform(get("/api/v1/channels").with(authenticate(token))).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(json, "$[?(@.operationId=='" + operationId + "' && @.kind=='" + kind + "')].id");
		return UUID.fromString(ids.get(0));
	}

	UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into identity.users (id, email, password_hash, full_name, display_name) values (?, ?, '{argon2}x', ?, ?)")
			.params(id, id + "@example.org", name + " Soyadı", name).update();
		return id;
	}

	UUID participant(String name) {
		UUID id = user(name);
		role(id, "GON", "OPERATION", operationId);
		return id;
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

	UUID operation(String state) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, ?, ?, now(), ?)""").params(id, ORGANIZATION_ID, "Tatbikat " + id.toString().substring(0, 6), state, admin)
			.update();
		return id;
	}

	UUID group(UUID op, String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, ?, ?)")
			.params(id, ORGANIZATION_ID, op, name, coordinator).update();
		return id;
	}

	void membership(UUID group, UUID user, String role) {
		jdbc.sql("insert into groups.group_memberships (id, group_id, user_id, role, added_by) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), group, user, role, coordinator).update();
	}

	void device(UUID user) {
		jdbc.sql("insert into identity.devices (id, user_id, installation_id, fcm_token) values (?, ?, ?, ?)")
			.params(UUID.randomUUID(), user, "kurulum-" + UUID.randomUUID(), "belirtec-" + UUID.randomUUID()).update();
	}

}
