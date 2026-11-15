package org.gks.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.gks.backend.TestSessions;
import org.gks.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

import com.jayway.jsonpath.JsonPath;

/**
 * OR-01, OR-04, OR-06 ve {@code OperationView} izdüşümü (backend §7) gerçek yetkiyle; taklit yok. ADM/KOR oturumları
 * web+MFA'dır (TOTP akışı her testte tekrarlanmaz).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OperationLifecycleApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	UUID admin;
	String adminToken;

	@BeforeEach
	void setUp() {
		admin = user("Yönetici");
		grant(admin, "ADM", "ORGANIZATION", ORGANIZATION_ID);
		adminToken = sessions().web(admin, true);
	}

	@Nested
	class Create {

		/** AC-08.4: operasyon PLANNED oluşur, koordinatörler aynı işlemde KOR olur ve operasyonu yönetebilir. */
		@Test
		void adminCreatesOperationAndCoordinatorsCanManageIt() throws Exception {
			UUID coordinator = user("Koordinatör Ayşe");

			String body = create(adminToken, null, "{\"name\": \"Deprem müdahalesi\", \"coordinatorIds\": [\"%s\"]}"
				.formatted(coordinator))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern("/api/v1/operations/[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.state").value("PLANNED"))
				.andExpect(jsonPath("$.coordinators[0].id").value(coordinator.toString()))
				.andExpect(jsonPath("$.coordinators[0].displayName").value("Koordinatör Ayşe"))
				.andReturn().getResponse().getContentAsString();
			UUID operationId = UUID.fromString(JsonPath.read(body, "$.id"));

			assertThat(jdbc.sql("""
					select granted_by from access.role_assignments
					 where user_id = ? and role = 'KOR' and scope_id = ? and revoked_at is null""")
				.params(coordinator, operationId).query(UUID.class).single()).isEqualTo(admin);
			mvc.perform(patch("/api/v1/operations/{id}", operationId).with(auth(sessions().web(coordinator, true)))
				.contentType(MediaType.APPLICATION_JSON).content("{\"description\": \"Kuzey ilçeleri\"}"))
				.andExpect(status().isOk());
		}

		@Test
		void invalidRequestsAreRejectedWithoutSideEffects() throws Exception {
			UUID coordinator = user("Koordinatör");
			UUID disabled = user("Devre Dışı");
			jdbc.sql("update identity.users set status = 'DISABLED', disabled_at = now() where id = ?").param(disabled).update();
			String eleven = IntStream.range(0, 11).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
				.collect(Collectors.joining(","));
			int before = operationCount();

			expectFieldError(create(adminToken, null, "{\"coordinatorIds\": [\"%s\"]}".formatted(coordinator)), "name", "REQUIRED");
			expectFieldError(create(adminToken, null, "{\"name\": \"Tatbikat\", \"coordinatorIds\": []}"), "coordinatorIds",
					"REQUIRED");
			expectFieldError(create(adminToken, null, "{\"name\": \"Tatbikat\", \"coordinatorIds\": [%s]}".formatted(eleven)),
					"coordinatorIds", "OUT_OF_RANGE");
			expectFieldError(create(adminToken, null, "{\"name\": \"Tatbikat\", \"coordinatorIds\": [\"%s\", \"%s\"]}"
				.formatted(coordinator, coordinator)), "coordinatorIds[1]", "NOT_ALLOWED_VALUE");
			// Aktif olmayan koordinatör: operasyon satırı da geri alınır (tek işlem).
			expectFieldError(create(adminToken, null, "{\"name\": \"Tatbikat\", \"coordinatorIds\": [\"%s\"]}"
				.formatted(disabled)), "coordinatorIds[0]", "NOT_ALLOWED_VALUE");

			assertThat(operationCount()).isEqualTo(before);
		}

		/** operation.create yalnızca ADM; ADM için MFA'lı web (K-19, ADR-013). */
		@Test
		void onlyAdminOnMfaWebSessionMayCreate() throws Exception {
			UUID coordinator = user("Koordinatör");
			grant(coordinator, "KOR", "OPERATION", operation("ACTIVE"));
			String body = "{\"name\": \"Yetkisiz\", \"coordinatorIds\": [\"%s\"]}".formatted(coordinator);

			expectProblem(create(sessions().web(coordinator, true), null, body), 403, "FORBIDDEN");
			expectProblem(create(sessions().web(admin, false), null, body), 403, "CLIENT_NOT_ALLOWED");
			expectProblem(create(sessions().android(admin), null, body), 403, "CLIENT_NOT_ALLOWED");
		}

		/** §3.6 "d": aynı anahtar + aynı gövde → aynı yanıt, tek operasyon ve tek KOR ataması; farklı gövde → 422. */
		@Test
		void createIsIdempotentWithKey() throws Exception {
			UUID coordinator = user("Koordinatör");
			String key = UUID.randomUUID().toString();
			String body = "{\"name\": \"Sel tahliyesi\", \"coordinatorIds\": [\"%s\"]}".formatted(coordinator);

			String first = create(adminToken, key, body).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
			String second = create(adminToken, key, body)
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();

			assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
			assertThat(jdbc.sql("select count(*) from access.role_assignments where user_id = ? and role = 'KOR'")
				.param(coordinator).query(Integer.class).single()).isEqualTo(1);
			expectProblem(create(adminToken, key, body.replace("Sel tahliyesi", "Başka ad")), 422,
					"IDEMPOTENCY_KEY_MISMATCH");
		}

	}

	@Nested
	class ListAndView {

		/** OR-04: ADM tümü; KOR yalnızca koordine ettikleri; gönüllü yalnızca katıldıkları (Android). */
		@Test
		void listIsFilteredByScope() throws Exception {
			UUID mine = operation("ACTIVE");
			UUID other = operation("PLANNED");
			UUID coordinator = user("Koordinatör");
			grant(coordinator, "KOR", "OPERATION", mine);
			UUID volunteer = user("Gönüllü");
			grant(volunteer, "GON", "OPERATION", other);

			assertThat(listIds(sessions().web(coordinator, true), "")).containsExactly(mine);
			assertThat(listIds(sessions().android(volunteer), "")).containsExactly(other);
			assertThat(listIds(sessions().android(user("Hiçbiri")), "")).isEmpty();
			List<UUID> all = listIds(adminToken, "?state=PLANNED&limit=100");
			assertThat(all).contains(other).doesNotContain(mine);
		}

		@Test
		void listPagesWithCursorAndRejectsBadQuery() throws Exception {
			UUID first = operation("ACTIVE");
			UUID second = operation("ACTIVE");
			UUID coordinator = user("Koordinatör");
			grant(coordinator, "KOR", "OPERATION", first);
			grant(coordinator, "KOR", "OPERATION", second);
			String token = sessions().web(coordinator, true);

			String page1 = mvc.perform(get("/api/v1/operations?limit=1").with(auth(token))).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			assertThat(ids(page1)).containsExactly(second);
			String page2 = mvc.perform(get("/api/v1/operations?limit=1&cursor=" + JsonPath.read(page1, "$.nextCursor"))
				.with(auth(token))).andReturn().getResponse().getContentAsString();
			assertThat(ids(page2)).containsExactly(first);
			assertThat(page2).doesNotContain("nextCursor");
			expectFieldError(mvc.perform(get("/api/v1/operations?state=ARCHIVED").with(auth(token))), "state",
					"NOT_ALLOWED_VALUE");
		}

		/** OR-05: gönüllü koordinatörleri yalnızca adıyla görür; KOR gecikme ve bekleyen atama özetini görür. */
		@Test
		void viewProjectionDependsOnRole() throws Exception {
			UUID operationId = operation("ACTIVE");
			jdbc.sql("update organization.operations set planned_end_at = now() - interval '3 hours' where id = ?")
				.param(operationId).update();
			UUID coordinator = user("Koordinatör");
			grant(coordinator, "KOR", "OPERATION", operationId);
			UUID volunteer = user("Gönüllü");
			grant(volunteer, "GON", "OPERATION", operationId);
			pendingAssignment(operationId, volunteer);

			mvc.perform(get("/api/v1/operations/{id}", operationId).with(auth(sessions().web(coordinator, true))))
				.andExpect(jsonPath("$.coordinators[0].id").value(coordinator.toString()))
				.andExpect(jsonPath("$.overdue").value(true))
				.andExpect(jsonPath("$.pendingAssignmentCount").value(1));
			mvc.perform(get("/api/v1/operations/{id}", operationId).with(auth(sessions().android(volunteer))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.coordinators[0].displayName").value("Koordinatör"))
				.andExpect(jsonPath("$.coordinators[0].id").doesNotExist())
				.andExpect(jsonPath("$.overdue").doesNotExist())
				.andExpect(jsonPath("$.pendingAssignmentCount").doesNotExist());
		}

	}

	@Nested
	class Participants {

		/** OR-06: KOR izdüşümü, gruplar ve açık atama sayısı; {@code notInGroup} grup üyelerini dışarıda bırakır. */
		@Test
		void coordinatorListsParticipantsWithGroupsAndOpenAssignments() throws Exception {
			UUID operationId = operation("ACTIVE");
			UUID coordinator = user("Koordinatör");
			grant(coordinator, "KOR", "OPERATION", operationId);
			UUID inGroup = user("Grupta");
			grant(inGroup, "GON", "OPERATION", operationId);
			UUID free = user("Grupsuz");
			grant(free, "GON", "OPERATION", operationId);
			UUID groupId = group(operationId, inGroup);
			pendingAssignment(operationId, inGroup);
			String token = sessions().web(coordinator, true);

			String body = mvc.perform(get("/api/v1/operations/{id}/participants", operationId).with(auth(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andReturn().getResponse().getContentAsString();
			List<java.util.Map<String, Object>> grouped = JsonPath.read(body, "$.items[?(@.user.id == '" + inGroup + "')]");
			assertThat(grouped).hasSize(1);
			String participant = com.jayway.jsonpath.JsonPath.parse(grouped.getFirst()).jsonString();
			assertThat(JsonPath.<String>read(participant, "$.user.email")).isEqualTo(inGroup + "@example.org");
			assertThat(JsonPath.<String>read(participant, "$.groups[0].id")).isEqualTo(groupId.toString());
			assertThat(JsonPath.<Integer>read(participant, "$.openAssignmentCount")).isEqualTo(1);

			mvc.perform(get("/api/v1/operations/{id}/participants?notInGroup=" + groupId, operationId).with(auth(token)))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].user.id").value(free.toString()));
			expectFieldError(mvc.perform(get("/api/v1/operations/{id}/participants?notInGroup=" + UUID.randomUUID(),
					operationId).with(auth(token))), "notInGroup", "NOT_ALLOWED_VALUE");
		}

		@Test
		void onlyOwnCoordinatorOrAdminOnWebMayListParticipants() throws Exception {
			UUID operationId = operation("ACTIVE");
			UUID otherCoordinator = user("Başka Koordinatör");
			grant(otherCoordinator, "KOR", "OPERATION", operation("ACTIVE"));
			UUID volunteer = user("Gönüllü");
			grant(volunteer, "GON", "OPERATION", operationId);

			expectProblem(mvc.perform(get("/api/v1/operations/{id}/participants", operationId)
				.with(auth(sessions().web(otherCoordinator, true)))), 403, "FORBIDDEN");
			expectProblem(mvc.perform(get("/api/v1/operations/{id}/participants", operationId)
				.with(auth(sessions().android(volunteer)))), 403, "CLIENT_NOT_ALLOWED");
			mvc.perform(get("/api/v1/operations/{id}/participants", operationId).with(auth(adminToken)))
				.andExpect(status().isOk());
		}

	}

	// --- yardımcılar ---

	private TestSessions sessions() {
		return new TestSessions(jdbc, jwtEncoder);
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor auth(String token) {
		return TestSessions.authenticate(token);
	}

	private ResultActions create(String token, String key, String body) throws Exception {
		var request = post("/api/v1/operations").with(auth(token)).contentType(MediaType.APPLICATION_JSON).content(body);
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mvc.perform(request);
	}

	private List<UUID> listIds(String token, String query) throws Exception {
		return ids(mvc.perform(get("/api/v1/operations" + query).with(auth(token))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private static List<UUID> ids(String page) {
		List<String> ids = JsonPath.read(page, "$.items[*].id");
		return ids.stream().map(UUID::fromString).toList();
	}

	private UUID user(String displayName) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', ?, ?)""")
			.params(id, id + "@example.org", displayName + " Soyadı", displayName).update();
		return id;
	}

	/** UUIDv7: liste sıralaması oluşturma sırasıdır (§3.5). */
	private UUID operation(String state) {
		UUID id = org.gks.backend.Ids.newId();
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
				values (?, ?, 'Sel müdahalesi', ?, ?, ?)""")
			.params(id, ORGANIZATION_ID, state, state.equals("PLANNED") ? null : Timestamp.from(Instant.now()), admin)
			.update();
		return id;
	}

	private void grant(UUID userId, String role, String scopeType, UUID scopeId) {
		jdbc.sql("insert into access.role_assignments (id, user_id, role, scope_type, scope_id) values (?, ?, ?, ?, ?)")
			.params(UUID.randomUUID(), userId, role, scopeType, scopeId).update();
	}

	private UUID group(UUID operationId, UUID leader) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, ?, ?)")
			.params(id, ORGANIZATION_ID, operationId, "Grup " + id.toString().substring(0, 8), admin).update();
		jdbc.sql("insert into groups.group_memberships (id, group_id, user_id, role, added_by) values (?, ?, ?, 'LEADER', ?)")
			.params(UUID.randomUUID(), id, leader, admin).update();
		return id;
	}

	private void pendingAssignment(UUID operationId, UUID userId) {
		UUID taskId = UUID.randomUUID();
		jdbc.sql("""
				insert into tasks.tasks (id, operation_id, title, state, published_at, created_by)
				values (?, ?, 'Su dağıtımı', 'PUBLISHED', now(), ?)""").params(taskId, operationId, admin).update();
		jdbc.sql("""
				insert into tasks.task_assignments (id, task_id, user_id, assigned_by, respond_by)
				values (?, ?, ?, ?, now() + interval '1 hour')""").params(UUID.randomUUID(), taskId, userId, admin).update();
	}

	private int operationCount() {
		return jdbc.sql("select count(*) from organization.operations").query(Integer.class).single();
	}

	private static ResultActions expectFieldError(ResultActions result, String field, String code) throws Exception {
		return expectProblem(result, 400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.errors[?(@.field == '%s')].code".formatted(field)).value(code));
	}

	private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
		return result
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value(code));
	}

}
