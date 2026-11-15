package org.gks.backend.tasks;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.PageCursor;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Idempotency;
import org.gks.backend.access.Subject;
import org.gks.backend.tasks.TaskRequests.AssignmentAddRequest;
import org.gks.backend.tasks.TaskRequests.AssignmentResponseRequest;
import org.gks.backend.tasks.TaskRequests.TaskPublishRequest;
import org.gks.backend.tasks.TaskRequests.TaskStateRequest;
import org.gks.backend.tasks.TaskViews.AssignmentView;
import org.gks.backend.tasks.TaskViews.MyAssignmentView;
import org.gks.backend.tasks.TaskViews.Page;
import org.gks.backend.tasks.TaskViews.TaskSummary;
import org.gks.backend.tasks.TaskViews.TaskView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

/**
 * Görev uçları TK-01 – TK-12 (backend-api-design.md §9.1). Idempotency: TK-03, TK-11, TK-12 zorunlu (Z); TK-01,
 * TK-04, TK-05 destekler (d); diğerleri başlığı yok sayar (§3.6).
 */
@RestController
class TaskController {

	private final TaskService service;
	private final Idempotency idempotency;

	TaskController(TaskService service, Idempotency idempotency) {
		this.service = service;
		this.idempotency = idempotency;
	}

	/** TK-01 (d). */
	@PostMapping("/api/v1/operations/{operationId}/tasks")
	ResponseEntity<String> create(@PathVariable UUID operationId, @RequestBody JsonNode body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.optionalKey(key);
		var fields = TaskValidation.create(body);
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/operations/{id}/tasks",
				Map.of("id", operationId), body);
		return service.create(subject, operationId, fields, request)
			.toResponseEntity(id -> URI.create("/api/v1/tasks/" + id));
	}

	/** TK-02. */
	@PatchMapping("/api/v1/tasks/{taskId}")
	TaskView update(@PathVariable UUID taskId, @RequestBody JsonNode body, Authentication authentication) {
		return service.update(management(authentication), taskId, TaskValidation.update(body));
	}

	/** TK-03 (Z). */
	@PostMapping("/api/v1/tasks/{taskId}/publish")
	ResponseEntity<String> publish(@PathVariable UUID taskId, @RequestBody TaskPublishRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.requiredKey(key);
		int version = TaskValidation.version(body == null ? null : body.version());
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/tasks/{id}/publish",
				Map.of("id", taskId), body);
		return service.publish(subject, taskId, version, request).toResponseEntity();
	}

	/** TK-04 (d). */
	@PostMapping("/api/v1/tasks/{taskId}/state")
	ResponseEntity<String> changeState(@PathVariable UUID taskId, @RequestBody TaskStateRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.optionalKey(key);
		TaskState target = TaskValidation.targetState(body == null ? null : body.targetState());
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/tasks/{id}/state", Map.of("id", taskId),
				body);
		return service.changeState(subject, taskId, target, request).toResponseEntity();
	}

	/** TK-05 (d). */
	@PostMapping("/api/v1/tasks/{taskId}/assignments")
	ResponseEntity<String> addAssignment(@PathVariable UUID taskId, @RequestBody AssignmentAddRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = management(authentication);
		String idemKey = Idempotency.optionalKey(key);
		UUID userId = TaskValidation.requireUser(body == null ? null : body.userId());
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/tasks/{id}/assignments",
				Map.of("id", taskId), body);
		return service.addAssignment(subject, taskId, userId, request)
			.toResponseEntity(id -> URI.create("/api/v1/tasks/" + taskId + "/assignments/" + id));
	}

	/** TK-06. */
	@DeleteMapping("/api/v1/tasks/{taskId}/assignments/{assignmentId}")
	ResponseEntity<Void> removeAssignment(@PathVariable UUID taskId, @PathVariable UUID assignmentId,
			Authentication authentication) {
		service.removeAssignment(management(authentication), taskId, assignmentId);
		return ResponseEntity.noContent().build();
	}

	/** TK-07. */
	@GetMapping("/api/v1/operations/{operationId}/tasks")
	Page<TaskSummary> list(@PathVariable UUID operationId, @RequestParam(required = false) String state,
			@RequestParam(required = false) String limit, @RequestParam(required = false) String cursor,
			Authentication authentication) {
		Subject subject = any(authentication);
		List<FieldError> errors = new ArrayList<>();
		TaskState filter = TaskValidation.queryEnum(state, "state", TaskState.class, errors);
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.HEX);
		requireValid(errors);
		return service.list(subject, operationId, filter, page);
	}

	/** TK-08. */
	@GetMapping("/api/v1/tasks/{taskId}")
	TaskView get(@PathVariable UUID taskId, Authentication authentication) {
		return service.get(any(authentication), taskId);
	}

	/** TK-09. */
	@GetMapping("/api/v1/tasks/{taskId}/assignments")
	Page<AssignmentView> assignments(@PathVariable UUID taskId, @RequestParam(required = false) List<String> status,
			@RequestParam(required = false) String overdue, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = management(authentication);
		List<FieldError> errors = new ArrayList<>();
		Set<AssignmentStatus> statuses = TaskValidation.statuses(status, errors);
		Boolean overdueFilter = TaskValidation.queryBoolean(overdue, "overdue", errors);
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.HEX);
		requireValid(errors);
		return service.assignments(subject, taskId, statuses, overdueFilter, page);
	}

	/** TK-10. */
	@GetMapping("/api/v1/me/assignments")
	Page<MyAssignmentView> myAssignments(@RequestParam(required = false) List<String> status,
			@RequestParam(required = false) String operationId, @RequestParam(required = false) String limit,
			@RequestParam(required = false) String cursor, Authentication authentication) {
		Subject subject = android(authentication);
		List<FieldError> errors = new ArrayList<>();
		Set<AssignmentStatus> statuses = TaskValidation.statuses(status, errors);
		UUID operation = TaskValidation.queryUuid(operationId, "operationId", errors);
		PageCursor page = PageCursor.parse(limit, cursor, errors, PageCursor.CursorFormat.HEX);
		requireValid(errors);
		return service.myAssignments(subject, statuses, operation, page);
	}

	/** TK-11 (Z). Gövde isteğe bağlıdır. */
	@PostMapping("/api/v1/assignments/{assignmentId}/accept")
	ResponseEntity<String> accept(@PathVariable UUID assignmentId,
			@RequestBody(required = false) AssignmentResponseRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = android(authentication);
		String idemKey = Idempotency.requiredKey(key);
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/assignments/{id}/accept",
				Map.of("id", assignmentId), body != null ? body : new AssignmentResponseRequest(null));
		return service.accept(subject, assignmentId, request).toResponseEntity();
	}

	/** TK-12 (Z). Gövde isteğe bağlıdır. */
	@PostMapping("/api/v1/assignments/{assignmentId}/reject")
	ResponseEntity<String> reject(@PathVariable UUID assignmentId,
			@RequestBody(required = false) AssignmentResponseRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String key, Authentication authentication) {
		Subject subject = android(authentication);
		String idemKey = Idempotency.requiredKey(key);
		var request = idempotency.request(idemKey, subject.userId(), "POST", "/assignments/{id}/reject",
				Map.of("id", assignmentId), body != null ? body : new AssignmentResponseRequest(null));
		return service.reject(subject, assignmentId, request).toResponseEntity();
	}

	private static void requireValid(List<FieldError> errors) {
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
	}

	/** {@code x-client: [WEB]} uçları (§2.4, §3.3 adım 4). */
	private static Subject management(Authentication authentication) {
		return Subject.from(authentication).requireManagementSession();
	}

	/** {@code x-client: [ANDROID]} uçları (TK-10 – TK-12). */
	private static Subject android(Authentication authentication) {
		return Subject.from(authentication).requireAndroid();
	}

	/** {@code x-client: [WEB, ANDROID]} uçları (TK-07, TK-08). */
	private static Subject any(Authentication authentication) {
		return Subject.from(authentication);
	}

}
