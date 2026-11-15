package org.gks.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.gks.backend.TestSessions.authenticate;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/**
 * ADR-014 Android yönetim oturumu, gerçek yetki kararlarıyla (access taklit edilmez): ADM/KOR Android'de yalnızca
 * TOTP'den sonra oturum açar; oturum 12 sa mutlaktır; kurumsal izin yalnızca MFA'lı oturumda kullanılır.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MobileManagementSessionTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
	static final String PASSWORD = "Deprem-Tatbikati-2026";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	UUID operationId;
	UUID creator;

	@BeforeEach
	void operation() {
		operationId = UUID.randomUUID();
		creator = register(email());
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', 'ACTIVE', now(), ?)""").params(operationId, ORGANIZATION_ID, creator).update();
	}

	@Test
	void coordinatorAndroidLoginRequiresTotpAndOpensTwelveHourManagementSession() throws Exception {
		String email = email();
		UUID kor = register(email);
		role(kor, "KOR", "OPERATION", operationId);

		MockHttpServletResponse first = login(email).andExpect(status().isOk())
			.andExpect(jsonPath("$.mfaRequired").value(true))
			.andExpect(jsonPath("$.mfaEnrollmentRequired").value(true))
			.andExpect(jsonPath("$.accessToken").doesNotExist())
			.andExpect(jsonPath("$.session").doesNotExist())
			.andReturn().getResponse();
		assertThat(sessions(kor)).as("TOTP öncesi oturum yok").isZero();
		String ticket = JsonPath.read(first.getContentAsString(), "$.mfaTicket");
		assertThat(jdbc.sql("select client || ':' || installation_id from identity.mfa_tickets where user_id = ?").param(kor)
			.query(String.class).single()).isEqualTo("ANDROID:yonetim-testi-01");

		String secret = JsonPath.read(mvc.perform(json("/api/v1/auth/mfa/enroll", "{\"mfaTicket\": \"%s\"}".formatted(ticket)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.secret");
		MockHttpServletResponse verified = mvc.perform(json("/api/v1/auth/mfa",
				"{\"mfaTicket\": \"%s\", \"code\": \"%s\"}".formatted(ticket, MfaApiTests.code(secret, 0))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.session.clientType").value("ANDROID"))
			.andExpect(jsonPath("$.session.mfaVerified").value(true))
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andReturn().getResponse();
		assertThat(verified.getHeaders("Set-Cookie")).as("Android'de çerez yok").isEmpty();

		String body = verified.getContentAsString();
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.session.expiresAt"));
		assertThat(expiresAt).as("12 sa mutlak, 30 gün değil").isCloseTo(Instant.now().plus(Duration.ofHours(12)),
				within(2, ChronoUnit.MINUTES));

		// Döndürme mutlak bitişi uzatmaz (LD-19 kuralı MFA'lı Android zincirinde de geçerli).
		String rotated = mvc.perform(json("/api/v1/auth/refresh", "{\"refreshToken\": \"%s\"}"
			.formatted((String) JsonPath.read(body, "$.refreshToken")))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		Instant afterRotation = jdbc.sql("""
				select expires_at from identity.refresh_tokens
				 where user_id = ? and revoked_at is null and replaced_by is null""").param(kor)
			.query(Timestamp.class).single().toInstant();
		assertThat(afterRotation).isCloseTo(expiresAt, within(1, ChronoUnit.SECONDS));
		assertThat((String) JsonPath.read(rotated, "$.accessToken")).isNotBlank();
	}

	@Test
	void managementSessionManagesWithinScopeButVolunteerSessionCannot() throws Exception {
		UUID kor = register(email());
		role(kor, "KOR", "OPERATION", operationId);
		UUID leader = register(email());
		role(leader, "GON", "OPERATION", operationId);
		TestSessions sessions = new TestSessions(jdbc, jwtEncoder);
		String management = androidManagementSession(kor);
		String volunteer = sessions.android(kor);   // MFA'sız (eski veya taklit edilmiş gönüllü oturumu)
		String createGroup = "{\"name\": \"Mobil ekip\", \"leaderUserId\": \"%s\"}".formatted(leader);

		mvc.perform(post("/api/v1/operations/{id}/groups", operationId).with(authenticate(volunteer))
			.contentType(MediaType.APPLICATION_JSON).content(createGroup))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));
		mvc.perform(post("/api/v1/operations/{id}/groups", operationId).with(authenticate(management))
			.contentType(MediaType.APPLICATION_JSON).content(createGroup))
			.andExpect(status().isCreated());

		// Kapsam: başka operasyonda KOR değil → 403 FORBIDDEN (istemci değil, yetki)
		UUID other = UUID.randomUUID();
		jdbc.sql("insert into organization.operations (id, organization_id, name, state, activated_at, created_by) values (?, ?, 'Başka', 'ACTIVE', now(), ?)")
			.params(other, ORGANIZATION_ID, creator).update();
		mvc.perform(post("/api/v1/operations/{id}/groups", other).with(authenticate(management))
			.contentType(MediaType.APPLICATION_JSON).content(createGroup))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

		// MeContext izinleri oturuma göre: yönetim oturumunda kurumsal izinler var, gönüllü oturumunda yok.
		mvc.perform(get("/api/v1/me/context").with(authenticate(management)))
			.andExpect(jsonPath("$.permissions.operations['%s']".formatted(operationId)).value(org.hamcrest.Matchers.hasItem("task.manage")));
		String plain = mvc.perform(get("/api/v1/me/context").with(authenticate(volunteer)))
			.andReturn().getResponse().getContentAsString();
		assertThat(plain).doesNotContain("task.manage");

		// Gönüllü uçları (x-client ANDROID) yönetim oturumunda da çalışır.
		mvc.perform(get("/api/v1/me/assignments").with(authenticate(management))).andExpect(status().isOk());
	}

	@Test
	void supportRoleKeepsMfaFreeAndHasNoCorporatePermissionsOnAndroid() throws Exception {
		String email = email();
		UUID tek = register(email);
		role(tek, "TEK", "ORGANIZATION", ORGANIZATION_ID);
		String body = login(email).andExpect(status().isOk())
			.andExpect(jsonPath("$.mfaRequired").value(false))
			.andExpect(jsonPath("$.session.mfaVerified").value(false))
			.andReturn().getResponse().getContentAsString();
		String context = mvc.perform(get("/api/v1/me/context").with(authenticate(JsonPath.read(body, "$.accessToken"))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(context).doesNotContain("user.status.view").doesNotContain("session.revoke");
	}

	@Test
	void volunteerAndroidLoginIsUnchanged() throws Exception {
		String email = email();
		UUID gon = register(email);
		role(gon, "GON", "OPERATION", operationId);
		String body = login(email).andExpect(status().isOk()).andExpect(jsonPath("$.mfaRequired").value(false))
			.andReturn().getResponse().getContentAsString();
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.session.expiresAt"));
		assertThat(expiresAt).as("gönüllü oturumu 30 gün kayan").isAfter(Instant.now().plus(Duration.ofDays(29)));
	}

	// ---------------------------------------------------------------- operasyon yönetimi (Android)

	@Test
	void adminManagesOperationLifecycleAndCoordinatorsFromAndroid() throws Exception {
		UUID admin = register(email());
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		String token = androidManagementSession(admin);

		String created = mvc.perform(post("/api/v1/operations").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.content("{\"name\": \"Mobil tatbikat\", \"coordinatorIds\": [\"%s\"]}".formatted(admin)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("PLANNED"))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/operations/{id}", id)
			.with(authenticate(token)).contentType(MediaType.APPLICATION_JSON).content("{\"description\": \"Telefondan düzenlendi\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Telefondan düzenlendi"));
		mvc.perform(post("/api/v1/operations/{id}/state", id).with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetState\": \"ACTIVE\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACTIVE"));

		UUID coordinator = register(email());
		String grant = mvc.perform(post("/api/v1/role-assignments").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\": \"%s\", \"role\": \"KOR\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}".formatted(coordinator, id)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		mvc.perform(get("/api/v1/role-assignments").param("scopeId", id).param("role", "KOR").param("active", "true")
			.with(authenticate(token))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/role-assignments/{id}",
				(String) JsonPath.read(grant, "$.id")).with(authenticate(token)))
			.andExpect(status().isNoContent());
	}

	@Test
	void coordinatorSeesAndManagesOnlyOwnOperationFromAndroid() throws Exception {
		UUID kor = register(email());
		role(kor, "KOR", "OPERATION", operationId);
		UUID other = UUID.randomUUID();
		jdbc.sql("insert into organization.operations (id, organization_id, name, state, activated_at, created_by) values (?, ?, 'Başka', 'ACTIVE', now(), ?)")
			.params(other, ORGANIZATION_ID, creator).update();
		String token = androidManagementSession(kor);

		String list = mvc.perform(get("/api/v1/operations").with(authenticate(token))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(list).contains(operationId.toString()).doesNotContain(other.toString());
		mvc.perform(get("/api/v1/operations/{id}", operationId).with(authenticate(token)))
			.andExpect(jsonPath("$.coordinators[0].id").value(kor.toString()))   // yönetici izdüşümü (kimlikli)
			.andExpect(jsonPath("$.pendingAssignmentCount").exists());

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/operations/{id}", operationId)
			.with(authenticate(token)).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Sel müdahalesi - güncel\"}"))
			.andExpect(status().isOk());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/operations/{id}", other)
			.with(authenticate(token)).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Ele geçirme\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/operations").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Yetkisiz\", \"coordinatorIds\": [\"%s\"]}".formatted(kor)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(post("/api/v1/role-assignments").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\": \"%s\", \"role\": \"KOR\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}".formatted(kor, other)))
			.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/operations/{id}/state", operationId).with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetState\": \"COMPLETED\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
	}

	@Test
	void coordinatorManagesVolunteersAndInvitationsFromAndroid() throws Exception {
		UUID kor = register(email());
		role(kor, "KOR", "OPERATION", operationId);
		UUID volunteer = register(email());
		role(volunteer, "GON", "OPERATION", operationId);
		String invitedEmail = email();
		register(invitedEmail);
		UUID other = UUID.randomUUID();
		jdbc.sql("insert into organization.operations (id, organization_id, name, state, activated_at, created_by) values (?, ?, 'Başka', 'ACTIVE', now(), ?)")
			.params(other, ORGANIZATION_ID, creator).update();
		String token = androidManagementSession(kor);
		String invite = "{\"email\": \"%s\", \"scopeType\": \"OPERATION\", \"scopeId\": \"%s\"}";

		mvc.perform(get("/api/v1/operations/{id}/participants", operationId).with(authenticate(token)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].user.id").value(volunteer.toString()));
		String created = mvc.perform(post("/api/v1/invitations").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", UUID.randomUUID().toString()).content(invite.formatted(invitedEmail, operationId)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("OPERATION"))
			.andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/v1/invitations").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", UUID.randomUUID().toString()).content(invite.formatted(invitedEmail, operationId)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.conflict").value("INVITATION_PENDING"));
		mvc.perform(get("/api/v1/invitations").param("scopeType", "OPERATION").param("scopeId", operationId.toString())
			.param("status", "PENDING").with(authenticate(token)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].email").value(invitedEmail));
		mvc.perform(post("/api/v1/invitations/{id}/revoke", (String) JsonPath.read(created, "$.id")).with(authenticate(token)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));

		// Kapsam: başka operasyonda ne katılımcılar ne davet
		mvc.perform(get("/api/v1/operations/{id}/participants", other).with(authenticate(token))).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/invitations").with(authenticate(token)).contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", UUID.randomUUID().toString()).content(invite.formatted(email(), other)))
			.andExpect(status().isForbidden());
	}

	@Test
	void coordinatorManagesGroupsFromAndroidAndAdminWithoutKorIsReadOnly() throws Exception {
		UUID kor = register(email());
		role(kor, "KOR", "OPERATION", operationId);
		UUID a = register(email());
		UUID b = register(email());
		UUID c = register(email());
		for (UUID u : List.of(a, b, c)) {
			role(u, "GON", "OPERATION", operationId);
		}
		String token = androidManagementSession(kor);
		var json = MediaType.APPLICATION_JSON;
		var patch = (java.util.function.Function<String, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>)
				path -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path);

		String created = mvc.perform(post("/api/v1/operations/{id}/groups", operationId).with(authenticate(token)).contentType(json)
			.content("{\"name\": \"Mobil arama\", \"leaderUserId\": \"%s\", \"memberUserIds\": [\"%s\"]}".formatted(a, b)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String group = JsonPath.read(created, "$.id");

		mvc.perform(get("/api/v1/operations/{id}/participants", operationId).param("notInGroup", group).with(authenticate(token)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].user.id").value(c.toString()));
		mvc.perform(post("/api/v1/groups/{id}/members", group).with(authenticate(token)).contentType(json)
			.content("{\"userId\": \"%s\"}".formatted(c))).andExpect(status().isCreated());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/groups/{id}/members/{u}", group, a)
			.with(authenticate(token))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LEADER_REQUIRED"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/groups/{id}/leader", group)
			.with(authenticate(token)).contentType(json).content("{\"userId\": \"%s\"}".formatted(b)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.leader.id").value(b.toString()));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/groups/{id}/members/{u}", group, a)
			.with(authenticate(token))).andExpect(status().isNoContent());
		mvc.perform(patch.apply("/api/v1/groups/" + group).with(authenticate(token)).contentType(json)
			.content("{\"name\": \"Mobil arama 2\", \"description\": null}")).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Mobil arama 2"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/groups/{id}/meeting-point", group)
			.with(authenticate(token)).contentType(json).content("{\"address\": \"Okul bahçesi\", \"time\": \"2030-01-01T07:00:00Z\"}"))
			.andExpect(status().isOk());
		mvc.perform(get("/api/v1/groups").param("operationId", operationId.toString()).with(authenticate(token)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].memberCount").value(2));

		// ADM (bu operasyonda KOR değil): görür, üyelik değiştiremez (GR-04 yalnızca K1 KOR).
		UUID admin = register(email());
		role(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		String adminToken = androidManagementSession(admin);
		mvc.perform(get("/api/v1/groups/{id}", group).with(authenticate(adminToken))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/groups/{id}/members", group).with(authenticate(adminToken)).contentType(json)
			.content("{\"userId\": \"%s\"}".formatted(a))).andExpect(status().isForbidden());

		// Başka operasyonda grup kurulamaz.
		UUID other = UUID.randomUUID();
		jdbc.sql("insert into organization.operations (id, organization_id, name, state, activated_at, created_by) values (?, ?, 'Başka', 'ACTIVE', now(), ?)")
			.params(other, ORGANIZATION_ID, creator).update();
		mvc.perform(post("/api/v1/operations/{id}/groups", other).with(authenticate(token)).contentType(json)
			.content("{\"name\": \"Yetkisiz\", \"leaderUserId\": \"%s\"}".formatted(a))).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- yardımcılar

	/** TOTP kurulumu + doğrulama ile gerçek Android yönetim oturumu; erişim belirteci döner. */
	String androidManagementSession(UUID userId) throws Exception {
		String email = jdbc.sql("select email from identity.users where id = ?").param(userId).query(String.class).single();
		String ticket = JsonPath.read(login(email).andReturn().getResponse().getContentAsString(), "$.mfaTicket");
		String secret = JsonPath.read(mvc.perform(json("/api/v1/auth/mfa/enroll", "{\"mfaTicket\": \"%s\"}".formatted(ticket)))
			.andReturn().getResponse().getContentAsString(), "$.secret");
		return JsonPath.read(mvc.perform(json("/api/v1/auth/mfa",
				"{\"mfaTicket\": \"%s\", \"code\": \"%s\"}".formatted(ticket, MfaApiTests.code(secret, 0))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	ResultActions login(String email) throws Exception {
		return mvc.perform(json("/api/v1/auth/login",
				"{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"ANDROID\", \"installationId\": \"yonetim-testi-01\"}"
					.formatted(email, PASSWORD)));
	}

	org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	String email() {
		return "yonetim-" + UUID.randomUUID() + "@example.org";
	}

	UUID register(String email) {
		return accounts.register(new NewUserAccount(email, PASSWORD, "Yönetim Testi", "Yönetim", null));
	}

	void role(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

	int sessions(UUID userId) {
		List<Integer> rows = jdbc.sql("select count(*) from identity.refresh_tokens where user_id = ?").param(userId)
			.query(Integer.class).list();
		return rows.getFirst();
	}

}
