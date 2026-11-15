package org.gks.backend.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import org.gks.backend.access.Decision;
import org.gks.backend.TestSessions;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.gks.backend.access.Subject;
import org.gks.backend.access.TaskAccess.TaskAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** TK-01 – TK-06 (backend-api-design.md §9.1 – §9.3; AC-13.*, AC-15.*, TD-16). */
class TaskManagementApiTests extends TaskApiTestSupport {

	@Nested
	class CreateTask {

		/** AC-13.1, AC-13.3, AC-15.2; §9.3 varsayılanları; DB {@code version = 1}. */
		@Test
		void createsDraftWithDefaults() throws Exception {
			UUID volunteer = user("Gönüllü");

			String body = createTask(operationId, taskBody(null, volunteer))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern("/api/v1/tasks/[0-9a-f-]{36}")))
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.title").value("Su dağıtımı"))
				.andExpect(jsonPath("$.state").value("DRAFT"))
				.andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.priority").value("NORMAL"))
				.andExpect(jsonPath("$.responseWindowMinutes").value(60))
				.andExpect(jsonPath("$.acceptedCount").value(0))
				.andExpect(jsonPath("$.pendingCount").value(0))
				.andExpect(jsonPath("$.overdueCount").value(0))
				.andExpect(jsonPath("$.capacity").doesNotExist())
				.andExpect(jsonPath("$.timeWindow").doesNotExist())
				.andExpect(jsonPath("$.description").doesNotExist())
				.andExpect(jsonPath("$.publishedAt").doesNotExist())
				.andExpect(jsonPath("$.targets.userIds[0]").value(volunteer.toString()))
				.andExpect(jsonPath("$.targets.groupIds").isEmpty())
				.andExpect(jsonPath("$.createdBy.id").value(coordinator.id().toString()))
				.andExpect(jsonPath("$.createdBy.displayName").value("Koordinatör"))
				.andExpect(jsonPath("$.history.length()").value(1))
				.andExpect(jsonPath("$.history[0].state").value("DRAFT"))
				.andExpect(jsonPath("$.history[0].changedBy.id").value(coordinator.id().toString()))
				.andExpect(jsonPath("$.createdAt").isString())
				.andReturn().getResponse().getContentAsString();
			UUID taskId = UUID.fromString(JsonPath.read(body, "$.id"));

