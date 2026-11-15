package org.gks.backend.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import org.gks.backend.access.Decision;
import org.gks.backend.TestSessions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.gks.backend.access.TaskAccess.TaskAction;
import org.gks.backend.access.TaskAccess.ViewScope;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** TK-07 – TK-10 (backend-api-design.md §9.1, §9.3; AC-13.6, AC-16.1, AC-16.3; §14 N-03 A). */
class TaskReadApiTests extends TaskApiTestSupport {

	@Nested
	class ListTasks {

		/** KOR görünümü: taslaklar dahil tümü, sayaçlarla; en yeni önce, imleçli sayfalama (§3.5). */
		@Test
		void coordinatorSeesAllTasksWithCountersAndPages() throws Exception {
			UUID volunteer = user("Gönüllü");
			UUID first = draft(null, volunteer);
			UUID second = published(5, volunteer, user("İkinci"));
			UUID third = draft(null, volunteer);
			jdbc.sql("update tasks.task_assignments set respond_by = now() - interval '1 minute' where task_id = ? and user_id = ?")
				.params(second, volunteer).update();

			String page1 = getAs(coordinator.token(), "/api/v1/operations/" + operationId + "/tasks?limit=2")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].id").value(third.toString()))
				.andExpect(jsonPath("$.items[1].id").value(second.toString()))
				.andExpect(jsonPath("$.items[1].pendingCount").value(2))
				.andExpect(jsonPath("$.items[1].overdueCount").value(1))
				.andExpect(jsonPath("$.items[1].acceptedCount").value(0))
				.andExpect(jsonPath("$.items[1].capacity").value(5))
				.andExpect(jsonPath("$.items[1].myAssignment").doesNotExist())
				.andExpect(jsonPath("$.items[1].targets").doesNotExist())
				.andExpect(jsonPath("$.nextCursor").isString())
				.andReturn().getResponse().getContentAsString();
			String cursor = JsonPath.read(page1, "$.nextCursor");

