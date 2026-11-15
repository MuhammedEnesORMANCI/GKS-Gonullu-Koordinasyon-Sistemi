package org.gks.backend.tasks;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import org.gks.backend.TestcontainersConfiguration;
import org.gks.backend.access.Decision;
import org.gks.backend.TestSessions;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.gks.backend.access.Participation;
import org.gks.backend.access.TaskAccess;
import org.gks.backend.access.TaskAccess.ViewScope;
import org.gks.backend.identity.NewUserAccount;
import org.gks.backend.identity.UserAccounts;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/**
 * Görev API testlerinin ortak bağlamı (tek PostgreSQL kapsayıcısı, aynı taklitler). İstekler gerçek giriş
 * belirteciyle yapılır; yetki ({@link TaskAccess}) ve katılım ({@link Participation}) kararları access modülünde
 * olduğundan taklit edilir. Varsayılan: her şeye izin, KOR görünümü ({@code ALL}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
abstract class TaskApiTestSupport {

	static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");
	static final String PASSWORD = "Saha-Ekibi-2026!";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserAccounts accounts;

	@Autowired
	JwtEncoder jwtEncoder;

	@MockitoBean
	TaskAccess access;

	@MockitoBean
	Participation participation;

	Person coordinator;
	UUID operationId;

	record Person(UUID id, String token) {
	}

	@BeforeEach
	void setUpCoordinator() throws Exception {
		coordinator = webPerson("Koordinatör");
		operationId = operation("ACTIVE");
		given(access.can(any(), any(), any(), anyBoolean())).willReturn(Decision.ALLOW);
		given(access.viewScope(any(), any())).willReturn(ViewScope.ALL);
		given(participation.isParticipant(any(), any())).willReturn(true);
	}

	/** Gönüllü izdüşümü: bu kişi yalnızca kendisine atanmış görevleri görür. */
	void volunteerView(UUID userId) {
		given(access.viewScope(argThat(subject -> subject != null && subject.userId().equals(userId)), any()))
			.willReturn(ViewScope.ASSIGNED);
	}

	// --- kişiler ve operasyon ---

	Person person(String displayName) throws Exception {
		UUID id = accounts.register(new NewUserAccount("kisi-" + UUID.randomUUID() + "@example.org", PASSWORD,
				displayName + " Soyadı", displayName, null));
		String email = jdbc.sql("select email from identity.users where id = ?").param(id).query(String.class).single();
		String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"%s\", \"password\": \"%s\", \"clientType\": \"ANDROID\", \"installationId\": \"gorev-testi-01\"}"
				.formatted(email, PASSWORD)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Person(id, JsonPath.read(body, "$.accessToken"));
	}

	/** KOR: web+MFA oturumu (TOTP akışı her testte tekrarlanmaz; filtre ve kapılar gerçek). */
	Person webPerson(String displayName) {
		UUID id = user(displayName);
		return new Person(id, new TestSessions(jdbc, jwtEncoder).web(id, true));
	}

	/** Giriş yapmayacak kişiler için hızlı kayıt. */
	UUID user(String displayName) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}kullanilmaz', ?, ?)""")
			.params(id, id + "@example.org", displayName + " Soyadı", displayName).update();
		return id;
	}

	UUID operation(String state) {
		UUID id = UUID.randomUUID();
		boolean activated = state.equals("ACTIVE") || state.equals("COMPLETED");
		boolean closed = state.equals("COMPLETED") || state.equals("CANCELLED");
		jdbc.sql("""
				insert into organization.operations (id, organization_id, name, state, activated_at, closed_at, created_by)
				values (?, ?, 'Deprem müdahalesi', ?, ?, ?, ?)""")
			.params(id, ORGANIZATION_ID, state, activated ? Timestamp.from(Instant.now()) : null,
					closed ? Timestamp.from(Instant.now()) : null, coordinator.id())
			.update();
		return id;
	}

	/** Grup doğrudan groups şemasına yazılır (ilk kişi lider); grup API'si bu testlerin konusu değil. */
	UUID group(UUID operation, UUID leader, UUID... members) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into groups.groups (id, organization_id, operation_id, name, created_by)
				values (?, ?, ?, ?, ?)""")
			.params(id, ORGANIZATION_ID, operation, "Grup " + id.toString().substring(0, 8), coordinator.id()).update();
		addMember(id, leader, "LEADER", 0);
		for (int i = 0; i < members.length; i++) {
			addMember(id, members[i], "MEMBER", i + 1);
		}
		return id;
	}

	void addMember(UUID groupId, UUID userId, String role, int order) {
		jdbc.sql("""
				insert into groups.group_memberships (id, group_id, user_id, role, added_by, joined_at)
				values (?, ?, ?, ?, ?, now() + make_interval(secs => ?))""")
			.params(UUID.randomUUID(), groupId, userId, role, coordinator.id(), order / 1000.0).update();
	}

	// --- görev uçları ---

	ResultActions createTask(UUID operation, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/operations/{id}/tasks", operation), coordinator.token(), body));
	}

	static String taskBody(Integer capacity, UUID... users) {
		String ids = Arrays.stream(users).map(u -> "\"" + u + "\"").collect(Collectors.joining(", "));
		return "{\"title\": \"Su dağıtımı\", \"targets\": {\"userIds\": [" + ids + "]}"
				+ (capacity != null ? ", \"capacity\": " + capacity : "") + "}";
	}

	UUID draft(Integer capacity, UUID... users) throws Exception {
		String body = createTask(operationId, taskBody(capacity, users)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	ResultActions publish(UUID taskId, int version, String key) throws Exception {
		MockHttpServletRequestBuilder request = json(post("/api/v1/tasks/{id}/publish", taskId), coordinator.token(),
				"{\"version\": " + version + "}");
		return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	UUID published(Integer capacity, UUID... users) throws Exception {
		UUID taskId = draft(capacity, users);
		publish(taskId, 1, newKey()).andExpect(status().isOk());
		return taskId;
	}

	ResultActions changeState(UUID taskId, String target) throws Exception {
		return mvc.perform(json(post("/api/v1/tasks/{id}/state", taskId), coordinator.token(),
				"{\"targetState\": \"" + target + "\"}"));
	}

	ResultActions accept(UUID assignmentId, String token, String key) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/assignments/{id}/accept", assignmentId)
			.with(TestSessions.authenticate(token));
		return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	ResultActions reject(UUID assignmentId, String token, String key) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/assignments/{id}/reject", assignmentId)
			.with(TestSessions.authenticate(token));
		return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	// --- veritabanı okumaları ---

	UUID assignment(UUID taskId, UUID userId) {
		return jdbc.sql("select id from tasks.task_assignments where task_id = ? and user_id = ?")
			.params(taskId, userId).query(UUID.class).single();
	}

	String assignmentStatus(UUID assignmentId) {
		return jdbc.sql("select status from tasks.task_assignments where id = ?").param(assignmentId)
			.query(String.class).single();
	}

	int acceptedCount(UUID taskId) {
		return jdbc.sql("select accepted_count from tasks.tasks where id = ?").param(taskId).query(Integer.class)
			.single();
	}

	String taskState(UUID taskId) {
		return jdbc.sql("select state from tasks.tasks where id = ?").param(taskId).query(String.class).single();
	}

	int idempotencyRows(UUID principal) {
		return jdbc.sql("select count(*) from platform.idempotency_keys where principal_key = ?")
			.param(principal.toString()).query(Integer.class).single();
	}

	static String newKey() {
		return UUID.randomUUID().toString();
	}

	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
		return request.with(TestSessions.authenticate(token)).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
		return result
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.type").value(matchesPattern("/problems/[a-z-]+")))
			.andExpect(jsonPath("$.title").isString())
			.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
	}

	static ResultActions expectFieldError(ResultActions result, String field, String code) throws Exception {
		return expectProblem(result, 400, "VALIDATION_FAILED")
			.andExpect(jsonPath("$.errors[?(@.field == '" + field + "')].code").value(code));
	}

}