			assertThat(jdbc.sql("select target_expr->'users'->>0 from tasks.tasks where id = ?").param(taskId)
				.query(String.class).single()).isEqualTo(volunteer.toString());
			assertThat(jdbc.sql("select count(*) from tasks.task_assignments where task_id = ?").param(taskId)
				.query(Integer.class).single()).isZero();
			verify(access).can(new Subject(coordinator.id(), true, true), TaskAction.MANAGE, operationId, false);
		}

		@Test
		void storesAllOptionalFields() throws Exception {
			UUID volunteer = user("Gönüllü");
			UUID groupId = group(operationId, user("Lider"));

			createTask(operationId, """
					{"title": "  Çadır kurulumu  ", "description": "Merkez parkta 20 çadır", "priority": "URGENT",
					 "meetingPoint": "Belediye önü", "capacity": 5, "responseWindowMinutes": 30,
					 "timeWindow": {"start": "2026-11-20T09:00:00+03:00", "end": "2026-11-20T12:00:00Z"},
					 "targets": {"userIds": ["%s"], "groupIds": ["%s"]}}""".formatted(volunteer, groupId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("Çadır kurulumu"))
				.andExpect(jsonPath("$.description").value("Merkez parkta 20 çadır"))
				.andExpect(jsonPath("$.priority").value("URGENT"))
				.andExpect(jsonPath("$.meetingPoint").value("Belediye önü"))
				.andExpect(jsonPath("$.capacity").value(5))
				.andExpect(jsonPath("$.responseWindowMinutes").value(30))
				.andExpect(jsonPath("$.timeWindow.start").value("2026-11-20T06:00:00Z"))
				.andExpect(jsonPath("$.timeWindow.end").value("2026-11-20T12:00:00Z"))
				.andExpect(jsonPath("$.targets.groupIds[0]").value(groupId.toString()));
		}

		/** LD-51 başlık 3..120; §9.3 alan kuralları. */
		@ParameterizedTest
		@CsvSource(delimiter = '|', textBlock = """
				{"title": "ab", "targets": {"userIds": ["%s"]}}                                     | title                   | TOO_SHORT
				{"title": "%s", "targets": {"userIds": ["%s"]}}                                     | title                   | TOO_LONG
				{"targets": {"userIds": ["%s"]}}                                                    | title                   | REQUIRED
				{"title": "   ", "targets": {"userIds": ["%s"]}}                                    | title                   | REQUIRED
				{"title": "Görev\\u0007", "targets": {"userIds": ["%s"]}}                           | title                   | INVALID_FORMAT
				{"title": "Görev"}                                                                  | targets                 | REQUIRED
				{"title": "Görev", "targets": {"userIds": [], "groupIds": []}}                      | targets                 | REQUIRED
				{"title": "Görev", "targets": {"userIds": ["yok"]}}                                 | targets.userIds[0]      | INVALID_FORMAT
				{"title": "Görev", "targets": {"userIds": ["%s", "%1$s"]}}                          | targets.userIds[1]      | NOT_ALLOWED_VALUE
				{"title": "Görev", "targets": {"userIds": ["%s"], "roles": []}}                     | targets.roles           | UNKNOWN_FIELD
				{"title": "Görev", "capacity": 0, "targets": {"userIds": ["%s"]}}                   | capacity                | OUT_OF_RANGE
				{"title": "Görev", "capacity": 501, "targets": {"userIds": ["%s"]}}                 | capacity                | OUT_OF_RANGE
				{"title": "Görev", "capacity": "beş", "targets": {"userIds": ["%s"]}}               | capacity                | INVALID_FORMAT
				{"title": "Görev", "responseWindowMinutes": 4, "targets": {"userIds": ["%s"]}}      | responseWindowMinutes   | OUT_OF_RANGE
				{"title": "Görev", "responseWindowMinutes": 1441, "targets": {"userIds": ["%s"]}}   | responseWindowMinutes   | OUT_OF_RANGE
				{"title": "Görev", "priority": "CRITICAL", "targets": {"userIds": ["%s"]}}          | priority                | NOT_ALLOWED_VALUE
				{"title": "Görev", "meetingPoint": "%s", "targets": {"userIds": ["%s"]}}            | meetingPoint            | TOO_LONG
				{"title": "Görev", "description": "%s", "targets": {"userIds": ["%s"]}}             | description             | TOO_LONG
				{"title": "Görev", "timeWindow": {"start": "2026-11-20T12:00:00Z", "end": "2026-11-20T09:00:00Z"}, "targets": {"userIds": ["%s"]}} | timeWindow.end | OUT_OF_RANGE
				{"title": "Görev", "timeWindow": {"end": "2026-11-20T09:00:00Z"}, "targets": {"userIds": ["%s"]}} | timeWindow.start | REQUIRED
				{"title": "Görev", "timeWindow": {"start": "dün"}, "targets": {"userIds": ["%s"]}} | timeWindow.start        | INVALID_FORMAT
				{"title": "Görev", "state": "PUBLISHED", "targets": {"userIds": ["%s"]}}            | state                   | UNKNOWN_FIELD
				""")
		void rejectsInvalidRequest(String template, String field, String code) throws Exception {
			String volunteer = user("Gönüllü").toString();
			String body = switch (field) {
				case "title" -> template.contains("\"%s\", \"targets\"") && code.equals("TOO_LONG")
						? template.formatted("a".repeat(121), volunteer) : template.formatted(volunteer);
				case "meetingPoint" -> template.formatted("y".repeat(301), volunteer);
				case "description" -> template.formatted("d".repeat(2001), volunteer);
				default -> template.formatted(volunteer);
			};

			expectFieldError(createTask(operationId, body), field, code);
			assertThat(taskCount()).isZero();
		}

		@Test
		void titleBoundariesAreAccepted() throws Exception {
			UUID volunteer = user("Gönüllü");
			createTask(operationId, "{\"title\": \"abc\", \"targets\": {\"userIds\": [\"" + volunteer + "\"]}}")
				.andExpect(status().isCreated());
			createTask(operationId, "{\"title\": \"" + "ğ".repeat(120) + "\", \"targets\": {\"userIds\": [\"" + volunteer
					+ "\"]}}").andExpect(status().isCreated());
		}

		/** §3.3: doğrulama (5) kaynak yüklemeden (6) önce gelir. */
		@Test
		void validationPrecedesNotFound() throws Exception {
			expectFieldError(createTask(UUID.randomUUID(), "{\"title\": \"ab\"}"), "title", "TOO_SHORT");
			expectProblem(createTask(UUID.randomUUID(), taskBody(null, user("Gönüllü"))), 404, "NOT_FOUND");
		}

		@Test
		void withoutManagePermissionIsForbidden() throws Exception {
			given(access.can(any(), eq(TaskAction.MANAGE), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(createTask(operationId, taskBody(null, user("Gönüllü"))), 403, "FORBIDDEN");
			assertThat(taskCount()).isZero();
		}

		/** §9.3 {@code TaskTargets}: katılımcı olmayan kişi, başka operasyonun veya arşivli grup → 403. */
		@Test
		void targetsOutsideScopeAreForbidden() throws Exception {
			UUID outsider = user("Dışarıdan");
			given(participation.isParticipant(operationId, outsider)).willReturn(false);
			UUID foreignGroup = group(operation("ACTIVE"), user("Lider"));
			UUID archivedGroup = group(operationId, user("Lider"));
			jdbc.sql("update groups.groups set archived_at = now() where id = ?").param(archivedGroup).update();

			expectProblem(createTask(operationId, taskBody(null, outsider)), 403, "FORBIDDEN");
			for (UUID groupId : List.of(foreignGroup, archivedGroup, UUID.randomUUID())) {
				expectProblem(createTask(operationId,
						"{\"title\": \"Görev\", \"targets\": {\"groupIds\": [\"" + groupId + "\"]}}"), 403, "FORBIDDEN");
			}
			assertThat(taskCount()).isZero();
		}

		@ParameterizedTest
		@CsvSource({ "COMPLETED", "CANCELLED" })
		void closedOperationRejectsNewTasks(String state) throws Exception {
			expectProblem(createTask(operation(state), taskBody(null, user("Gönüllü"))), 409, "OPERATION_CLOSED");
		}

		@Test
		void plannedOperationAcceptsTasks() throws Exception {
			createTask(operation("PLANNED"), taskBody(null, user("Gönüllü"))).andExpect(status().isCreated());
		}

		/** TK-01 {@code d}: aynı anahtar ve gövde → ilk yanıt aynen, tek görev. */
		@Test
		void supportsIdempotencyKey() throws Exception {
			String body = taskBody(3, user("Gönüllü"));
			String key = newKey();

			ResultActions first = mvc.perform(json(post("/api/v1/operations/{id}/tasks", operationId),
					coordinator.token(), body).header("Idempotency-Key", key)).andExpect(status().isCreated());
			ResultActions second = mvc.perform(json(post("/api/v1/operations/{id}/tasks", operationId),
					coordinator.token(), body).header("Idempotency-Key", key))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andExpect(header().string("Location", first.andReturn().getResponse().getHeader("Location")));

			assertThat(second.andReturn().getResponse().getContentAsString())
				.isEqualTo(first.andReturn().getResponse().getContentAsString());
			assertThat(taskCount()).isOne();
			expectProblem(mvc.perform(json(post("/api/v1/operations/{id}/tasks", operationId), coordinator.token(),
					taskBody(4, user("Başka"))).header("Idempotency-Key", key)), 422, "IDEMPOTENCY_KEY_MISMATCH");
			expectFieldError(mvc.perform(json(post("/api/v1/operations/{id}/tasks", operationId), coordinator.token(),
					body).header("Idempotency-Key", "anahtar")), "Idempotency-Key", "INVALID_FORMAT");
			assertThat(taskCount()).isOne();
		}

		/** Yalnızca 2xx saklanır: reddedilen istek anahtar satırı bırakmaz (§3.6). */
		@Test
		void rejectedRequestLeavesNoIdempotencyRow() throws Exception {
			UUID closed = operation("COMPLETED");
			expectProblem(mvc.perform(json(post("/api/v1/operations/{id}/tasks", closed), coordinator.token(),
					taskBody(null, user("Gönüllü"))).header("Idempotency-Key", newKey())), 409, "OPERATION_CLOSED");

			assertThat(idempotencyRows(coordinator.id())).isZero();
		}

		@Test
		void schemaEnforcesLengthsAndCapacity() {
			UUID taskId = UUID.randomUUID();
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
					insert into tasks.tasks (id, operation_id, title, created_by) values (?, ?, 'ab', ?)""")
				.params(taskId, operationId, coordinator.id()).update())
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_tasks_lengths");
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
					insert into tasks.tasks (id, operation_id, title, capacity, accepted_count, created_by)
					values (?, ?, 'Görev', 1, 2, ?)""")
				.params(taskId, operationId, coordinator.id()).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_tasks_capacity_not_exceeded");
		}

	}

	@Nested
	class UpdateTask {

		@Test
		void updatesFieldsAndIncrementsVersion() throws Exception {
			UUID taskId = draft(5, user("Gönüllü"));

			patchTask(taskId, """
					{"version": 1, "title": "Yeni başlık", "description": "Ayrıntı", "priority": "HIGH", "capacity": 3}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.title").value("Yeni başlık"))
				.andExpect(jsonPath("$.description").value("Ayrıntı"))
				.andExpect(jsonPath("$.priority").value("HIGH"))
				.andExpect(jsonPath("$.capacity").value(3))
				.andExpect(jsonPath("$.responseWindowMinutes").value(60));
			patchTask(taskId, "{\"version\": 2, \"description\": null, \"capacity\": null}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(3))
				.andExpect(jsonPath("$.title").value("Yeni başlık"))
				.andExpect(jsonPath("$.description").doesNotExist())
				.andExpect(jsonPath("$.capacity").doesNotExist());
		}

		/** LD-52: gönderilmeyen alan korunur; {@code null} isteğe bağlı alanı temizler (varsayılanlı alan varsayılana döner). */
		@Test
		void absentFieldsAreKeptAndNullClearsOptionalFields() throws Exception {
			UUID volunteer = user("Gönüllü");
			String body = createTask(operationId, """
					{"title": "Lojistik", "description": "Ayrıntı", "priority": "HIGH", "meetingPoint": "Park",
					 "capacity": 4, "responseWindowMinutes": 30,
					 "timeWindow": {"start": "2026-11-20T09:00:00Z", "end": "2026-11-20T12:00:00Z"},
					 "targets": {"userIds": ["%s"]}}""".formatted(volunteer))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			UUID taskId = UUID.fromString(JsonPath.read(body, "$.id"));

			patchTask(taskId, "{\"version\": 1}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.title").value("Lojistik"))
				.andExpect(jsonPath("$.description").value("Ayrıntı"))
				.andExpect(jsonPath("$.priority").value("HIGH"))
				.andExpect(jsonPath("$.meetingPoint").value("Park"))
				.andExpect(jsonPath("$.capacity").value(4))
				.andExpect(jsonPath("$.responseWindowMinutes").value(30))
				.andExpect(jsonPath("$.timeWindow.start").value("2026-11-20T09:00:00Z"))
				.andExpect(jsonPath("$.targets.userIds[0]").value(volunteer.toString()));

			patchTask(taskId, """
					{"version": 2, "description": null, "meetingPoint": null, "capacity": null, "timeWindow": null,
					 "priority": null, "responseWindowMinutes": null}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Lojistik"))
				.andExpect(jsonPath("$.description").doesNotExist())
				.andExpect(jsonPath("$.meetingPoint").doesNotExist())
				.andExpect(jsonPath("$.capacity").doesNotExist())
				.andExpect(jsonPath("$.timeWindow").doesNotExist())
				.andExpect(jsonPath("$.priority").value("NORMAL"))
				.andExpect(jsonPath("$.responseWindowMinutes").value(60));
			assertThat(jdbc.sql("""
					select description is null and meeting_point is null and capacity is null and time_window is null
					  from tasks.tasks where id = ?""").param(taskId).query(Boolean.class).single()).isTrue();

			// Zorunlu alana null: 400 REQUIRED ve hiçbir alan değişmez.
			expectFieldError(patchTask(taskId, "{\"version\": 3, \"title\": null, \"description\": \"Yeni\"}"),
					"title", "REQUIRED");
			expectFieldError(patchTask(taskId, "{\"version\": 3, \"targets\": null}"), "targets", "REQUIRED");
			assertThat(jdbc.sql("select version from tasks.tasks where id = ?").param(taskId).query(Integer.class)
				.single()).isEqualTo(3);
		}

		@Test
		void draftTargetsMayChange() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			UUID other = user("Diğer");

			patchTask(taskId, "{\"version\": 1, \"targets\": {\"userIds\": [\"" + other + "\"]}}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targets.userIds[0]").value(other.toString()));
		}

		@Test
		void staleVersionIsConflict() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			patchTask(taskId, "{\"version\": 1, \"title\": \"Birinci\"}").andExpect(status().isOk());

			expectProblem(patchTask(taskId, "{\"version\": 1, \"title\": \"İkinci\"}"), 409, "VERSION_CONFLICT")
				.andExpect(jsonPath("$.currentVersion").value(2));
		}

		@ParameterizedTest
		@CsvSource(delimiter = '|', textBlock = """
				{"title": "X yok"}                         | version        | REQUIRED
				{"version": 1, "state": "DONE"}            | state          | READ_ONLY
				{"version": 1, "acceptedCount": 9}         | acceptedCount  | READ_ONLY
				{"version": 1, "title": null}              | title          | REQUIRED
				{"version": null, "title": "Başlık"}       | version        | REQUIRED
				{"version": 1, "targets": null}            | targets        | REQUIRED
				{"version": 1, "color": "red"}             | color          | UNKNOWN_FIELD
				""")
		void rejectsInvalidPatch(String body, String field, String code) throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));

			expectFieldError(patchTask(taskId, body), field, code);
		}

		@Test
		void targetsAreReadOnlyAfterPublish() throws Exception {
			UUID taskId = published(null, user("Gönüllü"));

			expectFieldError(patchTask(taskId, "{\"version\": 2, \"targets\": {\"userIds\": [\"" + user("Yeni")
					+ "\"]}}"), "targets", "READ_ONLY");
		}

		/** AC-13.7, E-2. */
		@Test
		void capacityCannotDropBelowAcceptedAndEqualClosesPending() throws Exception {
			Person first = person("Birinci");
			Person second = person("İkinci");
			UUID third = user("Üçüncü");
			UUID taskId = published(5, first.id(), second.id(), third);
			accept(assignment(taskId, first.id()), first.token(), newKey()).andExpect(status().isOk());
			accept(assignment(taskId, second.id()), second.token(), newKey()).andExpect(status().isOk());

			expectProblem(patchTask(taskId, "{\"version\": 2, \"capacity\": 1}"), 409, "CAPACITY_BELOW_ACCEPTED")
				.andExpect(jsonPath("$.acceptedCount").value(2));
			assertThat(assignmentStatus(assignment(taskId, third))).isEqualTo("PENDING");

			patchTask(taskId, "{\"version\": 2, \"capacity\": 2}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.capacity").value(2))
				.andExpect(jsonPath("$.pendingCount").value(0));
			assertThat(assignmentStatus(assignment(taskId, third))).isEqualTo("CAPACITY_FULL");

			// N-17: kapasite artışı CAPACITY_FULL atamaları kendiliğinden açmaz.
			patchTask(taskId, "{\"version\": 3, \"capacity\": 10}").andExpect(status().isOk());
			assertThat(assignmentStatus(assignment(taskId, third))).isEqualTo("CAPACITY_FULL");
		}

		@ParameterizedTest
		@CsvSource({ "DONE", "CANCELLED" })
		void finishedTaskCannotBeEdited(String finalState) throws Exception {
			UUID taskId = published(null, user("Gönüllü"));
			changeState(taskId, "IN_PROGRESS").andExpect(status().isOk());
			changeState(taskId, finalState).andExpect(status().isOk());

			expectProblem(patchTask(taskId, "{\"version\": 4, \"title\": \"Geç\"}"), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value(finalState));
		}

		@Test
		void withoutManagePermissionIsForbiddenAndUnknownIsNotFound() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			expectProblem(patchTask(UUID.randomUUID(), "{\"version\": 1}"), 404, "NOT_FOUND");
			given(access.can(any(), eq(TaskAction.MANAGE), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(patchTask(taskId, "{\"version\": 1, \"title\": \"Yetkisiz\"}"), 403, "FORBIDDEN");
		}

		private ResultActions patchTask(UUID taskId, String body) throws Exception {
			return mvc.perform(json(patch("/api/v1/tasks/{id}", taskId), coordinator.token(), body));
		}

	}

	@Nested
	class PublishTask {

		/** TD-16, AC-13.2: kişi hedefleri + grupların yayın anındaki aktif üyeleri; kişi başına tek atama. */
		@Test
		void snapshotsAssignmentsAtPublish() throws Exception {
			UUID direct = user("Doğrudan");
			UUID leader = user("Lider");
			UUID member = user("Üye");
			UUID groupId = group(operationId, leader, member, direct);
			String body = createTask(operationId, """
					{"title": "Yardım kolisi", "responseWindowMinutes": 15,
					 "targets": {"userIds": ["%s"], "groupIds": ["%s"]}}""".formatted(direct, groupId))
				.andReturn().getResponse().getContentAsString();
			UUID taskId = UUID.fromString(JsonPath.read(body, "$.id"));

			publish(taskId, 1, newKey())
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.state").value("PUBLISHED"))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.publishedAt").isString())
				.andExpect(jsonPath("$.pendingCount").value(3))
				.andExpect(jsonPath("$.history[*].state").value(org.hamcrest.Matchers.contains("DRAFT", "PUBLISHED")));

			Map<UUID, String> via = jdbc.sql("""
					select user_id, coalesce(via_group_id::text, '-') as via, source, status
					  from tasks.task_assignments where task_id = ?""")
				.param(taskId)
				.query((rs, row) -> {
					assertThat(rs.getString("source")).isEqualTo("SNAPSHOT");
					assertThat(rs.getString("status")).isEqualTo("PENDING");
					return Map.entry(rs.getObject("user_id", UUID.class), rs.getString("via"));
				})
				.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			assertThat(via).containsExactlyInAnyOrderEntriesOf(
					Map.of(direct, "-", leader, groupId.toString(), member, groupId.toString()));
			assertThat(jdbc.sql("""
					select bool_and(respond_by = assigned_at + interval '15 minutes')
					  from tasks.task_assignments where task_id = ?""").param(taskId).query(Boolean.class).single())
				.isTrue();

			// Yayından sonra gruba katılan kişi otomatik atama almaz.
			addMember(groupId, user("Geç katılan"), "MEMBER", 9);
			assertThat(jdbc.sql("select count(*) from tasks.task_assignments where task_id = ?").param(taskId)
				.query(Integer.class).single()).isEqualTo(3);
		}

		/** TK-03 {@code Z}: başlık zorunlu; eksikse durum değişmez. */
		@Test
		void idempotencyKeyIsRequired() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));

			expectFieldError(publish(taskId, 1, null), "Idempotency-Key", "REQUIRED");
			expectFieldError(publish(UUID.randomUUID(), 1, null), "Idempotency-Key", "REQUIRED");
			assertThat(taskState(taskId)).isEqualTo("DRAFT");
		}

		@Test
		void sameKeyReplaysAndDifferentBodyIsMismatch() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			String key = newKey();
			String first = publish(taskId, 1, key).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();

			String replay = publish(taskId, 1, key)
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();
			assertThat(replay).isEqualTo(first);
			assertThat(jdbc.sql("select count(*) from tasks.task_assignments where task_id = ?").param(taskId)
				.query(Integer.class).single()).isOne();

			// §3.3: uyuşmazlık (8) kaynak kuralından (9) önce gelir.
			expectProblem(publish(taskId, 7, key), 422, "IDEMPOTENCY_KEY_MISMATCH");
			expectProblem(changeStateWithKey(taskId, "CANCELLED", key), 422, "IDEMPOTENCY_KEY_MISMATCH");
			assertThat(taskState(taskId)).isEqualTo("PUBLISHED");
		}

		@Test
		void onlyDraftWithCurrentVersionIsPublished() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			expectProblem(publish(taskId, 3, newKey()), 409, "VERSION_CONFLICT")
				.andExpect(jsonPath("$.currentVersion").value(1));
			publish(taskId, 1, newKey()).andExpect(status().isOk());

			expectProblem(publish(taskId, 2, newKey()), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value("PUBLISHED"))
				.andExpect(jsonPath("$.requestedState").value("PUBLISHED"));
			assertThat(idempotencyRows(coordinator.id())).isOne();   // yalnızca başarılı yayının satırı (2xx)
		}

		@Test
		void failedPublishStoresNoIdempotencyRow() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			String key = newKey();

			expectProblem(publish(taskId, 5, key), 409, "VERSION_CONFLICT");
			assertThat(idempotencyRows(coordinator.id())).isZero();
			// Satır olmadığı için aynı anahtar düzeltilmiş gövdeyle yeni istek sayılır.
			publish(taskId, 1, key).andExpect(status().isOk());
		}

		/** §3.2, §9.1: yayın anında atanabilecek kimse yoksa {@code 422 VALIDATION_FAILED} ({@code EMPTY_AUDIENCE}). */
		@Test
		void emptyAudienceIsRejected() throws Exception {
			UUID volunteer = user("Gönüllü");
			UUID taskId = draft(null, volunteer);
			given(participation.isParticipant(operationId, volunteer)).willReturn(false);

			String key = newKey();
			expectProblem(publish(taskId, 1, key), 422, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("targets"))
				.andExpect(jsonPath("$.errors[0].code").value("EMPTY_AUDIENCE"))
				.andExpect(jsonPath("$.errors[0].message").isString());
			assertThat(taskState(taskId)).isEqualTo("DRAFT");
			assertThat(jdbc.sql("select count(*) from tasks.task_assignments where task_id = ?").param(taskId)
				.query(Integer.class).single()).isZero();
			assertThat(idempotencyRows(coordinator.id())).isZero();   // 4xx saklanmaz

			// Sözdizimsel doğrulama değişmedi: 400 VALIDATION_FAILED.
			expectProblem(publish(taskId, 0, key), 400, "VALIDATION_FAILED")
				.andExpect(jsonPath("$.errors[0].field").value("version"));
		}

		@Test
		void closedOperationRejectsPublish() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			jdbc.sql("update organization.operations set state = 'COMPLETED', closed_at = now() where id = ?")
				.param(operationId).update();

			expectProblem(publish(taskId, 1, newKey()), 409, "OPERATION_CLOSED");
		}

		@Test
		void forbiddenBeforeIdempotency() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			given(access.can(any(), eq(TaskAction.MANAGE), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(publish(taskId, 1, newKey()), 403, "FORBIDDEN");
			expectProblem(publish(UUID.randomUUID(), 1, newKey()), 404, "NOT_FOUND");
			assertThat(idempotencyRows(coordinator.id())).isZero();
		}

	}

	@Nested
	class ChangeState {

		/** §9.2 geçiş tablosu (AC-15.1). */
		@ParameterizedTest
		@CsvSource({
				"DRAFT, CANCELLED, 200", "DRAFT, IN_PROGRESS, 409", "DRAFT, DONE, 409", "DRAFT, DRAFT, 409",
				"PUBLISHED, IN_PROGRESS, 200", "PUBLISHED, CANCELLED, 200", "PUBLISHED, DONE, 409",
				"PUBLISHED, DRAFT, 409", "IN_PROGRESS, DONE, 200", "IN_PROGRESS, CANCELLED, 200",
				"IN_PROGRESS, IN_PROGRESS, 409", "DONE, CANCELLED, 409", "DONE, IN_PROGRESS, 409",
				"CANCELLED, IN_PROGRESS, 409", "CANCELLED, DONE, 409" })
		void followsTransitionTable(String from, String to, int expected) throws Exception {
			UUID taskId = inState(from);

			ResultActions result = changeState(taskId, to);
			if (expected == 200) {
				result.andExpect(status().isOk()).andExpect(jsonPath("$.state").value(to));
				assertThat(taskState(taskId)).isEqualTo(to);
			}
			else {
				expectProblem(result, 409, "INVALID_STATE_TRANSITION")
					.andExpect(jsonPath("$.currentState").value(from))
					.andExpect(jsonPath("$.requestedState").value(to));
				assertThat(taskState(taskId)).isEqualTo(from);
			}
		}

		@Test
		void publishedIsNotRequestableHereAndTargetIsRequired() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));

			expectFieldError(changeState(taskId, "PUBLISHED"), "targetState", "NOT_ALLOWED_VALUE");
			expectFieldError(mvc.perform(json(post("/api/v1/tasks/{id}/state", taskId), coordinator.token(), "{}")),
					"targetState", "REQUIRED");
			expectFieldError(changeState(taskId, "ARCHIVED"), "targetState", "NOT_ALLOWED_VALUE");
		}

		/** AC-15.2: her geçiş yapan ve zamanla geçmişte görünür; DB'de zaman damgaları. */
		@Test
		void historyRecordsEveryChange() throws Exception {
			UUID taskId = published(null, user("Gönüllü"));
			changeState(taskId, "IN_PROGRESS").andExpect(status().isOk());

			changeState(taskId, "DONE")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(4))
				.andExpect(jsonPath("$.history[*].state")
					.value(org.hamcrest.Matchers.contains("DRAFT", "PUBLISHED", "IN_PROGRESS", "DONE")))
				.andExpect(jsonPath("$.history[3].changedBy.displayName").value("Koordinatör"))
				.andExpect(jsonPath("$.history[3].changedAt").isString());
			assertThat(jdbc.sql("""
					select completed_at is not null and state_changed_by = ? from tasks.tasks where id = ?""")
				.params(coordinator.id(), taskId).query(Boolean.class).single()).isTrue();
			assertThat(jdbc.sql("select from_state from tasks.task_state_changes where task_id = ? order by id")
				.param(taskId).query(String.class).list()).containsExactly(null, "DRAFT", "PUBLISHED", "IN_PROGRESS");
		}

		/** BOŞLUK-14: taslak silinmez, iptal edilir. */
		@Test
		void draftIsCancelledNotDeleted() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));

			changeState(taskId, "CANCELLED").andExpect(status().isOk());
			assertThat(jdbc.sql("select cancelled_at is not null and published_at is null from tasks.tasks where id = ?")
				.param(taskId).query(Boolean.class).single()).isTrue();
		}

		/** TK-04 {@code d}: aynı anahtarla tekrar ilk yanıtı döner; ikinci geçiş denenmez. */
		@Test
		void supportsIdempotencyKey() throws Exception {
			UUID taskId = published(null, user("Gönüllü"));
			String key = newKey();
			String first = changeStateWithKey(taskId, "CANCELLED", key).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

			String replay = changeStateWithKey(taskId, "CANCELLED", key)
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();
			assertThat(replay).isEqualTo(first);
			expectProblem(changeState(taskId, "CANCELLED"), 409, "INVALID_STATE_TRANSITION");
		}

		@Test
		void withoutManagePermissionIsForbidden() throws Exception {
			UUID taskId = draft(null, user("Gönüllü"));
			given(access.can(any(), eq(TaskAction.MANAGE), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(changeState(taskId, "CANCELLED"), 403, "FORBIDDEN");
			assertThat(taskState(taskId)).isEqualTo("DRAFT");
		}

		private UUID inState(String state) throws Exception {
			UUID taskId = state.equals("DRAFT") ? draft(null, user("Gönüllü")) : published(null, user("Gönüllü"));
			switch (state) {
				case "IN_PROGRESS" -> changeState(taskId, "IN_PROGRESS").andExpect(status().isOk());
				case "DONE" -> {
					changeState(taskId, "IN_PROGRESS").andExpect(status().isOk());
					changeState(taskId, "DONE").andExpect(status().isOk());
				}
				case "CANCELLED" -> changeState(taskId, "CANCELLED").andExpect(status().isOk());
				default -> {
				}
			}
			return taskId;
		}

	}

	@Nested
	class ManualAssignments {

		/** TK-05, TD-16: yayından sonra elle atama; {@code respondBy} ekleme anından hesaplanır. */
		@Test
		void addsManualPendingAssignment() throws Exception {
			UUID taskId = published(null, user("Gönüllü"));
			UUID late = user("Geç katılan");
			Instant before = Instant.now();

			String body = addAssignment(taskId, late)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location",
						matchesPattern("/api/v1/tasks/" + taskId + "/assignments/[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.taskId").value(taskId.toString()))
				.andExpect(jsonPath("$.user.id").value(late.toString()))
				.andExpect(jsonPath("$.user.displayName").value("Geç katılan"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.overdue").value(false))
				.andExpect(jsonPath("$.respondedAt").doesNotExist())
				.andReturn().getResponse().getContentAsString();
			Instant respondBy = Instant.parse(JsonPath.read(body, "$.respondBy"));
			assertThat(respondBy).isAfterOrEqualTo(before.plus(Duration.ofMinutes(60)).minusSeconds(1));
			assertThat(jdbc.sql("select source from tasks.task_assignments where task_id = ? and user_id = ?")
				.params(taskId, late).query(String.class).single()).isEqualTo("MANUAL");
		}

		/** DB §5.4 yeniden atama: aynı satır {@code PENDING}'e döner. */
		@Test
		void reAddsRemovedRejectedAndCapacityFullAssignments() throws Exception {
			Person rejecter = person("Reddeden");
			Person acceptor = person("Kabul eden");
			UUID removed = user("Kaldırılan");
			UUID full = user("Kapasite dışı");
			UUID taskId = published(1, rejecter.id(), acceptor.id(), removed, full);
			reject(assignment(taskId, rejecter.id()), rejecter.token(), newKey()).andExpect(status().isOk());
			removeAssignment(taskId, assignment(taskId, removed)).andExpect(status().isNoContent());
			accept(assignment(taskId, acceptor.id()), acceptor.token(), newKey()).andExpect(status().isOk());
			assertThat(assignmentStatus(assignment(taskId, full))).isEqualTo("CAPACITY_FULL");
			patchCapacity(taskId, 5);

			for (UUID userId : List.of(rejecter.id(), removed, full)) {
				UUID existing = assignment(taskId, userId);
				addAssignment(taskId, userId)
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.id").value(existing.toString()))
					.andExpect(jsonPath("$.status").value("PENDING"))
					.andExpect(jsonPath("$.removedBy").doesNotExist());
				assertThat(jdbc.sql("""
						select source = 'MANUAL' and responded_at is null and removed_at is null and removed_by is null
						  from tasks.task_assignments where id = ?""").param(existing).query(Boolean.class).single())
					.isTrue();
			}
		}

		@Test
		void activeAssignmentIsAlreadyExists() throws Exception {
			Person acceptor = person("Kabul eden");
			UUID pending = user("Bekleyen");
			UUID taskId = published(null, acceptor.id(), pending);
			accept(assignment(taskId, acceptor.id()), acceptor.token(), newKey()).andExpect(status().isOk());

			for (UUID userId : List.of(acceptor.id(), pending)) {
				expectProblem(addAssignment(taskId, userId), 409, "ALREADY_EXISTS")
					.andExpect(jsonPath("$.conflict").value("ASSIGNMENT_ACTIVE"));
			}
		}

		@Test
		void ruleViolationsAreConflicts() throws Exception {
			Person acceptor = person("Kabul eden");
			UUID taskId = published(1, acceptor.id());
			UUID outsider = user("Dışarıdan");
			given(participation.isParticipant(operationId, outsider)).willReturn(false);

			expectProblem(addAssignment(taskId, outsider), 409, "NOT_A_PARTICIPANT");
			accept(assignment(taskId, acceptor.id()), acceptor.token(), newKey()).andExpect(status().isOk());
			expectProblem(addAssignment(taskId, user("Yeni")), 409, "CAPACITY_FULL")
				.andExpect(jsonPath("$.acceptedCount").value(1))
				.andExpect(jsonPath("$.capacity").value(1));
			expectProblem(addAssignment(draft(null, user("Gönüllü")), user("Yeni")), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value("DRAFT"));
			expectFieldError(mvc.perform(json(post("/api/v1/tasks/{id}/assignments", taskId), coordinator.token(),
					"{}")), "userId", "REQUIRED");
			expectProblem(addAssignment(UUID.randomUUID(), user("Yeni")), 404, "NOT_FOUND");
		}

		/** E-3: kabul edilmiş atamanın kaldırılması kabul sayısını aynı işlemde düşürür; AC-14.4. */
		@Test
		void removesAssignmentAndReleasesAcceptedCount() throws Exception {
			Person acceptor = person("Kabul eden");
			UUID pending = user("Bekleyen");
			UUID taskId = published(3, acceptor.id(), pending);
			UUID accepted = assignment(taskId, acceptor.id());
			accept(accepted, acceptor.token(), newKey()).andExpect(status().isOk());

			removeAssignment(taskId, accepted).andExpect(status().isNoContent());
			removeAssignment(taskId, assignment(taskId, pending)).andExpect(status().isNoContent());

			assertThat(acceptedCount(taskId)).isZero();
			assertThat(jdbc.sql("""
					select bool_and(status = 'REMOVED' and removed_at is not null and removed_by = ?)
					  from tasks.task_assignments where task_id = ?""").params(coordinator.id(), taskId)
				.query(Boolean.class).single()).isTrue();
			expectProblem(removeAssignment(taskId, accepted), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value("REMOVED"));
			expectProblem(removeAssignment(published(null, user("Başka")), accepted), 404, "NOT_FOUND");
			expectProblem(removeAssignment(taskId, UUID.randomUUID()), 404, "NOT_FOUND");
		}

		@Test
		void withoutManagePermissionIsForbidden() throws Exception {
			UUID volunteer = user("Gönüllü");
			UUID taskId = published(null, volunteer);
			given(access.can(any(), eq(TaskAction.MANAGE), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(addAssignment(taskId, user("Yeni")), 403, "FORBIDDEN");
			expectProblem(removeAssignment(taskId, assignment(taskId, volunteer)), 403, "FORBIDDEN");
			assertThat(assignmentStatus(assignment(taskId, volunteer))).isEqualTo("PENDING");
		}

		private ResultActions addAssignment(UUID taskId, UUID userId) throws Exception {
			return mvc.perform(json(post("/api/v1/tasks/{id}/assignments", taskId), coordinator.token(),
					"{\"userId\": \"" + userId + "\"}"));
		}

		private ResultActions removeAssignment(UUID taskId, UUID assignmentId) throws Exception {
			return mvc.perform(delete("/api/v1/tasks/{id}/assignments/{assignmentId}", taskId, assignmentId)
				.with(TestSessions.authenticate(coordinator.token())));
		}

		private void patchCapacity(UUID taskId, int capacity) throws Exception {
			int version = jdbc.sql("select version from tasks.tasks where id = ?").param(taskId).query(Integer.class)
				.single();
			mvc.perform(json(patch("/api/v1/tasks/{id}", taskId), coordinator.token(),
					"{\"version\": " + version + ", \"capacity\": " + capacity + "}")).andExpect(status().isOk());
		}

	}

	ResultActions changeStateWithKey(UUID taskId, String target, String key) throws Exception {
		return mvc.perform(json(post("/api/v1/tasks/{id}/state", taskId), coordinator.token(),
				"{\"targetState\": \"" + target + "\"}").header("Idempotency-Key", key));
	}

	int taskCount() {
		return jdbc.sql("select count(*) from tasks.tasks where operation_id = ?").param(operationId)
			.query(Integer.class).single();
	}

}
