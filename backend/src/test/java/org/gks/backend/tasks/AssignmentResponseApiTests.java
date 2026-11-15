package org.gks.backend.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import org.gks.backend.access.Decision;
import org.gks.backend.TestSessions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.gks.backend.access.Subject;
import org.gks.backend.access.TaskAccess.TaskAction;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * TK-11, TK-12 (backend-api-design.md §9.1 "Kapasite ve eşzamanlılık", DB E-1, E-4; AC-14.*, AC-15.3; LD-45,
 * LD-46; T-TSK-01 – T-TSK-07).
 */
class AssignmentResponseApiTests extends TaskApiTestSupport {

	@Autowired
	DataSource dataSource;

	@Nested
	class Accept {

		/** AC-14.1: yanıt veren kişi ve sunucu zamanı kaydedilir; {@code MyAssignmentView}. */
		@Test
		void acceptsPendingAssignment() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(3, volunteer.id(), user("Diğer"));
			UUID assignmentId = assignment(taskId, volunteer.id());

			mvc.perform(post("/api/v1/assignments/{id}/accept", assignmentId)
				.with(TestSessions.authenticate(volunteer.token())).header("Idempotency-Key", newKey())
				.contentType(MediaType.APPLICATION_JSON).content("{\"respondedAtDevice\": \"2026-11-15T10:00:00+03:00\"}"))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Idempotent-Replayed"))
				.andExpect(jsonPath("$.id").value(assignmentId.toString()))
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.overdue").value(false))
				.andExpect(jsonPath("$.task.id").value(taskId.toString()))
				.andExpect(jsonPath("$.task.acceptedCount").value(1))
				.andExpect(jsonPath("$.task.capacity").value(3))
				.andExpect(jsonPath("$.task.operationName").value("Deprem müdahalesi"));

			assertThat(acceptedCount(taskId)).isOne();
			assertThat(jdbc.sql("select responded_at is not null from tasks.task_assignments where id = ?")
				.param(assignmentId).query(Boolean.class).single()).isTrue();
			verify(access).can(new Subject(volunteer.id(), false, false), TaskAction.RESPOND, operationId, true);
			assertThat(idempotencyRows(volunteer.id())).isOne();
		}

		@Test
		void unlimitedCapacityNeverFills() throws Exception {
			Person first = person("Birinci");
			Person second = person("İkinci");
			UUID taskId = published(null, first.id(), second.id());

			accept(assignment(taskId, first.id()), first.token(), newKey()).andExpect(status().isOk());
			accept(assignment(taskId, second.id()), second.token(), newKey()).andExpect(status().isOk());
			assertThat(acceptedCount(taskId)).isEqualTo(2);
		}

		/** AC-14.5: kapasite dolduğu anda bekleyen diğer atamalar {@code CAPACITY_FULL} olur. */
		@Test
		void fillingCapacityClosesOtherPendingAssignments() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID other1 = user("Bekleyen 1");
			UUID other2 = user("Bekleyen 2");
			UUID taskId = published(1, volunteer.id(), other1, other2);

			accept(assignment(taskId, volunteer.id()), volunteer.token(), newKey()).andExpect(status().isOk());

