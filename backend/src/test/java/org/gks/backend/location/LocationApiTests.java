package org.gks.backend.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
 * Konum (§10, K-15 – K-16, ADR-019, ADR-020): paylaşım yalnızca aktif operasyonda ve açık başlatmayla, durdurulabilir;
 * konumu KOR ve ADM görür; her görüntüleme ayrı denetim kaydı (koordinatsız); kapanışta paylaşım durur ve son konum
 * saklama süresi dolunca silinir.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "gks.location.purge-interval=PT1H")
@AutoConfigureMockMvc
class LocationApiTests {

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
	LocationService service;

	TestSessions sessions;
	UUID admin;
	UUID kor;
	UUID v1;
	UUID v2;
	UUID v1Role;
	UUID operationId;

	@BeforeEach
	void setUp() {
		sessions = new TestSessions(jdbc, jwtEncoder);
		admin = register("Yönetici");
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		kor = register("Koordinatör");
		v1 = register("Ayşe");
		v2 = register("Burak");
		operationId = operation("ACTIVE");
		role(kor, "KOR", "OPERATION", operationId);
		v1Role = role(v1, "GON", "OPERATION", operationId);
		role(v2, "GON", "OPERATION", operationId);
	}

	@Test
	void sharingRequiresActiveOperationExplicitStartAndCanBeStopped() throws Exception {
		measure(v1, Instant.now()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCATION_SHARING_STOPPED"));
		UUID planned = operation("PLANNED");
		role(v1, "GON", "OPERATION", planned);
		mvc.perform(post("/api/v1/operations/{id}/location-sharing", planned).with(authenticate(sessions.android(v1))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_NOT_ACTIVE"));
		mvc.perform(post("/api/v1/operations/{id}/location-sharing", operationId).with(authenticate(sessions.android(kor))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_A_PARTICIPANT"));
		mvc.perform(post("/api/v1/operations/{id}/location-sharing", operationId).with(authenticate(sessions.web(v1, false))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));

		start(v1).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.operationName").value("Sel müdahalesi"));
		start(v1).andExpect(status().isOk());   // doğal idempotent; denetim yalnızca durum değişiminde
		measure(v1, Instant.now()).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/me/location-sharing").with(authenticate(sessions.android(v1))))
			.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.operationId == '%s')].lastSentAt".formatted(operationId)).isNotEmpty());
		mvc.perform(delete("/api/v1/operations/{id}/location-sharing", operationId).with(authenticate(sessions.android(v1))))
			.andExpect(status().isNoContent());
		measure(v1, Instant.now()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LOCATION_SHARING_STOPPED"));
		mvc.perform(post("/api/v1/operations/{id}/locations", operationId).with(authenticate(sessions.android(v1)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"lat\": 95, \"lon\": 29, \"accuracy\": 5}"))
			.andExpect(status().isBadRequest());

		assertThat(count("LOCATION_SHARING_STARTED")).isEqualTo(1);
		assertThat(count("LOCATION_SHARING_STOPPED")).isEqualTo(1);
		assertThat(jdbc.sql("select details::text from audit.audit_entries where action = 'LOCATION_SHARING_STOPPED' and operation_id = ?")
			.param(operationId).query(String.class).single()).doesNotContain("41.");
	}

	@Test
	void coordinatorAndAdminSeeLocationsAndEveryViewIsAudited() throws Exception {
		start(v1).andExpect(status().isOk());
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		measure(v1, now).andExpect(status().isNoContent());
		measure(v1, now.minus(1, ChronoUnit.MINUTES)).andExpect(status().isNoContent());   // sıra dışı: üzerine yazmaz

		for (int i = 0; i < 3; i++) {
			list(kor).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].user.id").value(v1.toString()))
				.andExpect(jsonPath("$.items[0].lat").value(41.0082))
				.andExpect(jsonPath("$.items[0].measuredAt").value(now.toString()))
				.andExpect(jsonPath("$.items[0].freshness").value("LIVE"))
				.andExpect(jsonPath("$.notSharing[0].user.id").value(v2.toString()))
				.andExpect(jsonPath("$.notSharing[0].state").value("OFF"));
		}
		list(admin).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].user.id").value(v1.toString()));   // ADR-020
		list(v1, sessions.android(v1)).andExpect(status().isForbidden());
		list(v1, sessions.web(v1, false)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/operations/{id}/locations", operationId).param("freshness", "LIVE")
			.with(authenticate(sessions.web(kor, true)))).andExpect(status().isOk());

		assertThat(jdbc.sql("select count(*) from audit.audit_entries where action = 'LOCATION_VIEWED' and actor_id = ?")
			.param(kor).query(Integer.class).single()).as("her görüntüleme ayrı kayıt").isEqualTo(4);
		assertThat(jdbc.sql("select actor_basis from audit.audit_entries where action = 'LOCATION_VIEWED' and actor_id = ?")
			.param(admin).query(String.class).single()).isEqualTo("ADM");
		String details = jdbc.sql("""
				select string_agg(details::text, ' ') from audit.audit_entries where action = 'LOCATION_VIEWED' and operation_id = ?""")
			.param(operationId).query(String.class).single();
		assertThat(details).contains("\"resultCount\": 1").contains("freshness").doesNotContain("41.0082").doesNotContain("28.97")
			.doesNotContain(v1.toString());

		jdbc.sql("update location.last_locations set measured_at = now() - interval '20 minutes', received_at = now() - interval '20 minutes' where user_id = ?")
			.param(v1).update();
		list(kor).andExpect(jsonPath("$.items[0].freshness").value("LAST_KNOWN"));
	}

	@Test
	void locationStreamIsForCoordinatorAndAdmin() throws Exception {
		mvc.perform(get("/api/v1/stream").param("topics", "location:" + operationId).with(authenticate(sessions.web(kor, true)))
			.accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted());
		mvc.perform(get("/api/v1/stream").param("topics", "location:" + operationId).with(authenticate(sessions.web(admin, true)))
			.accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted());
		mvc.perform(get("/api/v1/stream").param("topics", "location:" + operationId).with(authenticate(sessions.android(v1)))
			.accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isForbidden());
	}

	@Test
	void closingStopsSharingRetainsLastLocationThenPurgesAndRevokedParticipantIsRemoved() throws Exception {
		start(v1).andExpect(status().isOk());
		start(v2).andExpect(status().isOk());
		measure(v1, Instant.now()).andExpect(status().isNoContent());
		measure(v2, Instant.now()).andExpect(status().isNoContent());

		mvc.perform(delete("/api/v1/role-assignments/{id}", v1Role).with(authenticate(sessions.web(admin, true))))
			.andExpect(status().is2xxSuccessful());
		assertThat(sharing(v1)).isEqualTo("STOPPED:PARTICIPATION_REVOKED");
		assertThat(lastLocations(v1)).isZero();

		mvc.perform(post("/api/v1/operations/{id}/state", operationId).with(authenticate(sessions.web(admin, true)))
			.contentType(MediaType.APPLICATION_JSON).content("{\"targetState\": \"COMPLETED\"}"))
			.andExpect(status().isOk());
		assertThat(sharing(v2)).isEqualTo("STOPPED:OPERATION_CLOSED");
		assertThat(jdbc.sql("""
				select retain_until > now() + interval '29 days' and retain_until < now() + interval '31 days'
				  from location.last_locations where user_id = ? and operation_id = ?""")
			.params(v2, operationId).query(Boolean.class).single()).as("ADR-019: 30 gün").isTrue();
		measure(v2, Instant.now()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPERATION_NOT_ACTIVE"));

		service.purgeExpired();
		assertThat(lastLocations(v2)).isEqualTo(1);
		jdbc.sql("update location.last_locations set retain_until = now() - interval '1 second' where operation_id = ?")
			.param(operationId).update();
		service.purgeExpired();
		assertThat(lastLocations(v2)).isZero();
	}

	// ---- yardımcılar ----

	ResultActions start(UUID user) throws Exception {
		return mvc.perform(post("/api/v1/operations/{id}/location-sharing", operationId).with(authenticate(sessions.android(user))));
	}

	ResultActions measure(UUID user, Instant at) throws Exception {
		return mvc.perform(post("/api/v1/operations/{id}/locations", operationId).with(authenticate(sessions.android(user)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"lat\": 41.0082, \"lon\": 28.9784, \"accuracy\": 12.5, \"measuredAt\": \"%s\"}".formatted(at)));
	}

	ResultActions list(UUID user) throws Exception {
		return list(user, sessions.web(user, true));
	}

	ResultActions list(UUID user, String token) throws Exception {
		return mvc.perform(get("/api/v1/operations/{id}/locations", operationId).with(authenticate(token)));
	}

	String sharing(UUID user) {
		return jdbc.sql("select state || ':' || coalesce(stop_reason, '') from location.location_sharings where user_id = ? and operation_id = ?")
			.params(user, operationId).query(String.class).single();
	}

	int lastLocations(UUID user) {
		return jdbc.sql("select count(*) from location.last_locations where user_id = ? and operation_id = ?")
			.params(user, operationId).query(Integer.class).single();
	}

	int count(String action) {
		return jdbc.sql("select count(*) from audit.audit_entries where action = ? and operation_id = ?")
			.params(action, operationId).query(Integer.class).single();
	}

	UUID operation(String state) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', ?, case when ? = 'ACTIVE' then now() end, ?)""")
			.params(id, ORGANIZATION_ID, state, state, admin).update();
		return id;
	}

	UUID register(String name) {
		return accounts.register(new NewUserAccount("konum-" + UUID.randomUUID() + "@example.org", "Deprem-Tatbikati-2026",
				name + " Kişi", name, null));
	}

	UUID role(UUID userId, String role, String scopeType, UUID scopeId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(id, userId, role, scopeType, scopeId).update();
		return id;
	}

}
