package org.gks.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.gks.backend.TestcontainersConfiguration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.gks.backend.access.Decision;
import org.gks.backend.TestSessions;
import org.gks.backend.access.OperationAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OR-02, OR-03, OR-05 (backend-api-design.md §7). Kimlik doğrulama henüz olmadığından istekler
 * spring-security-test ile sahte kimlik taşır; {@code can()} sonucu {@link OperationAccess} taklidiyle verilir.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OperationApiTests {

	/** V2 ile tohumlanan tek kurum (A-01). */
	private static final UUID ORGANIZATION_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

	private static final String PROBLEM_JSON = "application/problem+json";

	private final UUID userId = UUID.randomUUID();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private OperationRepository repository;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private JwtEncoder jwtEncoder;

	private String token;

	@MockitoBean
	private OperationAccess access;

	/** Operasyonun {@code created_by}/{@code closed_by} alanları gerçek kullanıcıya bağlıdır (V3 FK'leri). */
	@BeforeEach
	void createUserAndAllowByDefault() {
		jdbc.sql("""
				insert into identity.users (id, email, password_hash, full_name, display_name)
				values (?, ?, '{argon2}test', 'Test Koordinatör', 'Koordinatör')""")
			.params(userId, userId + "@example.org")
			.update();
		token = new TestSessions(jdbc, jwtEncoder).web(userId, true);
		given(access.canView(any(), any())).willReturn(Decision.ALLOW);
		given(access.canManage(any(), any())).willReturn(Decision.ALLOW);
	}

	@Nested
	class GetOperation {

		@Test
		void returnsViewAndOmitsEmptyOptionalFields() throws Exception {
			UUID id = insertOperation("PLANNED");

			mvc.perform(get("/api/v1/operations/{id}", id).with(TestSessions.authenticate(token)))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.name").value("Deprem tatbikatı"))
				.andExpect(jsonPath("$.state").value("PLANNED"))
				.andExpect(jsonPath("$.createdAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")))
				.andExpect(jsonPath("$.coordinators").isEmpty())
				.andExpect(jsonPath("$.description").doesNotExist())
				.andExpect(jsonPath("$.plannedEndAt").doesNotExist())
				.andExpect(jsonPath("$.activatedAt").doesNotExist())
				.andExpect(jsonPath("$.closedAt").doesNotExist());
		}

		@Test
		void unknownOperationIsNotFound() throws Exception {
			expectProblem(mvc.perform(get("/api/v1/operations/{id}", UUID.randomUUID()).with(TestSessions.authenticate(token))),
					404, "NOT_FOUND");
		}

		@Test
		void malformedIdIsNotFound() throws Exception {
			expectProblem(mvc.perform(get("/api/v1/operations/not-a-uuid").with(TestSessions.authenticate(token))),
					404, "NOT_FOUND");
		}

		@Test
		void operationOutOfScopeIsForbidden() throws Exception {
			UUID id = insertOperation("PLANNED");
			given(access.canView(any(), eq(id))).willReturn(Decision.FORBIDDEN);

			expectProblem(mvc.perform(get("/api/v1/operations/{id}", id).with(TestSessions.authenticate(token))),
					403, "FORBIDDEN");
		}

		@Test
		void requiresAuthentication() throws Exception {
			expectProblem(mvc.perform(get("/api/v1/operations/{id}", insertOperation("PLANNED"))),
					401, "AUTH_UNAUTHENTICATED");
		}

	}

	@Nested
	class UpdateOperation {

		@Test
		void updatesGivenFields() throws Exception {
			UUID id = insertOperation("ACTIVE");
			Instant plannedEnd = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

			patchJson(id, """
					{"name": "  Sel müdahalesi  ", "description": "Kuzey bölgesi", "plannedEndAt": "%s"}"""
				.formatted(plannedEnd))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Sel müdahalesi"))
				.andExpect(jsonPath("$.description").value("Kuzey bölgesi"))
				.andExpect(jsonPath("$.plannedEndAt").value(plannedEnd.toString()))
				.andExpect(jsonPath("$.state").value("ACTIVE"));

			Map<String, Object> row = row(id);
			assertThat(row.get("name")).isEqualTo("Sel müdahalesi");
			assertThat(((java.sql.Timestamp) row.get("updated_at")).toInstant())
				.isAfter(((java.sql.Timestamp) row.get("created_at")).toInstant());
		}

		@Test
		void absentFieldsAreKeptAndExplicitNullClears() throws Exception {
			UUID id = insertOperation("PLANNED");
			patchJson(id, "{\"description\": \"Geçici\"}").andExpect(status().isOk());

			patchJson(id, "{\"name\": \"Yeni ad\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Geçici"));

			patchJson(id, "{\"description\": null}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Yeni ad"))
				.andExpect(jsonPath("$.description").doesNotExist());
		}

		@ParameterizedTest(name = "{0}")
		@CsvSource(delimiter = '|', textBlock = """
				name too short           | {"name": " ab "}                         | name         | TOO_SHORT
				name too long            | {"name": "%121"}                         | name         | TOO_LONG
				name null                | {"name": null}                           | name         | REQUIRED
				name not text            | {"name": 42}                             | name         | INVALID_FORMAT
				description too long     | {"description": "%2001"}                 | description  | TOO_LONG
				planned end in past      | {"plannedEndAt": "2020-01-01T00:00:00Z"} | plannedEndAt | OUT_OF_RANGE
				planned end not a date   | {"plannedEndAt": "yarın"}                | plannedEndAt | INVALID_FORMAT
				unknown field            | {"colour": "red"}                        | colour       | UNKNOWN_FIELD
				read-only field          | {"state": "ACTIVE"}                      | state        | READ_ONLY
				""")
		void rejectsInvalidFields(String caseName, String body, String field, String code) throws Exception {
			UUID id = insertOperation("PLANNED");

			expectProblem(patchJson(id, expand(body)), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].code").value(code))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
			assertThat(row(id).get("name")).isEqualTo("Deprem tatbikatı");
		}

		@Test
		void reportsEveryInvalidField() throws Exception {
			expectProblem(patchJson(insertOperation("PLANNED"), "{\"name\": \"x\", \"description\": \"%2001\"}".replace(
					"%2001", "d".repeat(2001))), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors.length()").value(2));
		}

		@Test
		void nonObjectBodyIsRejected() throws Exception {
			expectProblem(patchJson(insertOperation("PLANNED"), "[]"), 400, "VALIDATION_FAILED");
			expectProblem(patchJson(insertOperation("PLANNED"), "{bozuk"), 400, "VALIDATION_FAILED");
		}

		@Test
		void validationComesBeforeResourceLookup() throws Exception {
			expectProblem(patchJson(UUID.randomUUID(), "{\"name\": \"x\"}"), 400, "VALIDATION_FAILED");
		}

		@Test
		void unknownOperationIsNotFound() throws Exception {
			expectProblem(patchJson(UUID.randomUUID(), "{\"name\": \"Geçerli ad\"}"), 404, "NOT_FOUND");
		}

		@Test
		void withoutManagePermissionIsForbiddenAndUnchanged() throws Exception {
			UUID id = insertOperation("ACTIVE");
			given(access.canManage(any(), eq(id))).willReturn(Decision.FORBIDDEN);

			expectProblem(patchJson(id, "{\"name\": \"Geçerli ad\"}"), 403, "FORBIDDEN");
			assertThat(row(id).get("name")).isEqualTo("Deprem tatbikatı");
		}

		@ParameterizedTest
		@CsvSource({ "COMPLETED", "CANCELLED" })
		void closedOperationCannotBeUpdated(String state) throws Exception {
			UUID id = insertOperation(state);

			expectProblem(patchJson(id, "{\"name\": \"Geçerli ad\"}"), 409, "OPERATION_CLOSED");
			assertThat(row(id).get("name")).isEqualTo("Deprem tatbikatı");
		}

	}

	@Nested
	class ChangeState {

		@Test
		void activatingSetsActivationTime() throws Exception {
			UUID id = insertOperation("PLANNED");

			changeState(id, "ACTIVE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("ACTIVE"))
				.andExpect(jsonPath("$.activatedAt").exists())
				.andExpect(jsonPath("$.closedAt").doesNotExist());
		}

		@Test
		void completingRecordsClosingTimeAndActor() throws Exception {
			UUID id = insertOperation("PLANNED");
			changeState(id, "ACTIVE").andExpect(status().isOk());
			Object activatedAt = row(id).get("activated_at");

			changeState(id, "COMPLETED")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("COMPLETED"))
				.andExpect(jsonPath("$.closedAt").exists());

			Map<String, Object> row = row(id);
			assertThat(row.get("closed_by")).isEqualTo(userId);
			assertThat(row.get("activated_at")).isEqualTo(activatedAt);
		}

		@Test
		void cancellingPlannedOperationNeverActivatesIt() throws Exception {
			changeState(insertOperation("PLANNED"), "CANCELLED")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("CANCELLED"))
				.andExpect(jsonPath("$.closedAt").exists())
				.andExpect(jsonPath("$.activatedAt").doesNotExist());
		}

		@ParameterizedTest(name = "{0} -> {1}")
		@CsvSource({
				"PLANNED, COMPLETED", "PLANNED, PLANNED", "PLANNED, SUSPENDED",
				"ACTIVE, ACTIVE", "ACTIVE, PLANNED", "ACTIVE, SUSPENDED",
				"COMPLETED, ACTIVE", "COMPLETED, CANCELLED", "CANCELLED, ACTIVE", "CANCELLED, PLANNED" })
		void disallowedTransitionIsConflict(String current, String requested) throws Exception {
			UUID id = insertOperation(current);

			expectProblem(changeState(id, requested), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value(current))
				.andExpect(jsonPath("$.requestedState").value(requested));
			assertThat(row(id).get("state")).isEqualTo(current);
		}

		@Test
		void unknownTargetStateIsValidationError() throws Exception {
			expectProblem(postJson(insertOperation("PLANNED"), "{\"targetState\": \"ARCHIVED\"}"), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("targetState"))
				.andExpect(jsonPath("$.errors[0].code").value("NOT_ALLOWED_VALUE"));
		}

		@Test
		void missingTargetStateIsRequired() throws Exception {
			expectProblem(postJson(insertOperation("PLANNED"), "{}"), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("targetState"))
				.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		}

		@Test
		void unknownFieldIsRejected() throws Exception {
			expectProblem(postJson(insertOperation("PLANNED"), "{\"targetState\": \"ACTIVE\", \"force\": true}"),
					400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("force"))
				.andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
		}

		@Test
		void withoutManagePermissionIsForbidden() throws Exception {
			UUID id = insertOperation("PLANNED");
			given(access.canManage(any(), eq(id))).willReturn(Decision.FORBIDDEN);

			expectProblem(changeState(id, "ACTIVE"), 403, "FORBIDDEN");
			assertThat(row(id).get("state")).isEqualTo("PLANNED");
		}

		@Test
		void unknownOperationIsNotFound() throws Exception {
			expectProblem(changeState(UUID.randomUUID(), "ACTIVE"), 404, "NOT_FOUND");
		}

		/** İki eşzamanlı geçişten ikincisi beklenen durumu bulamaz (DB §4.3). */
		@Test
		void conditionalUpdateDoesNotApplyWhenStateAlreadyChanged() {
			UUID id = insertOperation("ACTIVE");

			int updated = transactions.execute(tx -> repository.transition(id, OperationState.PLANNED,
					OperationState.ACTIVE, Instant.now(), null, null));

			assertThat(updated).isZero();
		}

	}

	@Nested
	class Schema {

		@Test
		void singleOrganizationIsSeeded() {
			assertThat(jdbc.sql("select id from organization.organizations").query(UUID.class).list())
				.containsExactly(ORGANIZATION_ID);
		}

		@Test
		void closedStateRequiresClosingTime() {
			assertThatThrownBy(() -> jdbc.sql("""
					insert into organization.operations (id, organization_id, name, state, activated_at, created_by)
					values (?, ?, 'Kapanmamış', 'COMPLETED', now(), ?)""")
				.params(UUID.randomUUID(), ORGANIZATION_ID, userId).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_operations_closed");
		}

		@Test
		void activeStateRequiresActivationTime() {
			assertThatThrownBy(() -> jdbc.sql("""
					insert into organization.operations (id, organization_id, name, state, created_by)
					values (?, ?, 'Etkin', 'ACTIVE', ?)""")
				.params(UUID.randomUUID(), ORGANIZATION_ID, userId).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_operations_activated");
		}

	}

	/** OR-01 henüz yok; operasyonlar doğrudan tabloya, verilen durumun kısıtlarına uygun eklenir. */
	private UUID insertOperation(String state) {
		UUID id = UUID.randomUUID();
		boolean activated = !state.equals("PLANNED") && !state.equals("CANCELLED");
		boolean closed = state.equals("COMPLETED") || state.equals("CANCELLED");
		jdbc.sql("""
				insert into organization.operations
				    (id, organization_id, name, state, activated_at, closed_at, created_by, created_at, updated_at)
				values (?, ?, 'Deprem tatbikatı', ?, ?, ?, ?, now() - interval '1 hour', now() - interval '1 hour')""")
			.params(id, ORGANIZATION_ID, state,
					activated ? java.sql.Timestamp.from(Instant.now()) : null,
					closed ? java.sql.Timestamp.from(Instant.now()) : null,
					userId)
			.update();
		return id;
	}

	private Map<String, Object> row(UUID id) {
		return jdbc.sql("select * from organization.operations where id = ?").param(id).query().singleRow();
	}

	private ResultActions patchJson(UUID id, String body) throws Exception {
		return mvc.perform(json(patch("/api/v1/operations/{id}", id), body));
	}

	private ResultActions postJson(UUID id, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/operations/{id}/state", id), body));
	}

	private ResultActions changeState(UUID id, String target) throws Exception {
		return postJson(id, "{\"targetState\": \"" + target + "\"}");
	}

	private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.with(TestSessions.authenticate(token)).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static ResultActions expectProblem(ResultActions result, int status, String code) throws Exception {
		return result
			.andExpect(status().is(status))
			.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(status))
			.andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.type").value(matchesPattern("/problems/[a-z-]+")))
			.andExpect(jsonPath("$.title").isNotEmpty())
			.andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
	}

	/** {@code "%N"} → N karakterlik metin (sınır değerleri tabloda okunur kalsın diye). */
	private static String expand(String body) {
		return body.replaceAll("\"%121\"", "\"" + "a".repeat(121) + "\"")
			.replaceAll("\"%2001\"", "\"" + "d".repeat(2001) + "\"");
	}

}