			assertThat(assignmentStatus(assignment(taskId, other1))).isEqualTo("CAPACITY_FULL");
			assertThat(assignmentStatus(assignment(taskId, other2))).isEqualTo("CAPACITY_FULL");
			assertThat(assignmentStatus(assignment(taskId, volunteer.id()))).isEqualTo("ACCEPTED");
		}

		/** T-TSK-02, LD-46: {@code CAPACITY_FULL} atamanın kabulü durum değiştirmeden 409; idempotency satırı yok. */
		@Test
		void capacityFullAssignmentIsRejectedWithoutChange() throws Exception {
			Person winner = person("Kazanan");
			Person late = person("Geç kalan");
			UUID taskId = published(1, winner.id(), late.id());
			accept(assignment(taskId, winner.id()), winner.token(), newKey()).andExpect(status().isOk());
			UUID lateAssignment = assignment(taskId, late.id());
			String before = assignmentRow(lateAssignment);

			expectProblem(accept(lateAssignment, late.token(), newKey()), 409, "CAPACITY_FULL")
				.andExpect(jsonPath("$.acceptedCount").value(1))
				.andExpect(jsonPath("$.capacity").value(1));

			assertThat(assignmentRow(lateAssignment)).isEqualTo(before);
			assertThat(acceptedCount(taskId)).isOne();
			assertThat(idempotencyRows(late.id())).isZero();
		}

		/** T-TSK-03: {@code ACCEPTED}, {@code REJECTED}, {@code REMOVED} → {@code ASSIGNMENT_NOT_PENDING}. */
		@ParameterizedTest
		@CsvSource({ "ACCEPTED", "REJECTED", "REMOVED" })
		void nonPendingAssignmentIsNotPending(String state) throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(1, volunteer.id());
			UUID assignmentId = assignment(taskId, volunteer.id());
			switch (state) {
				case "ACCEPTED" -> accept(assignmentId, volunteer.token(), newKey()).andExpect(status().isOk());
				case "REJECTED" -> reject(assignmentId, volunteer.token(), newKey()).andExpect(status().isOk());
				default -> jdbc.sql("""
						update tasks.task_assignments set status = 'REMOVED', removed_at = now(), removed_by = ?
						 where id = ?""").params(coordinator.id(), assignmentId).update();
			}

			expectProblem(accept(assignmentId, volunteer.token(), newKey()), 409, "ASSIGNMENT_NOT_PENDING")
				.andExpect(jsonPath("$.acceptedCount").doesNotExist());
			assertThat(assignmentStatus(assignmentId)).isEqualTo(state);
		}

		/**
		 * T-TSK-04, T-TSK-05 (LD-45): kapasitesi dolu görevde {@code PENDING} atama (savunma yolu) → 409
		 * {@code CAPACITY_FULL}; atama {@code CAPACITY_FULL} olarak commit edilir; idempotency satırı yok. Aynı anahtarla
		 * tekrar yine 409, durum değişmez.
		 */
		@Test
		void defensivePathCommitsCapacityFullWithoutIdempotencyRow() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(2, volunteer.id());
			jdbc.sql("update tasks.tasks set accepted_count = 2 where id = ?").param(taskId).update();
			UUID assignmentId = assignment(taskId, volunteer.id());
			String key = newKey();

			expectProblem(accept(assignmentId, volunteer.token(), key), 409, "CAPACITY_FULL")
				.andExpect(jsonPath("$.acceptedCount").value(2))
				.andExpect(jsonPath("$.capacity").value(2));
			assertThat(assignmentStatus(assignmentId)).isEqualTo("CAPACITY_FULL");
			assertThat(idempotencyRows(volunteer.id())).isZero();
			String after = assignmentRow(assignmentId);

			expectProblem(accept(assignmentId, volunteer.token(), key), 409, "CAPACITY_FULL")
				.andExpect(header().doesNotExist("Idempotent-Replayed"));
			assertThat(assignmentRow(assignmentId)).isEqualTo(after);
			assertThat(acceptedCount(taskId)).isEqualTo(2);
			assertThat(idempotencyRows(volunteer.id())).isZero();
		}

		/**
		 * T-TSK-06: savunma yolunda aynı anahtarla eşzamanlı ikinci istek birinci commit edene kadar birincil anahtarda
		 * bekler, sonra kendi satırını ekler, {@code CAPACITY_FULL} görür ve geri alınır. Sıra, görev satırı test
		 * bağlantısında kilitlenerek belirlenir.
		 */
		@Test
		void concurrentRetryOnDefensivePathWaitsAndRollsBack() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(1, volunteer.id());
			jdbc.sql("update tasks.tasks set accepted_count = 1 where id = ?").param(taskId).update();
			UUID assignmentId = assignment(taskId, volunteer.id());
			String key = newKey();

			ExecutorService executor = Executors.newFixedThreadPool(2);
			try (Connection blocker = dataSource.getConnection()) {
				blocker.setAutoCommit(false);
				try (PreparedStatement lock = blocker.prepareStatement("select id from tasks.tasks where id = ? for update")) {
					lock.setObject(1, taskId);
					lock.executeQuery();
				}
				int waiting = lockWaiters();
				Future<MockHttpServletResponse> first = executor
					.submit(() -> accept(assignmentId, volunteer.token(), key).andReturn().getResponse());
				awaitLockWaiters(waiting + 1);   // birinci: anahtar satırını ekledi, görev kilidinde bekliyor
				Future<MockHttpServletResponse> second = executor
					.submit(() -> accept(assignmentId, volunteer.token(), key).andReturn().getResponse());
				awaitLockWaiters(waiting + 2);   // ikinci: aynı anahtarın birincil anahtarında bekliyor
				blocker.commit();

				for (Future<MockHttpServletResponse> result : List.of(first, second)) {
					MockHttpServletResponse response = result.get();
					assertThat(response.getStatus()).isEqualTo(409);
					assertThat(response.getContentAsString()).contains("\"code\":\"CAPACITY_FULL\"");
					assertThat(response.getHeader("Idempotent-Replayed")).isNull();
				}
			}
			finally {
				executor.shutdownNow();
			}
			assertThat(assignmentStatus(assignmentId)).isEqualTo("CAPACITY_FULL");
			assertThat(acceptedCount(taskId)).isOne();
			assertThat(idempotencyRows(volunteer.id())).isZero();
		}

		/** T-TSK-01, T-TSK-07 (AC-14.6): kapasite 1, 20 eşzamanlı kabul → tam 1 kabul; yalnızca onun 200 satırı kalır. */
		@Test
		void twentyConcurrentAcceptsNeverExceedCapacity() throws Exception {
			List<Person> volunteers = new ArrayList<>();
			for (int i = 0; i < 20; i++) {
				volunteers.add(person("Gönüllü " + i));
			}
			UUID taskId = published(1, volunteers.stream().map(Person::id).toArray(UUID[]::new));
			CountDownLatch start = new CountDownLatch(1);
			List<Callable<Integer>> accepts = new ArrayList<>();
			for (Person volunteer : volunteers) {
				UUID assignmentId = assignment(taskId, volunteer.id());
				accepts.add(() -> {
					start.await();
					return accept(assignmentId, volunteer.token(), newKey()).andReturn().getResponse().getStatus();
				});
			}
			List<Integer> statuses = new ArrayList<>();
			try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
				List<Future<Integer>> results = new ArrayList<>();
				accepts.forEach(call -> results.add(executor.submit(call)));
				start.countDown();
				for (Future<Integer> result : results) {
					statuses.add(result.get());
				}
			}

			assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(19);
			assertThat(acceptedCount(taskId)).isOne();
			assertThat(jdbc.sql("""
					select count(*) filter (where status = 'ACCEPTED') = 1 and count(*) filter (where status = 'CAPACITY_FULL') = 19
					  from tasks.task_assignments where task_id = ?""").param(taskId).query(Boolean.class).single())
				.isTrue();
			List<Integer> rows = jdbc.sql("""
					select response_status from platform.idempotency_keys where principal_key in (:principals)""")
				.param("principals", volunteers.stream().map(v -> v.id().toString()).toList())
				.query(Integer.class).list();
			assertThat(rows).containsExactly(200);
		}

		/** AC-14.3: başkasının atamasına yanıt verilemez; access izin verse bile. */
		@Test
		void othersAssignmentIsForbidden() throws Exception {
			Person owner = person("Sahibi");
			Person stranger = person("Yabancı");
			UUID assignmentId = assignment(published(null, owner.id()), owner.id());

			expectProblem(accept(assignmentId, stranger.token(), newKey()), 403, "FORBIDDEN");
			expectProblem(reject(assignmentId, stranger.token(), newKey()), 403, "FORBIDDEN");
			assertThat(assignmentStatus(assignmentId)).isEqualTo("PENDING");
			assertThat(idempotencyRows(stranger.id())).isZero();
		}

		@Test
		void withoutRespondPermissionIsForbidden() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID assignmentId = assignment(published(null, volunteer.id()), volunteer.id());
			given(access.can(any(), eq(TaskAction.RESPOND), any(), anyBoolean())).willReturn(Decision.FORBIDDEN);

			expectProblem(accept(assignmentId, volunteer.token(), newKey()), 403, "FORBIDDEN");
			assertThat(assignmentStatus(assignmentId)).isEqualTo("PENDING");
		}

		/** §3.3: başlık eksikliği (5) kaynak yüklemeden (6) önce; biçimsiz anahtar da 400. */
		@Test
		void idempotencyKeyIsRequired() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID assignmentId = assignment(published(null, volunteer.id()), volunteer.id());

			expectFieldError(accept(assignmentId, volunteer.token(), null), "Idempotency-Key", "REQUIRED");
			expectFieldError(accept(UUID.randomUUID(), volunteer.token(), null), "Idempotency-Key", "REQUIRED");
			expectFieldError(accept(assignmentId, volunteer.token(), "12345678"), "Idempotency-Key", "INVALID_FORMAT");
			expectProblem(accept(UUID.randomUUID(), volunteer.token(), newKey()), 404, "NOT_FOUND");
			assertThat(assignmentStatus(assignmentId)).isEqualTo("PENDING");
		}

		/** §3.6: aynı anahtar + aynı gövde → ilk yanıt aynen; farklı uçta aynı anahtar → 422. */
		@Test
		void sameKeyReplaysAndOtherEndpointIsMismatch() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(5, volunteer.id());
			UUID assignmentId = assignment(taskId, volunteer.id());
			String key = newKey();
			String first = accept(assignmentId, volunteer.token(), key).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

			String replay = accept(assignmentId, volunteer.token(), key)
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString();
			assertThat(replay).isEqualTo(first);
			assertThat(acceptedCount(taskId)).isOne();
			expectProblem(reject(assignmentId, volunteer.token(), key), 422, "IDEMPOTENCY_KEY_MISMATCH");
			expectProblem(mvc.perform(post("/api/v1/assignments/{id}/accept", assignmentId)
				.with(TestSessions.authenticate(volunteer.token())).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"respondedAtDevice\": \"2026-11-15T10:00:00Z\"}")),
					422, "IDEMPOTENCY_KEY_MISMATCH");
		}

		/** Kapsam anahtarı kullanıcıdır: iki kişi aynı anahtarı birbirinden bağımsız kullanır. */
		@Test
		void keysAreScopedPerUser() throws Exception {
			Person first = person("Birinci");
			Person second = person("İkinci");
			UUID taskId = published(null, first.id(), second.id());
			String key = newKey();

			accept(assignment(taskId, first.id()), first.token(), key).andExpect(status().isOk())
				.andExpect(header().doesNotExist("Idempotent-Replayed"));
			accept(assignment(taskId, second.id()), second.token(), key).andExpect(status().isOk())
				.andExpect(header().doesNotExist("Idempotent-Replayed"));
			assertThat(acceptedCount(taskId)).isEqualTo(2);
		}

		/** AC-15.3: iptal veya tamamlanmış görevde atama yanıtlanamaz; durumu değişmez. */
		@ParameterizedTest
		@CsvSource({ "CANCELLED", "DONE" })
		void closedTaskCannotBeAnswered(String finalState) throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(null, volunteer.id());
			if (finalState.equals("DONE")) {
				changeState(taskId, "IN_PROGRESS").andExpect(status().isOk());
			}
			changeState(taskId, finalState).andExpect(status().isOk());
			UUID assignmentId = assignment(taskId, volunteer.id());

			expectProblem(accept(assignmentId, volunteer.token(), newKey()), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value(finalState));
			expectProblem(reject(assignmentId, volunteer.token(), newKey()), 409, "INVALID_STATE_TRANSITION")
				.andExpect(jsonPath("$.currentState").value(finalState));
			assertThat(assignmentStatus(assignmentId)).isEqualTo("PENDING");
		}

		@Test
		void inProgressTaskStillAcceptsResponses() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(null, volunteer.id());
			changeState(taskId, "IN_PROGRESS").andExpect(status().isOk());

			accept(assignment(taskId, volunteer.id()), volunteer.token(), newKey()).andExpect(status().isOk());
		}

	}

	@Nested
	class Reject {

		@Test
		void rejectsPendingAssignment() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID taskId = published(2, volunteer.id());
			UUID assignmentId = assignment(taskId, volunteer.id());
			String key = newKey();

			String first = reject(assignmentId, volunteer.token(), key)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"))
				.andExpect(jsonPath("$.task.acceptedCount").value(0))
				.andReturn().getResponse().getContentAsString();
			assertThat(jdbc.sql("select responded_at is not null from tasks.task_assignments where id = ?")
				.param(assignmentId).query(Boolean.class).single()).isTrue();
			assertThat(reject(assignmentId, volunteer.token(), key)
				.andExpect(header().string("Idempotent-Replayed", "true"))
				.andReturn().getResponse().getContentAsString()).isEqualTo(first);
		}

		/** AC-14.4: kabulden sonra ret yok; {@code CAPACITY_FULL} de yanıt beklemiyor. */
		@Test
		void onlyPendingCanBeRejected() throws Exception {
			Person winner = person("Kazanan");
			Person late = person("Geç kalan");
			UUID taskId = published(1, winner.id(), late.id());
			UUID accepted = assignment(taskId, winner.id());
			accept(accepted, winner.token(), newKey()).andExpect(status().isOk());

			expectProblem(reject(accepted, winner.token(), newKey()), 409, "ASSIGNMENT_NOT_PENDING");
			expectProblem(reject(assignment(taskId, late.id()), late.token(), newKey()), 409, "ASSIGNMENT_NOT_PENDING");
			assertThat(assignmentStatus(accepted)).isEqualTo("ACCEPTED");
			assertThat(acceptedCount(taskId)).isOne();
		}

		@Test
		void idempotencyKeyIsRequired() throws Exception {
			Person volunteer = person("Gönüllü");
			UUID assignmentId = assignment(published(null, volunteer.id()), volunteer.id());

			expectFieldError(reject(assignmentId, volunteer.token(), null), "Idempotency-Key", "REQUIRED");
			expectProblem(reject(UUID.randomUUID(), volunteer.token(), newKey()), 404, "NOT_FOUND");
			assertThat(assignmentStatus(assignmentId)).isEqualTo("PENDING");
		}

	}

	// --- yardımcılar ---

	private String assignmentRow(UUID assignmentId) {
		return jdbc.sql("select t::text from tasks.task_assignments t where id = ?").param(assignmentId)
			.query(String.class).single();
	}

	private int lockWaiters() {
		return jdbc.sql("""
				select count(*) from pg_stat_activity
				 where wait_event_type = 'Lock' and datname = current_database()""").query(Integer.class).single();
	}

	private void awaitLockWaiters(int expected) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
		while (lockWaiters() < expected) {
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError("Beklenen kilit bekleyişi oluşmadı: " + expected);
			}
			Thread.sleep(20);
		}
	}

}
