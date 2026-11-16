package org.gks.backend.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
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

/** ADR-025 Operasyon Haritası: kapsam (KOR yalnızca kendi operasyonu), bölge doğrulaması, açık alarm özeti, gizlilik. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OperationMapApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	TestSessions sessions;
	UUID admin;
	UUID coordinator;
	UUID volunteer;
	UUID mine;
	UUID other;
	UUID planned;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = user("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		coordinator = user("Koordinatör");
		mine = operation("ACTIVE");
		other = operation("ACTIVE");
		planned = operation("PLANNED");
		role(coordinator, "KOR", "OPERATION", mine);
		volunteer = user("Gönüllü");
		role(volunteer, "GON", "OPERATION", mine);
	}

	@Test
	void coordinatorSeesOnlyOwnOperationsAndAdminSeesAllWithPlannedFilter() throws Exception {
		List<String> korIds = ids(sessions.web(coordinator, true), "");
		assertThat(korIds).contains(mine.toString()).doesNotContain(other.toString(), planned.toString());

		List<String> admIds = ids(sessions.web(admin, true), "");
		assertThat(admIds).contains(mine.toString(), other.toString()).doesNotContain(planned.toString());
		assertThat(ids(sessions.web(admin, true), "?state=ACTIVE&state=PLANNED")).contains(planned.toString());
		// Tek operasyon (operasyon sayfası): KOR başka operasyonu göremez.
		mvc.perform(get("/api/v1/map/operations/{id}", mine).with(authenticate(sessions.web(coordinator, true))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(mine.toString()));
		mvc.perform(get("/api/v1/map/operations/{id}", other).with(authenticate(sessions.web(coordinator, true))))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/map/operations/{id}", mine).with(authenticate(sessions.android(volunteer))))
			.andExpect(status().isForbidden());
		// Kapalı operasyon haritada gösterilmez.
		mvc.perform(get("/api/v1/map/operations?state=COMPLETED").with(authenticate(sessions.web(admin, true))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void volunteersAndAnonymousCallersGetNoMapData() throws Exception {
		mvc.perform(get("/api/v1/map/operations").with(authenticate(sessions.android(volunteer))))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/map/operations")).andExpect(status().isUnauthorized());
		// MFA'sız oturumdaki KOR da göremez (ADR-014/015).
		mvc.perform(get("/api/v1/map/operations").with(authenticate(sessions.web(coordinator, false))))
			.andExpect(status().isForbidden());
	}

	@Test
	void areaIsValidatedStoredAndShownWithMeetingPointsAndOpenAlert() throws Exception {
		String kor = sessions.web(coordinator, true);
		area(kor, mine, "{\"area\":{\"lat\":91,\"lon\":29}}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("area.lat"));
		area(kor, mine, "{\"area\":{\"lat\":40.9,\"lon\":29.1,\"radiusM\":10}}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("area.radiusM"));
		area(kor, mine, "{\"area\":{\"lat\":40.9}}").andExpect(status().isBadRequest());
		area(kor, mine, "{\"area\":{\"lat\":40.9,\"lon\":\"x\"}}").andExpect(status().isBadRequest());
		// KOR başka operasyonun bölgesini değiştiremez.
		area(kor, other, "{\"area\":{\"lat\":40.9,\"lon\":29.1}}").andExpect(status().isForbidden());

		area(kor, mine, "{\"area\":{\"lat\":40.98,\"lon\":29.05,\"radiusM\":1500}}").andExpect(status().isOk())
			.andExpect(jsonPath("$.area.lat").value(40.98)).andExpect(jsonPath("$.area.radiusM").value(1500));

		UUID group = UUID.randomUUID();
		jdbc.sql("""
				insert into groups.groups (id, organization_id, operation_id, name, created_by, mp_address, mp_lat, mp_lon,
				                           mp_updated_by, mp_updated_at)
				values (?, ?, ?, 'Arama ekibi', ?, 'Okul bahçesi', 40.981, 29.051, ?, now())""")
			.params(group, ORGANIZATION_ID, mine, coordinator, coordinator).update();
		UUID alert = UUID.randomUUID();
		jdbc.sql("""
				insert into alerts.alerts (id, operation_id, message, state, target_expr, created_by, sent_by, sent_at,
				                           recipient_count)
				values (?, ?, 'Tahliye', 'OPEN', '{}'::jsonb, ?, ?, now(), 1)""")
			.params(alert, mine, coordinator, coordinator).update();
		jdbc.sql("insert into alerts.alert_recipients (id, alert_id, user_id, state) values (?, ?, ?, 'SENT')")
			.params(UUID.randomUUID(), alert, volunteer).update();

		String body = mvc.perform(get("/api/v1/map/operations").with(authenticate(kor))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String op = "$[?(@.id == '" + mine + "')]";
		assertThat(JsonPath.<List<Double>>read(body, op + ".area.lat")).containsExactly(40.98);
		assertThat(JsonPath.<List<Integer>>read(body, op + ".participantCount")).containsExactly(1);
		assertThat(JsonPath.<List<String>>read(body, op + ".meetingPoints[*].groupName")).containsExactly("Arama ekibi");
		assertThat(JsonPath.<List<String>>read(body, op + ".openAlert.id")).containsExactly(alert.toString());
		assertThat(JsonPath.<List<Integer>>read(body, op + ".openAlert.counts.pending")).containsExactly(1);
		// Harita özeti kişi konumu ve alarm metni taşımaz.
		assertThat(body).doesNotContain("Tahliye").doesNotContain(volunteer.toString());

		area(kor, mine, "{\"area\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.area").doesNotExist());
	}

	private ResultActions area(String token, UUID op, String json) throws Exception {
		return mvc.perform(patch("/api/v1/operations/{id}", op).with(authenticate(token))
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private List<String> ids(String token, String query) throws Exception {
		String body = mvc.perform(get("/api/v1/map/operations" + query).with(authenticate(token)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	UUID user(String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into identity.users (id, email, password_hash, full_name, display_name) values (?, ?, '{argon2}x', ?, ?)")
			.params(id, id + "@example.org", name + " Soyadı", name).update();
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
				values (?, ?, ?, ?, case when ? = 'PLANNED' then null else now() end, ?)""")
			.params(id, ORGANIZATION_ID, "Tatbikat " + id.toString().substring(0, 6), state, state, admin).update();
		return id;
	}

}
