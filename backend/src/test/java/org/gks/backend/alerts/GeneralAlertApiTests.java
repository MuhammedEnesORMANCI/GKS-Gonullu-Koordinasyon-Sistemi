package org.gks.backend.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

/**
 * ADR-026 Genel Acil Alarm: kapsam (KOR kurum geneli ve yetkisiz operasyon/grup/kişi seçemez), tekilleştirilmiş alıcı
 * sayısı, onaysız gönderimin reddi, denetim, kanallara sistem mesajı, oran sınırı, olay yeri doğrulaması.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "gks.alerts.reminder-interval=PT1H", "gks.alerts.general.rate-limit=2" })
@AutoConfigureMockMvc
class GeneralAlertApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	TestSessions sessions;
	UUID admin;
	UUID kor;
	UUID v1;
	UUID v2;
	UUID v3;
	UUID outsider;
	UUID op;
	UUID otherOp;
	UUID group;
	UUID otherGroup;

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
		op = operation();
		otherOp = operation();
		role(kor, "KOR", "OPERATION", op);
		for (UUID v : new UUID[] { v1, v2, v3 }) {
			role(v, "GON", "OPERATION", op);
		}
		role(outsider, "GON", "OPERATION", otherOp);
		group = group(op, v2, v3);
		otherGroup = group(otherOp, outsider);
	}

	@Test
	void coordinatorCannotTargetOrganizationOrOutsideOwnOperations() throws Exception {
		String kt = sessions.web(kor, true);
		draft(kt, "{\"organization\": true}").andExpect(status().isForbidden());
		draft(kt, "{\"operationIds\": [\"%s\"]}".formatted(otherOp)).andExpect(status().isForbidden());
		draft(kt, "{\"groupIds\": [\"%s\"]}".formatted(otherGroup)).andExpect(status().isForbidden());
		draft(kt, "{\"userIds\": [\"%s\"]}".formatted(outsider)).andExpect(status().isForbidden());
		// MFA'sız oturum ve gönüllü genel alarm başlatamaz.
		draft(sessions.web(kor, false), "{\"operationIds\": [\"%s\"]}".formatted(op)).andExpect(status().isForbidden());
		draft(sessions.android(v1), "{\"operationIds\": [\"%s\"]}".formatted(op)).andExpect(status().isForbidden());
		draft(kt, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("targets"));
		assertThat(jdbc.sql("select count(*) from alerts.alerts where scope = 'GENERAL' and created_by = ?").param(kor)
			.query(Integer.class).single()).isZero();

		// Kendi operasyonunun katılımcılarını seçebilir; gönderen alıcı değildir.
		String preview = draft(kt, "{\"operationIds\": [\"%s\"], \"userIds\": [\"%s\"]}".formatted(op, v1))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<Integer>read(preview, "$.recipientCount")).isEqualTo(3);
		assertThat(JsonPath.<List<String>>read(preview, "$.recipients[*].id")).doesNotContain(kor.toString());
	}

	@Test
	void recipientsAreDeduplicatedConfirmationIsRequiredAndSendIsAuditedAndPostedToChannels() throws Exception {
		String at = sessions.web(admin, true);
		String preview = draft(at, "{\"operationIds\": [\"%s\"], \"groupIds\": [\"%s\"], \"userIds\": [\"%s\", \"%s\", \"%s\"]}"
			.formatted(op, group, v1, v2, admin), "{\"lat\": 40.98, \"lon\": 29.05}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.scope").value("GENERAL"))
			.andExpect(jsonPath("$.targets.operations[0].id").value(op.toString()))
			.andExpect(jsonPath("$.eventLocation.lat").value(40.98))
			.andReturn().getResponse().getContentAsString();
		// v1, v2, v3 (katılımcı/grup/kişi olarak birden çok kez) + KOR = 4 tekil kişi; gönderen (ADM) hariç.
		assertThat(JsonPath.<Integer>read(preview, "$.recipientCount")).isEqualTo(4);
		assertThat(JsonPath.<List<String>>read(preview, "$.recipients[*].id"))
			.containsExactlyInAnyOrder(v1.toString(), v2.toString(), v3.toString(), kor.toString());
		String alertId = JsonPath.read(preview, "$.alertId");
		String hash = JsonPath.read(preview, "$.audienceHash");

		send(admin, alertId, hash, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("confirmed"));
		send(admin, alertId, hash, false).andExpect(status().isBadRequest());
		assertThat(jdbc.sql("select count(*) from alerts.alert_recipients where alert_id = ?::uuid").param(alertId)
			.query(Integer.class).single()).isZero();

		send(admin, alertId, hash, true).andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("OPEN"))
			.andExpect(jsonPath("$.scope").value("GENERAL"));
		assertThat(jdbc.sql("select count(*) from alerts.alert_recipients where alert_id = ?::uuid").param(alertId)
			.query(Integer.class).single()).isEqualTo(4);
		// Bildirim aynı alarm bildirimi (CRITICAL); olay yeri metinde.
		assertThat(jdbc.sql("select title || '|' || body from notifications.notifications where source_id = ?::uuid and kind = 'ALERT_SENT'")
			.param(alertId).query(String.class).single()).startsWith("GENEL ACİL ALARM|").contains("Olay yeri: 40.98000, 29.05000");
		// Denetim: kim, kaç kişi, hangi hedefler.
		String details = jdbc.sql("select details::text from audit.audit_entries where action = 'ALERT_SENT' and resource_id = ?::uuid")
			.param(alertId).query(String.class).single();
		assertThat(details).contains("\"scope\": \"GENERAL\"").contains("\"recipientCount\": 4").contains(op.toString());
		assertThat(jdbc.sql("select actor_basis from audit.audit_entries where action = 'ALERT_SENT' and resource_id = ?::uuid")
			.param(alertId).query(String.class).single()).isEqualTo("ADM");
		// Alarm metni hedef operasyonun ve grubun kanalına sistem mesajı olarak yazıldı.
		List<String> kinds = jdbc.sql("""
				select c.kind from messaging.messages m join messaging.channels c on c.id = m.channel_id
				 where m.kind = 'SYSTEM' and m.body like 'GENEL ACİL ALARM:%' and c.operation_id = ? order by c.kind""")
			.param(op).query(String.class).list();
		assertThat(kinds).containsExactly("GROUP", "OPERATION");

		// Alıcı genel alarmı görür; kapanınca "Acil durum sona erdi".
		mvc.perform(get("/api/v1/me/alerts").with(authenticate(sessions.android(v3)))).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].scope").value("GENERAL")).andExpect(jsonPath("$.items[0].eventLocation.lon").value(29.05));
		mvc.perform(post("/api/v1/alerts/{id}/close", alertId).with(authenticate(at))).andExpect(status().isOk());
		assertThat(jdbc.sql("select title from notifications.notifications where source_id = ?::uuid and kind = 'ALERT_CLOSED'")
			.param(alertId).query(String.class).single()).isEqualTo("Acil durum sona erdi");
		// KOR, ADM'nin genel alarmını yönetemez; ADM listesinde görünür.
		mvc.perform(get("/api/v1/alerts/{id}/recipients", alertId).with(authenticate(sessions.web(kor, true))))
			.andExpect(status().isForbidden());
	}

	@Test
	void coordinatorSendsToOwnOperationWithKorBasisAndRateLimitApplies() throws Exception {
		String kt = sessions.web(kor, true);
		String first = sendGeneral(kt, kor);
		assertThat(jdbc.sql("select actor_basis from audit.audit_entries where action = 'ALERT_SENT' and resource_id = ?::uuid")
			.param(first).query(String.class).single()).isEqualTo("KOR");
		// KOR kendi genel alarmının takip ekranını görür ve listesinde bulur.
		mvc.perform(get("/api/v1/alerts/{id}/recipients", first).with(authenticate(kt))).andExpect(status().isOk());
		String list = mvc.perform(get("/api/v1/alerts").with(authenticate(kt))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(list, "$.items[*].id")).contains(first);
		sendGeneral(kt, kor);
		// Üçüncü gönderim (sınır 2) reddedilir.
		String preview = draft(kt, "{\"operationIds\": [\"%s\"]}".formatted(op)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		send(kor, JsonPath.read(preview, "$.alertId"), JsonPath.read(preview, "$.audienceHash"), true)
			.andExpect(status().isTooManyRequests());
	}

	@Test
	void eventLocationIsValidated() throws Exception {
		draft(sessions.web(admin, true), "{\"operationIds\": [\"%s\"]}".formatted(op), "{\"lat\": 95, \"lon\": 29}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("eventLocation.lat"));
		draft(sessions.web(admin, true), "{\"operationIds\": [\"%s\"]}".formatted(op), "{\"lat\": 40}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("eventLocation"));
		// Operasyon alarmında da isteğe bağlı olay yeri (Görev 23).
		mvc.perform(post("/api/v1/operations/{id}/alerts", op).with(authenticate(sessions.web(kor, true)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"message\": \"Artçı\", \"targets\": {\"userIds\": [\"%s\"]}, \"eventLocation\": {\"lat\": 41.0, \"lon\": 29.0}}"
				.formatted(v1)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.scope").value("OPERATION"))
			.andExpect(jsonPath("$.eventLocation.lat").value(41.0));
	}

	@Test
	void organizationWideTargetsEveryActiveAccountExceptSender() throws Exception {
		int active = jdbc.sql("select count(*) from identity.users where status = 'ACTIVE'").query(Integer.class).single();
		ResultActions result = draft(sessions.web(admin, true), "{\"organization\": true}");
		if (active - 1 <= AlertService.MAX_RECIPIENTS) {
			result.andExpect(status().isCreated()).andExpect(jsonPath("$.recipientCount").value(active - 1))
				.andExpect(jsonPath("$.targets.organization").value(true));
		}
		else {
			result.andExpect(status().isUnprocessableEntity());
		}
	}

	// ---- yardımcılar ----

	String sendGeneral(String token, UUID actor) throws Exception {
		String preview = draft(token, "{\"operationIds\": [\"%s\"]}".formatted(op)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(preview, "$.alertId");
		send(actor, id, JsonPath.read(preview, "$.audienceHash"), true).andExpect(status().isAccepted());
		return id;
	}

	/**
	 * RAPOR_7 madde 3: kişi seçicisi sunucuda arar ve sayfalar. ADM kurumdaki tüm aktif hesapları (hiçbir operasyonda
	 * olmayanlar dahil, 100+ kişi, ikinci sayfa) görür; KOR yalnızca koordine ettiği kapanmamış operasyonların
	 * katılımcı ve koordinatörlerini; devre dışı hesap ve gönderen listelenmez.
	 */
	@Test
	void personPickerSearchesAndPagesOnTheServer() throws Exception {
		String tag = "Picker" + UUID.randomUUID().toString().substring(0, 8);
		List<UUID> many = new java.util.ArrayList<>();
		for (int i = 0; i < 120; i++) {
			many.add(plainUser(tag + "-" + "%03d".formatted(i)));
		}
		UUID disabled = plainUser(tag + "-pasif");
		jdbc.sql("update identity.users set status = 'DISABLED' where id = ?").param(disabled).update();

		String at = sessions.web(admin, true);
		String first = candidates(at, "q", tag, "limit", "100").andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(100))
			.andExpect(jsonPath("$.items[0].displayName").value(tag + "-000"))
			.andExpect(jsonPath("$.items[0].email").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String cursor = JsonPath.read(first, "$.nextCursor");
		String second = candidates(at, "q", tag, "limit", "100", "cursor", cursor).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(20))
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<String>read(second, "$.items[19].id")).isEqualTo(many.get(119).toString());
		assertThat(JsonPath.<List<String>>read(second, "$.items[*].id")).doesNotContain(disabled.toString());
		candidates(at, "q", tag.toUpperCase(java.util.Locale.ROOT) + "-11").andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(10));   // -110 … -119
		candidates(at, "q", "Yönetici").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(admin.toString()))));

		// KOR: kendi (aktif + planlı) operasyonlarının kişileri; başka operasyon ve kurumdaki diğerleri yok.
		UUID planned = operation();
		jdbc.sql("update organization.operations set state = 'PLANNED', activated_at = null where id = ?").param(planned).update();
		role(kor, "KOR", "OPERATION", planned);
		UUID plannedVolunteer = plainUser(tag + "-planli");
		role(plannedVolunteer, "GON", "OPERATION", planned);
		String kt = sessions.web(kor, true);
		String own = candidates(kt, "limit", "100").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(own, "$.items[*].id"))
			.contains(v1.toString(), v2.toString(), v3.toString(), plannedVolunteer.toString())
			.doesNotContain(outsider.toString(), kor.toString(), many.get(0).toString());
		candidates(kt, "q", tag).andExpect(jsonPath("$.items.length()").value(1));   // yalnızca planlı operasyondaki
		draft(kt, "{\"userIds\": [\"%s\"]}".formatted(plannedVolunteer)).andExpect(status().isCreated());

		// Yetkisiz: gönüllü/MFA'sız; geçersiz istek.
		candidates(sessions.android(v1)).andExpect(status().isForbidden());
		candidates(sessions.web(kor, false)).andExpect(status().isForbidden());
		candidates(at, "limit", "101").andExpect(status().isBadRequest());
		candidates(at, "q", "x".repeat(101)).andExpect(status().isBadRequest());
	}

	ResultActions candidates(String token, String... params) throws Exception {
		var request = get("/api/v1/general-alerts/candidates").with(authenticate(token));
		for (int i = 0; i < params.length; i += 2) {
			request = request.param(params[i], params[i + 1]);
		}
		return mvc.perform(request);
	}

	/** Parola özeti hesaplamadan doğrudan hesap (çok sayıda kişi için). */
	UUID plainUser(String displayName) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', ?, ?)""")
			.params(id, id + "@example.org", displayName + " Soyadı", displayName).update();
		return id;
	}

	ResultActions draft(String token, String targets) throws Exception {
		return draft(token, targets, null);
	}

	ResultActions draft(String token, String targets, String location) throws Exception {
		String body = "{\"message\": \"Tatbikat: binayı boşaltın\", \"requestedAction\": \"Toplanma alanına gidin\", \"targets\": "
				+ targets + (location == null ? "" : ", \"eventLocation\": " + location) + "}";
		return mvc.perform(post("/api/v1/general-alerts").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	ResultActions send(UUID actor, String alertId, String hash, Boolean confirmed) throws Exception {
		return mvc.perform(post("/api/v1/alerts/{id}/send", alertId).with(authenticate(sessions.web(actor, true)))
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\": 1, \"audienceHash\": \"%s\"%s}".formatted(hash,
					confirmed == null ? "" : ", \"confirmed\": " + confirmed)));
	}

	UUID operation() {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, ?, 'ACTIVE', now(), ?)""").params(id, ORGANIZATION_ID, "Tatbikat " + id.toString().substring(0, 6), admin)
			.update();
		return id;
	}

	UUID group(UUID operation, UUID... members) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, 'Arama ekibi', ?)")
			.params(id, ORGANIZATION_ID, operation, admin).update();
		for (UUID m : members) {
			jdbc.sql("insert into groups.group_memberships (id, group_id, user_id, role, added_by) values (?, ?, ?, 'MEMBER', ?)")
				.params(UUID.randomUUID(), id, m, admin).update();
		}
		return id;
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("genel-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				name + " Kişi", name, null));
	}

}
