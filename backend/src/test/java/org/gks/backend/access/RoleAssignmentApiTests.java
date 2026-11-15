package org.gks.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * AC-01 – AC-03 (backend §6) ve gerçek yetki zinciri; taklit yok. ADM/KOR oturumları web+MFA'dır (TOTP akışı her
 * testte tekrarlanmaz; filtre, uç düzeyi kapı ve {@code can()} gerçektir).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RoleAssignmentApiTests {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JwtEncoder jwtEncoder;

	UUID admin;
	String adminToken;
	UUID operationId;

	@BeforeEach
	void setUp() {
		admin = user("Yönetici");
		grant(admin, Role.ADM, ORGANIZATION_ID);
		adminToken = sessions().web(admin, true);
		operationId = operation("ACTIVE");
	}

	@Nested
	class Grant {

		@Test
		void adminAssignsCoordinator() throws Exception {
			UUID coordinator = user("Koordinatör");

			assign(adminToken, coordinator, "KOR", "OPERATION", operationId)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern("/api/v1/role-assignments/[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.user.id").value(coordinator.toString()))
				.andExpect(jsonPath("$.user.displayName").value("Koordinatör"))
				.andExpect(jsonPath("$.role").value("KOR"))
				.andExpect(jsonPath("$.scopeType").value("OPERATION"))
				.andExpect(jsonPath("$.scopeName").value("Sel müdahalesi"))
				.andExpect(jsonPath("$.grantedBy.id").value(admin.toString()))
				.andExpect(jsonPath("$.revokedAt").doesNotExist());
		}

		@Test
		void duplicateInvalidPairUnknownUserAndClosedOperationAreRejected() throws Exception {
			UUID coordinator = user("Koordinatör");
			assign(adminToken, coordinator, "KOR", "OPERATION", operationId).andExpect(status().isCreated());

			expectProblem(assign(adminToken, coordinator, "KOR", "OPERATION", operationId), 409, "ALREADY_EXISTS")
				.andExpect(jsonPath("$.conflict").value("ROLE_ACTIVE"));
			expectProblem(assign(adminToken, coordinator, "KOR", "ORGANIZATION", ORGANIZATION_ID), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("role"))
				.andExpect(jsonPath("$.errors[0].code").value("NOT_ALLOWED_VALUE"));
			expectProblem(assign(adminToken, UUID.randomUUID(), "GON", "OPERATION", operationId), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("userId"));
			expectProblem(assign(adminToken, coordinator, "GON", "OPERATION", operation("COMPLETED")), 409, "OPERATION_CLOSED");
		}

		/** role.assign yalnızca ADM (yalnızca K1); ADM/KOR için MFA'lı web (K-19, ADR-013). */
		@Test
		void onlyAdminOnMfaWebSessionMayAssign() throws Exception {
			UUID coordinator = user("Koordinatör");
			grant(coordinator, Role.KOR, operationId);
			UUID target = user("Hedef");

			expectProblem(assign(sessions().web(coordinator, true), target, "GON", "OPERATION", operationId), 403, "FORBIDDEN");
			expectProblem(assign(sessions().web(admin, false), target, "GON", "OPERATION", operationId), 403,
					"CLIENT_NOT_ALLOWED");
			expectProblem(assign(sessions().android(admin), target, "GON", "OPERATION", operationId), 403,
					"CLIENT_NOT_ALLOWED");
			assertThat(activeRoles(target)).isEmpty();
		}

	}

	@Nested
	class Revoke {

		@Test
		void revokedRoleStopsWorkingOnNextRequest() throws Exception {
			UUID other = user("İkinci Yönetici");
			UUID assignment = grant(other, Role.ADM, ORGANIZATION_ID);
			String otherToken = sessions().web(other, true);
			mvc.perform(get("/api/v1/role-assignments").with(TestSessions.authenticate(otherToken)))
				.andExpect(status().isOk());

			mvc.perform(delete("/api/v1/role-assignments/{id}", assignment).with(TestSessions.authenticate(adminToken)))
				.andExpect(status().isNoContent());

			expectProblem(mvc.perform(get("/api/v1/role-assignments").with(TestSessions.authenticate(otherToken))), 403,
					"FORBIDDEN");
			expectProblem(mvc.perform(delete("/api/v1/role-assignments/{id}", assignment)
				.with(TestSessions.authenticate(adminToken))), 409, "INVALID_STATE_TRANSITION");
		}

		/** S-09: ADM kendi ADM atamasını kaldıramaz. */
		@Test
		void adminCannotRevokeOwnAdminRole() throws Exception {
			UUID own = jdbc.sql("select id from access.role_assignments where user_id = ? and role = 'ADM'")
				.param(admin).query(UUID.class).single();

			expectProblem(mvc.perform(delete("/api/v1/role-assignments/{id}", own)
				.with(TestSessions.authenticate(adminToken))), 403, "FORBIDDEN");
			assertThat(activeRoles(admin)).containsExactly("ADM");
		}

		/** N-10: katılım kaldırılınca grup üyelikleri ve bekleyen atamalar sonlanır. */
		@Test
		void revokingParticipationEndsMembershipsAndPendingAssignments() throws Exception {
			UUID volunteer = user("Gönüllü");
			UUID participation = grant(volunteer, Role.GON, operationId);
			UUID leader = user("Lider");
			grant(leader, Role.GON, operationId);
			UUID groupId = group(leader, volunteer);
			UUID pending = pendingAssignment(volunteer);

			mvc.perform(delete("/api/v1/role-assignments/{id}", participation).with(TestSessions.authenticate(adminToken)))
				.andExpect(status().isNoContent());

			assertThat(jdbc.sql("""
					select status || ':' || end_reason from groups.group_memberships where group_id = ? and user_id = ?""")
				.params(groupId, volunteer).query(String.class).single()).isEqualTo("REMOVED:PARTICIPATION_REVOKED");
			assertThat(jdbc.sql("select status from tasks.task_assignments where id = ?").param(pending)
				.query(String.class).single()).isEqualTo("REMOVED");
		}

		/** K-32: grubun aktif lideri katılımdan çıkarılamaz; işlem tamamen geri alınır. */
		@Test
		void activeLeaderParticipationCannotBeRevoked() throws Exception {
			UUID leader = user("Lider");
			UUID participation = grant(leader, Role.GON, operationId);
			UUID groupId = group(leader);

			expectProblem(mvc.perform(delete("/api/v1/role-assignments/{id}", participation)
				.with(TestSessions.authenticate(adminToken))), 409, "LEADER_REQUIRED");
			assertThat(activeRoles(leader)).containsExactly("GON");
			assertThat(jdbc.sql("select status from groups.group_memberships where group_id = ? and user_id = ?")
				.params(groupId, leader).query(String.class).single()).isEqualTo("ACTIVE");
		}

	}

	@Nested
	class ListAssignments {

		@Test
		void filtersAndPages() throws Exception {
			UUID volunteer = user("Gönüllü");
			grant(volunteer, Role.GON, operationId);
			grant(volunteer, Role.GON, operation("ACTIVE"));

			String page1 = mvc.perform(get("/api/v1/role-assignments?userId=" + volunteer + "&limit=1")
				.with(TestSessions.authenticate(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andReturn().getResponse().getContentAsString();
			String cursor = JsonPath.read(page1, "$.nextCursor");
			mvc.perform(get("/api/v1/role-assignments?userId=" + volunteer + "&limit=1&cursor=" + cursor)
				.with(TestSessions.authenticate(adminToken)))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
			mvc.perform(get("/api/v1/role-assignments?scopeId=" + operationId + "&role=GON")
				.with(TestSessions.authenticate(adminToken)))
				.andExpect(jsonPath("$.items[0].user.id").value(volunteer.toString()));
		}

	}

	/** Taklitsiz yetki zinciri: gerçek KOR ataması grup oluşturur; kapsam dışı ve kaldırılmış rol reddedilir. */
	@Nested
	class RealAuthorization {

		@Test
		void coordinatorCreatesGroupOnlyInOwnOperation() throws Exception {
			UUID coordinator = user("Koordinatör");
			UUID assignment = grant(coordinator, Role.KOR, operationId);
			String token = sessions().web(coordinator, true);
			UUID leader = user("Lider");
			grant(leader, Role.GON, operationId);
			UUID outsider = user("Dışarıdan");
			UUID otherOperation = operation("ACTIVE");

			createGroup(token, operationId, "Arama 1", leader).andExpect(status().isCreated());
			expectProblem(createGroup(token, operationId, "Arama 2", outsider), 409, "NOT_A_PARTICIPANT");
			expectProblem(createGroup(token, otherOperation, "Başka", leader), 403, "FORBIDDEN");
			expectProblem(createGroup(sessions().android(coordinator), operationId, "Android", leader), 403,
					"CLIENT_NOT_ALLOWED");
			expectProblem(createGroup(sessions().web(coordinator, false), operationId, "MFA'sız", leader), 403,
					"CLIENT_NOT_ALLOWED");

			jdbc.sql("update access.role_assignments set revoked_at = now(), revoke_reason = 'MANUAL' where id = ?")
				.param(assignment).update();
			expectProblem(createGroup(token, operationId, "Arama 3", leader), 403, "FORBIDDEN");
		}

	}

	// --- yardımcılar ---

	private TestSessions sessions() {
		return new TestSessions(jdbc, jwtEncoder);
	}

	private ResultActions assign(String token, UUID userId, String role, String scopeType, UUID scopeId)
			throws Exception {
		return mvc.perform(post("/api/v1/role-assignments").with(TestSessions.authenticate(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\": \"%s\", \"role\": \"%s\", \"scopeType\": \"%s\", \"scopeId\": \"%s\"}"
				.formatted(userId, role, scopeType, scopeId)));
	}

	private ResultActions createGroup(String token, UUID operation, String name, UUID leader) throws Exception {
		return mvc.perform(post("/api/v1/operations/{id}/groups", operation).with(TestSessions.authenticate(token))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"%s\", \"leaderUserId\": \"%s\"}".formatted(name, leader)));
	}

	private UUID user(String displayName) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', ?, ?)""")
			.params(id, id + "@example.org", displayName + " Soyadı", displayName).update();
		return id;
	}

	private UUID operation(String state) {
		UUID id = UUID.randomUUID();
		boolean activated = !state.equals("PLANNED");
		boolean closed = state.equals("COMPLETED");
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, closed_at, created_by)
				values (?, ?, 'Sel müdahalesi', ?, ?, ?, ?)""")
			.params(id, ORGANIZATION_ID, state, activated ? Timestamp.from(Instant.now()) : null,
					closed ? Timestamp.from(Instant.now()) : null, admin)
			.update();
		return id;
	}

	private UUID grant(UUID userId, Role role, UUID scopeId) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into access.role_assignments (id, user_id, role, scope_type, scope_id)
				values (?, ?, ?, ?, ?)""").params(id, userId, role.name(), role.scopeType.name(), scopeId).update();
		return id;
	}

	private UUID group(UUID leader, UUID... members) {
		UUID id = UUID.randomUUID();
		jdbc.sql("insert into groups.groups (id, organization_id, operation_id, name, created_by) values (?, ?, ?, ?, ?)")
			.params(id, ORGANIZATION_ID, operationId, "Grup " + id.toString().substring(0, 8), admin).update();
		membership(id, leader, "LEADER");
		for (UUID member : members) {
			membership(id, member, "MEMBER");
		}
		return id;
	}

	private void membership(UUID groupId, UUID userId, String role) {
		jdbc.sql("""
				insert into groups.group_memberships (id, group_id, user_id, role, added_by)
				values (?, ?, ?, ?, ?)""").params(UUID.randomUUID(), groupId, userId, role, admin).update();
	}

	/** Yayınlanmış görevde bekleyen atama (görev API'si bu testin konusu değil). */
	private UUID pendingAssignment(UUID userId) {
		UUID taskId = UUID.randomUUID();
		jdbc.sql("""
				insert into tasks.tasks (id, operation_id, title, state, published_at, created_by)
				values (?, ?, 'Su dağıtımı', 'PUBLISHED', now(), ?)""").params(taskId, operationId, admin).update();
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into tasks.task_assignments (id, task_id, user_id, assigned_by, respond_by)
				values (?, ?, ?, ?, now() + interval '1 hour')""").params(id, taskId, userId, admin).update();
		return id;
	}

	private List<String> activeRoles(UUID userId) {
		return jdbc.sql("select role from access.role_assignments where user_id = ? and revoked_at is null")
			.param(userId).query(String.class).list();
	}

	private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
		return result
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
	}

}