			getAs(coordinator.token(), "/api/v1/operations/" + operationId + "/tasks?limit=2&cursor=" + cursor)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(first.toString()))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
			getAs(coordinator.token(), "/api/v1/operations/" + operationId + "/tasks?state=PUBLISHED")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(second.toString()));
		}

		@Test
		void invalidQueryIsValidationFailed() throws Exception {
			String base = "/api/v1/operations/" + operationId + "/tasks";
			expectFieldError(getAs(coordinator.token(), base + "?state=OPEN"), "state", "NOT_ALLOWED_VALUE");
			expectFieldError(getAs(coordinator.token(), base + "?limit=0"), "limit", "OUT_OF_RANGE");
			expectFieldError(getAs(coordinator.token(), base + "?limit=101"), "limit", "OUT_OF_RANGE");
			expectFieldError(getAs(coordinator.token(), base + "?cursor=bm90LWEtdXVpZA"), "cursor", "INVALID_FORMAT");
		}

		/** Gönüllü izdüşümü: yalnızca kendisine atanmış (kaldırılmamış) görevler ve {@code myAssignment}. */
		@Test
		void volunteerSeesOnlyOwnAssignedTasks() throws Exception {
			Person volunteer = person("Gönüllü");
			volunteerView(volunteer.id());
			UUID mine = published(3, volunteer.id(), user("Diğer"));
			published(null, user("Başka"));
			draft(null, volunteer.id());
			UUID removed = published(null, volunteer.id());
			jdbc.sql("update tasks.task_assignments set status = 'REMOVED', removed_at = now(), removed_by = ? where task_id = ?")
				.params(coordinator.id(), removed).update();

			getAs(volunteer.token(), "/api/v1/operations/" + operationId + "/tasks")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(mine.toString()))
				.andExpect(jsonPath("$.items[0].acceptedCount").value(0))
				.andExpect(jsonPath("$.items[0].capacity").value(3))
				.andExpect(jsonPath("$.items[0].pendingCount").doesNotExist())
				.andExpect(jsonPath("$.items[0].overdueCount").doesNotExist())
				.andExpect(jsonPath("$.items[0].myAssignment.id").value(assignment(mine, volunteer.id()).toString()))
				.andExpect(jsonPath("$.items[0].myAssignment.status").value("PENDING"))
				.andExpect(jsonPath("$.items[0].myAssignment.overdue").value(false));
		}

		@Test
		void outOfScopeIsForbiddenAndUnknownOperationNotFound() throws Exception {
			given(access.viewScope(any(), any())).willReturn(ViewScope.NONE);

			expectProblem(getAs(coordinator.token(), "/api/v1/operations/" + operationId + "/tasks"), 403, "FORBIDDEN");
			expectProblem(getAs(coordinator.token(), "/api/v1/operations/" + UUID.randomUUID() + "/tasks"), 404,
					"NOT_FOUND");
		}

	}

	@Nested
	class GetTask {

		@Test
		void coordinatorSeesTargetsAndHistory() throws Exception {
			UUID volunteer = user("Gönüllü");
			UUID taskId = published(2, volunteer);

			getAs(coordinator.token(), "/api/v1/tasks/" + taskId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targets.userIds[0]").value(volunteer.toString()))
				.andExpect(jsonPath("$.history.length()").value(2))
				.andExpect(jsonPath("$.pendingCount").value(1))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.myAssignment").doesNotExist());
		}

		/** TK-08: gönüllü yalnızca kendi atamasını görür; hedefler, geçmiş, sayaçlar yazılmaz (AC-13.6 "3/5" kalır). */
		@Test
		void volunteerSeesOnlyOwnAssignment() throws Exception {
			Person volunteer = person("Gönüllü");
			volunteerView(volunteer.id());
			UUID taskId = published(5, volunteer.id(), user("Diğer"));

			getAs(volunteer.token(), "/api/v1/tasks/" + taskId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Su dağıtımı"))
				.andExpect(jsonPath("$.acceptedCount").value(0))
				.andExpect(jsonPath("$.capacity").value(5))
				.andExpect(jsonPath("$.myAssignment.id").value(assignment(taskId, volunteer.id()).toString()))
				.andExpect(jsonPath("$.targets").doesNotExist())
				.andExpect(jsonPath("$.history").doesNotExist())
				.andExpect(jsonPath("$.pendingCount").doesNotExist())
				.andExpect(jsonPath("$.overdueCount").doesNotExist());
			expectProblem(getAs(volunteer.token(), "/api/v1/tasks/" + published(null, user("Başka"))), 403,
					"FORBIDDEN");
			expectProblem(getAs(volunteer.token(), "/api/v1/tasks/" + UUID.randomUUID()), 404, "NOT_FOUND");
		}

	}

	@Nested
	class TaskAssignments {

		/** TK-09: durum (çoklu) ve {@code overdue} süzgeçleri; AC-16.3. */
		@Test
		void filtersByStatusAndOverdue() throws Exception {
			Person acceptor = person("Kabul eden");
			UUID late = user("Geciken");
			UUID onTime = user("Zamanında");
			UUID taskId = published(null, acceptor.id(), late, onTime);
			accept(assignment(taskId, acceptor.id()), acceptor.token(), newKey()).andExpect(status().isOk());
			jdbc.sql("update tasks.task_assignments set respond_by = now() - interval '1 minute' where task_id = ? and user_id = ?")
				.params(taskId, late).update();
			String base = "/api/v1/tasks/" + taskId + "/assignments";

			getAs(coordinator.token(), base)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3));
			getAs(coordinator.token(), base + "?overdue=true")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].user.id").value(late.toString()))
				.andExpect(jsonPath("$.items[0].overdue").value(true));
			getAs(coordinator.token(), base + "?status=ACCEPTED")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
				.andExpect(jsonPath("$.items[0].respondedAt").isString());
			getAs(coordinator.token(), base + "?status=ACCEPTED,PENDING&overdue=false")
				.andExpect(jsonPath("$.items.length()").value(2));
			getAs(coordinator.token(), base + "?status=ACCEPTED&status=PENDING&limit=1")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.nextCursor").isString());
			expectFieldError(getAs(coordinator.token(), base + "?status=DONE"), "status", "NOT_ALLOWED_VALUE");
			expectFieldError(getAs(coordinator.token(), base + "?overdue=evet"), "overdue", "INVALID_FORMAT");
		}

		@Test
		void onlyFullScopeMayListAssignments() throws Exception {
			Person volunteer = person("Gönüllü");
			volunteerView(volunteer.id());
			UUID taskId = published(null, volunteer.id());

			expectProblem(getAs(volunteer.token(), "/api/v1/tasks/" + taskId + "/assignments"), 403, "CLIENT_NOT_ALLOWED");
			String volunteerWeb = new TestSessions(jdbc, jwtEncoder).web(volunteer.id(), false);
			expectProblem(getAs(volunteerWeb, "/api/v1/tasks/" + taskId + "/assignments"), 403, "FORBIDDEN");
		}

	}

	@Nested
	class MyAssignments {

		/** TK-10: kendi atamaları, görev özeti operasyon adıyla; durum ve operasyon süzgeci. */
		@Test
		void listsOwnAssignmentsWithTaskSummary() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID firstTask = published(4, volunteer.id());
			UUID otherOperation = operation("ACTIVE");
			UUID secondTask = draftIn(otherOperation, volunteer.id());
			publish(secondTask, 1, newKey()).andExpect(status().isOk());
			reject(assignment(secondTask, volunteer.id()), volunteer.token(), newKey()).andExpect(status().isOk());
			published(null, user("Başkası"));

			getAs(volunteer.token(), "/api/v1/me/assignments")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].status").value("REJECTED"))
				.andExpect(jsonPath("$.items[0].task.id").value(secondTask.toString()))
				.andExpect(jsonPath("$.items[1].id").value(assignment(firstTask, volunteer.id()).toString()))
				.andExpect(jsonPath("$.items[1].task.operationId").value(operationId.toString()))
				.andExpect(jsonPath("$.items[1].task.operationName").value("Deprem müdahalesi"))
				.andExpect(jsonPath("$.items[1].task.capacity").value(4))
				.andExpect(jsonPath("$.items[1].task.pendingCount").doesNotExist())
				.andExpect(jsonPath("$.items[1].respondBy").isString());
			getAs(volunteer.token(), "/api/v1/me/assignments?status=PENDING")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].task.id").value(firstTask.toString()));
			getAs(volunteer.token(), "/api/v1/me/assignments?operationId=" + otherOperation)
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].task.id").value(secondTask.toString()));
			expectFieldError(getAs(volunteer.token(), "/api/v1/me/assignments?operationId=yok"), "operationId",
					"INVALID_FORMAT");
		}

		@Test
		void withoutPermissionIsForbidden() throws Exception {
			given(access.can(any(), eq(TaskAction.LIST_OWN_ASSIGNMENTS), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(getAs(person("Gönüllü").token(), "/api/v1/me/assignments"), 403, "FORBIDDEN");
			// Web oturumundan TK-10: uç düzeyi kapı.
			expectProblem(getAs(coordinator.token(), "/api/v1/me/assignments"), 403, "CLIENT_NOT_ALLOWED");
		}

		private UUID draftIn(UUID operation, UUID... users) throws Exception {
			String body = createTask(operation, taskBody(null, users)).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();
			return UUID.fromString(JsonPath.read(body, "$.id"));
		}

	}

	private ResultActions getAs(String token, String path) throws Exception {
		return mvc.perform(get(path).with(TestSessions.authenticate(token)));
	}

	static List<String> ids(String page) {
		return JsonPath.read(page, "$.items[*].id");
	}

}
