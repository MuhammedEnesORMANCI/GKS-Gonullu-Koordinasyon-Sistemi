package org.gks.backend.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR-022 uçtan uca (sahte FCM): cihaz kaydı (ID-26) oturuma bağlanır; acil alarm yüksek, duyuru normal öncelikli
 * data mesajıyla gider ve SSE ile ortak tekilleştirme anahtarı ({@code notificationId}) ile gelen kutusu kaydını taşır;
 * FCM'nin geçersiz dediği belirteç pasifleşir; gönderim hatası alarmı geri almaz; çıkışta cihaz pasifleşir.
 */
@Import({ TestcontainersConfiguration.class, PushFlowTests.FakeFcm.class })
@SpringBootTest(properties = { "gks.notifications.worker.critical-delay=PT1H", "gks.notifications.worker.standard-delay=PT1H",
		"gks.alerts.reminder-interval=PT1H" })
@AutoConfigureMockMvc
class PushFlowTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
	static final List<PushGateway.Message> SENT = new CopyOnWriteArrayList<>();
	static final AtomicReference<PushGateway.Result> NEXT = new AtomicReference<>();

	@TestConfiguration
	static class FakeFcm {
		@Bean
		@Primary
		PushGateway fakeFcm() {
			return new PushGateway() {
				@Override
				public boolean enabled() {
					return true;
				}

				@Override
				public Result send(Message message) {
					SENT.add(message);
					return NEXT.get();
				}
			};
		}
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	DeliveryWorker worker;

	TestSessions sessions;
	UUID admin;
	UUID volunteer;
	UUID operationId;
	String volunteerToken;
	String installation;

	@BeforeEach
	void setUp() throws Exception {
		SENT.clear();
		NEXT.set(PushGateway.Result.sent("projects/x/messages/1"));
		jdbc.sql("update notifications.deliveries set status = 'FAILED', failed_at = now() where status = 'QUEUED'").update();
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = register("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		volunteer = register("Gönüllü");
		operationId = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel', 'ACTIVE', now(), ?)""").params(operationId, ORGANIZATION_ID, admin).update();
		role(volunteer, "GON", "OPERATION", operationId);
		volunteerToken = sessions.android(volunteer);
		installation = "kurulum-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void deviceRegistrationIsAndroidOnlyAndBoundToSession() throws Exception {
		registerDevice("fcm-1").andExpect(status().isCreated());
		registerDevice("fcm-2").andExpect(status().isOk());   // belirteç yenilendi, aynı kayıt
		assertThat(jdbc.sql("select count(*) from identity.devices where user_id = ? and active").param(volunteer)
			.query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select fcm_token from identity.devices where user_id = ?").param(volunteer).query(String.class).single())
			.isEqualTo("fcm-2");
		mvc.perform(put("/api/v1/devices/{i}", installation).with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\": \"x\", \"appVersion\": \"1\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/devices/{i}", installation).with(authenticate(volunteerToken))
			.contentType(MediaType.APPLICATION_JSON).content("{\"appVersion\": \"1\"}")).andExpect(status().isBadRequest());

		mvc.perform(post("/api/v1/auth/logout").with(authenticate(volunteerToken))).andExpect(status().is2xxSuccessful());
		assertThat(jdbc.sql("select deactivation_reason from identity.devices where user_id = ?").param(volunteer)
			.query(String.class).single()).isEqualTo("LOGOUT");
	}

	@Test
	void alertGoesAsHighPriorityDataAnnouncementAsNormalWithDedupKeys() throws Exception {
		registerDevice("fcm-1").andExpect(status().isCreated());
		String alertId = sendAlert();
		worker.drain("CRITICAL");
		PushGateway.Message alert = SENT.stream().filter(m -> "ALERT".equals(m.type())).findFirst().orElseThrow();
		assertThat(alert.priority()).isEqualTo(PushGateway.Priority.HIGH);
		assertThat(alert.kind()).isEqualTo("ALERT_SENT");
		assertThat(alert.sourceId().toString()).isEqualTo(alertId);
		assertThat(alert.token()).isEqualTo("fcm-1");
		String inbox = mvc.perform(get("/api/v1/me/notifications").with(authenticate(volunteerToken)))
			.andReturn().getResponse().getContentAsString();
		assertThat(alert.inboxItemId().toString()).isEqualTo(JsonPath.read(inbox, "$.items[0].id"));
		assertThat(alert.notificationId().toString()).as("SSE/gelen kutusu ile ortak tekilleştirme anahtarı")
			.isEqualTo(JsonPath.read(inbox, "$.items[0].notificationId"));

		mvc.perform(post("/api/v1/announcements").with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"operationId\": \"%s\", \"title\": \"Toplantı\", \"body\": \"Saat 15\"}".formatted(operationId)))
			.andExpect(status().isAccepted());
		worker.drain("STANDARD");
		PushGateway.Message announcement = SENT.stream().filter(m -> "ANNOUNCEMENT".equals(m.type())).findFirst().orElseThrow();
		assertThat(announcement.priority()).isEqualTo(PushGateway.Priority.NORMAL);
		assertThat(FcmPushGateway.data(announcement)).containsEntry("title", "Toplantı").containsEntry("body", "Saat 15")
			.containsKeys("notificationId", "inboxItemId").doesNotContainKey("token");

		mvc.perform(post("/api/v1/alerts/{id}/cancel", alertId).with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());
		worker.drain("CRITICAL");
		assertThat(SENT).anySatisfy(m -> {
			assertThat(m.kind()).isEqualTo("ALERT_CANCELLED");
			assertThat(m.sourceId().toString()).isEqualTo(alertId);
			assertThat(m.collapseKey()).isEqualTo("alert:" + alertId);
		});
	}

	@Test
	void invalidTokenIsDeactivatedAndFailureDoesNotRollBackTheAlert() throws Exception {
		registerDevice("fcm-gecersiz").andExpect(status().isCreated());
		NEXT.set(PushGateway.Result.invalidToken("UNREGISTERED"));
		String alertId = sendAlert();
		worker.drain("CRITICAL");

		assertThat(jdbc.sql("select active::text || ':' || deactivation_reason from identity.devices where user_id = ?")
			.param(volunteer).query(String.class).single()).isEqualTo("false:FCM_UNREGISTERED");
		assertThat(jdbc.sql("""
				select d.status || ':' || d.error_code from notifications.deliveries d join notifications.notifications n
				  on n.id = d.notification_id where d.channel = 'PUSH' and n.source_id = ?""")
			.param(UUID.fromString(alertId)).query(String.class).single()).isEqualTo("FAILED:UNREGISTERED");
		mvc.perform(get("/api/v1/me/alerts").with(authenticate(volunteerToken))).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].id").value(alertId));
	}

	private String sendAlert() throws Exception {
		String preview = mvc.perform(post("/api/v1/operations/{id}/alerts", operationId).with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\": \"Tahliye\", \"targets\": {\"userIds\": [\"%s\"]}}".formatted(volunteer)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String alertId = JsonPath.read(preview, "$.alertId");
		mvc.perform(post("/api/v1/alerts/{id}/send", alertId).with(authenticate(sessions.web(admin, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\": 1, \"audienceHash\": \"%s\"}".formatted((String) JsonPath.read(preview, "$.audienceHash"))))
			.andExpect(status().isAccepted());
		return alertId;
	}

	private org.springframework.test.web.servlet.ResultActions registerDevice(String token) throws Exception {
		return mvc.perform(put("/api/v1/devices/{i}", installation).with(authenticate(volunteerToken))
			.contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\": \"%s\", \"appVersion\": \"1.0.0\"}".formatted(token)));
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("push-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				name + " Kişi", name, null));
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

}
