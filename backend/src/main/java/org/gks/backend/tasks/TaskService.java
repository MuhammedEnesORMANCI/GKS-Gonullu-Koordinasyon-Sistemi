package org.gks.backend.tasks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.access.ActingBasis;
import org.gks.backend.AuditTrail;
import org.gks.backend.ApiException;
import org.gks.backend.PageCursor;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Idempotency;
import org.gks.backend.IdempotencyResponse;
import org.gks.backend.Ids;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.access.TaskAccess;
import org.gks.backend.access.TaskAccess.TaskAction;
import org.gks.backend.access.TaskAccess.ViewScope;
import org.gks.backend.groups.GroupMembers;
import org.gks.backend.identity.UserDirectory;
import org.gks.backend.organization.OperationState;
import org.gks.backend.organization.Operations;
import org.gks.backend.organization.Operations.OperationRef;
import org.gks.backend.tasks.TaskStore.AssignmentRow;
import org.gks.backend.tasks.TaskStore.Counts;
import org.gks.backend.tasks.TaskStore.SummaryRow;
import org.gks.backend.tasks.TaskStore.TaskRow;
import org.gks.backend.tasks.TaskValidation.TargetsInput;
import org.gks.backend.tasks.TaskValidation.TaskFields;
import org.gks.backend.tasks.TaskValidation.TimeWindowInput;
import org.gks.backend.tasks.TaskViews.AssignmentView;
import org.gks.backend.tasks.TaskViews.MyAssignmentRef;
import org.gks.backend.tasks.TaskViews.MyAssignmentView;
import org.gks.backend.tasks.TaskViews.Page;
import org.gks.backend.tasks.TaskViews.TaskStateChange;
import org.gks.backend.tasks.TaskViews.TaskSummary;
import org.gks.backend.tasks.TaskViews.TaskTargets;
import org.gks.backend.tasks.TaskViews.TaskView;
import org.gks.backend.tasks.TaskViews.TimeWindow;
import org.gks.backend.tasks.TaskViews.UserRef;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TK-01 – TK-12 (backend-api-design.md §9.1). Denetim sırası §3.3: doğrulama ve {@code Idempotency-Key} (400,
 * denetleyicide) → kaynak (404) → {@code can()} (403) → idempotency tekrar/uyuşmazlık → kaynak kuralı (409) →
 * anlamsal kural. Yetki kararı access modülündedir; burada yalnızca girdiler (operasyon, atananın kendisi mi)
 * hazırlanır. Bildirim, denetim ve SSE modülleri henüz yoktur.
 */
@Service
class TaskService {

	static final String RESOURCE_TASK = "TASK";
	static final String RESOURCE_ASSIGNMENT = "TASK_ASSIGNMENT";

	private final TaskStore store;
	private final TaskAccess access;
	private final Participation participation;
	private final Operations operations;
	private final GroupMembers groups;
	private final UserDirectory users;
	private final Idempotency idempotency;
	private final TaskNotifications notifications;
	private final Clock clock;
	private final AuditTrail audit;
	private final ActingBasis acting;

	private static final Set<AssignmentStatus> ACTIVE = Set.of(AssignmentStatus.PENDING, AssignmentStatus.ACCEPTED);

	TaskService(TaskStore store, TaskAccess access, Participation participation, Operations operations,
			GroupMembers groups, UserDirectory users, Idempotency idempotency, TaskNotifications notifications,
			Clock clock, AuditTrail audit, ActingBasis acting) {
		this.audit = audit;
		this.acting = acting;
		this.store = store;
		this.access = access;
		this.participation = participation;
		this.operations = operations;
		this.groups = groups;
		this.users = users;
		this.idempotency = idempotency;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** TK-01. Taslak {@code DRAFT}, {@code version = 1}; oluşturma geçmişe yazılır (AC-13.3, AC-15.2). */
	@Transactional
	IdempotencyResponse create(Subject subject, UUID operationId, TaskFields fields, Idempotency.Request idem) {
		OperationRef operation = operations.findForShare(operationId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		requireManage(subject, operation.id());
		requireTargetsInScope(operation.id(), fields.targets());
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		requireOpen(operation);

		UUID taskId = Ids.newId();
		Instant now = clock.instant();
		store.insertTask(taskId, operation.id(), fields.title(), fields.description().orElse(null), fields.priority(),
				fields.meetingPoint().orElse(null), fields.timeWindow().orElse(null), fields.capacity().orElse(null),
				fields.responseWindowMinutes(), fields.targets(), subject.userId(), now);
		store.insertStateChange(Ids.newId(), taskId, null, TaskState.DRAFT, subject.userId(), now);
		record(subject, "TASK_CREATED", RESOURCE_TASK, taskId, operation.id(), Map.of("priority", String.valueOf(fields.priority())));
		return idempotency.complete(idem, 201, fullView(store.find(taskId).orElseThrow()), RESOURCE_TASK, taskId);
	}

	/**
	 * TK-02. {@code targets} yalnızca {@code DRAFT} iken (§9.3); kapasite kabul sayısının altına inemez, eşitse
	 * {@code PENDING} atamalar {@code CAPACITY_FULL} olur (AC-13.7, E-2). Yayındaki görevde atananlara değişiklik
	 * bildirimi notifications modülüyle gelecek (AC-13.5).
	 */
	@Transactional
	TaskView update(Subject subject, UUID taskId, TaskFields fields) {
		TaskRow task = store.lock(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		requireManage(subject, task.operationId());
		if (fields.targets() != null && task.state() != TaskState.DRAFT) {
			throw ApiException.validation(List.of(new FieldError("targets", "READ_ONLY",
					"Hedefler yayından sonra değiştirilemez; atama ekleyin veya kaldırın")));
		}
		if (fields.targets() != null) {
			requireTargetsInScope(task.operationId(), fields.targets());
		}
		if (task.state() == TaskState.DONE || task.state() == TaskState.CANCELLED) {
			throw ApiException.invalidStateTransition(task.state(), task.state());
		}
		if (fields.version() != task.version()) {
			throw ApiException.versionConflict(task.version());
		}
		Integer capacity = fields.capacity() != null ? fields.capacity().orElse(null) : task.capacity();
		if (capacity != null && capacity < task.acceptedCount()) {
			throw ApiException.capacityBelowAccepted(task.acceptedCount());
		}

		Instant now = clock.instant();
		TimeWindowInput window = fields.timeWindow() != null ? fields.timeWindow().orElse(null)
				: task.windowStart() == null ? null : new TimeWindowInput(task.windowStart(), task.windowEnd());
		try {
			store.updateDetails(taskId, fields.title() != null ? fields.title() : task.title(),
					fields.description() != null ? fields.description().orElse(null) : task.description(),
					fields.priority() != null ? fields.priority() : task.priority(),
					fields.meetingPoint() != null ? fields.meetingPoint().orElse(null) : task.meetingPoint(), window,
					capacity,
					fields.responseWindowMinutes() != null ? fields.responseWindowMinutes() : task.responseWindowMinutes(),
					fields.targets() != null ? fields.targets() : task.targets(), now);
		}
		catch (DataIntegrityViolationException ex) {
			throw capacityViolation(ex, () -> ApiException.capacityBelowAccepted(task.acceptedCount()));
		}
		if (capacity != null && capacity == task.acceptedCount()) {
			notifications.capacityFull(task, store.markPendingCapacityFull(taskId, now), now);
		}
		TaskRow updated = store.find(taskId).orElseThrow();
		record(subject, "TASK_UPDATED", RESOURCE_TASK, taskId, task.operationId(), Map.of("version", updated.version()));
		if (updated.state().isOpen()) {   // taslakta atanan yok; yayındaki görevde atananlara değişiklik (AC-13.5)
			notifications.updated(updated, updated.version(), store.assignees(taskId, ACTIVE), subject.userId());
		}
		return fullView(updated);
	}

	/**
	 * TK-03. Atama anlık görüntüsü (TD-16, AC-13.2): kişi hedefleri (hâlâ katılımcı olanlar) ve hedef grupların
	 * <b>yayın anındaki</b> aktif üyeleri; her kişiye tek atama. Operasyon {@code FOR SHARE} ile okunur (E-9).
	 */
	@Transactional
	IdempotencyResponse publish(Subject subject, UUID taskId, int version, Idempotency.Request idem) {
		TaskRow found = store.find(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		requireManage(subject, found.operationId());
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		requireOpen(operations.findForShare(found.operationId()).orElseThrow(() -> ApiException.of(Code.NOT_FOUND)));
		TaskRow task = store.lock(taskId).orElseThrow();
		if (task.state() != TaskState.DRAFT) {
			throw ApiException.invalidStateTransition(task.state(), TaskState.PUBLISHED);
		}
		if (version != task.version()) {
			throw ApiException.versionConflict(task.version());
		}

		Map<UUID, UUID> audience = new LinkedHashMap<>();   // kişi → geldiği grup (kişi hedefinde null)
		for (UUID userId : task.targets().userIds()) {
			if (participation.isParticipant(task.operationId(), userId)) {
				audience.put(userId, null);
			}
		}
		groups.activeMembers(task.operationId(), task.targets().groupIds())
			.forEach((groupId, members) -> members.forEach(member -> {
				if (!audience.containsKey(member)) {   // putIfAbsent null değeri "yok" sayar; kişi hedefi korunur
					audience.put(member, groupId);
				}
			}));
		if (audience.isEmpty()) {
			throw ApiException.semanticValidation(List.of(new FieldError("targets", "EMPTY_AUDIENCE",
					"Hedeflerde yayın anında atanabilecek kimse yok")));
		}

		Instant now = clock.instant();
		Instant respondBy = now.plus(Duration.ofMinutes(task.responseWindowMinutes()));
		audience.forEach((userId, viaGroup) -> store.insertAssignment(Ids.newId(), taskId, userId, viaGroup, "SNAPSHOT",
				subject.userId(), now, respondBy));
		store.setState(taskId, TaskState.PUBLISHED, subject.userId(), now);
		store.insertStateChange(Ids.newId(), taskId, TaskState.DRAFT, TaskState.PUBLISHED, subject.userId(), now);
		notifications.published(task, audience.keySet(), subject.userId());
		record(subject, "TASK_PUBLISHED", RESOURCE_TASK, taskId, task.operationId(), Map.of("assignmentCount", audience.size()));
		return idempotency.complete(idem, 200, fullView(store.find(taskId).orElseThrow()), RESOURCE_TASK, taskId);
	}

	/**
	 * TK-04 (§9.2). {@code DONE}/{@code CANCELLED} olunca {@code PENDING} atamalar yanıtlanamaz, durumları
	 * değişmez (AC-15.3). İptal bildirimi notifications modülüyle gelecek.
	 */
	@Transactional
	IdempotencyResponse changeState(Subject subject, UUID taskId, TaskState target, Idempotency.Request idem) {
		TaskRow found = store.find(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		requireManage(subject, found.operationId());
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		TaskRow task = store.lock(taskId).orElseThrow();
		if (!task.state().canTransitionTo(target)) {
			throw ApiException.invalidStateTransition(task.state(), target);
		}
		Instant now = clock.instant();
		store.setState(taskId, target, subject.userId(), now);
		store.insertStateChange(Ids.newId(), taskId, task.state(), target, subject.userId(), now);
		// İptal: yanıt bekleyen ve kabul edenler; ilerleme: yalnızca kabul edenler.
		notifications.stateChanged(task, target, store.assignees(taskId,
				target == TaskState.CANCELLED ? ACTIVE : Set.of(AssignmentStatus.ACCEPTED)), subject.userId());
		record(subject, "TASK_STATE_CHANGED", RESOURCE_TASK, taskId, task.operationId(),
				Map.of("from", task.state().name(), "to", target.name()));
		return idempotency.complete(idem, 200, fullView(store.find(taskId).orElseThrow()), RESOURCE_TASK, taskId);
	}

	/**
	 * TK-05 (TD-16). Mevcut {@code REMOVED}/{@code REJECTED}/{@code CAPACITY_FULL} satırı yeniden {@code PENDING}
	 * olur (DB §5.4, §15.3); {@code respondBy} yeniden hesaplanır.
	 */
	@Transactional
	IdempotencyResponse addAssignment(Subject subject, UUID taskId, UUID userId, Idempotency.Request idem) {
		TaskRow found = store.find(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		requireManage(subject, found.operationId());
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		requireOpen(operations.findForShare(found.operationId()).orElseThrow(() -> ApiException.of(Code.NOT_FOUND)));
		TaskRow task = store.lock(taskId).orElseThrow();
		if (!task.state().isOpen()) {
			throw ApiException.invalidStateTransition(task.state(), AssignmentStatus.PENDING);
		}
		if (!participation.isParticipant(task.operationId(), userId)) {
			throw ApiException.of(Code.NOT_A_PARTICIPANT);
		}
		Optional<AssignmentRow> existing = store.findAssignment(taskId, userId);
		if (existing.isPresent() && (existing.get().status() == AssignmentStatus.PENDING
				|| existing.get().status() == AssignmentStatus.ACCEPTED)) {
			throw ApiException.alreadyExists("ASSIGNMENT_ACTIVE");
		}
		if (task.full()) {
			throw ApiException.capacityFull(task.acceptedCount(), task.capacity());
		}
		Instant now = clock.instant();
		Instant respondBy = now.plus(Duration.ofMinutes(task.responseWindowMinutes()));
		UUID assignmentId;
		if (existing.isPresent()) {
			assignmentId = existing.get().id();
			store.lockAssignment(assignmentId);
			store.reactivateAssignment(assignmentId, subject.userId(), now, respondBy);
		}
		else {
			assignmentId = Ids.newId();
			try {
				store.insertAssignment(assignmentId, taskId, userId, null, "MANUAL", subject.userId(), now, respondBy);
			}
			catch (DuplicateKeyException ex) {
				throw ApiException.alreadyExists("ASSIGNMENT_ACTIVE");   // §15.3 uq_task_assignments_task_user
			}
		}
		notifications.assigned(task, assignmentId, userId, now, subject.userId());
		record(subject, "ASSIGNMENT_ADDED", RESOURCE_ASSIGNMENT, assignmentId, task.operationId(),
				Map.of("taskId", taskId, "userId", userId));
		return idempotency.complete(idem, 201, assignmentView(store.findAssignment(assignmentId).orElseThrow()),
				RESOURCE_ASSIGNMENT, assignmentId);
	}

	/** TK-06. Kabul edilmiş atama kaldırılırsa kabul sayısı aynı işlemde düşer (E-3). Kişiye bildirim gelecek. */
	@Transactional
	void removeAssignment(Subject subject, UUID taskId, UUID assignmentId) {
		TaskRow found = store.find(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		AssignmentRow assignment = store.findAssignment(assignmentId).filter(a -> a.taskId().equals(taskId))
			.orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		requireManage(subject, found.operationId());
		store.lock(taskId).orElseThrow();
		assignment = store.lockAssignment(assignmentId).orElseThrow();
		if (assignment.status() == AssignmentStatus.REMOVED) {
			throw ApiException.invalidStateTransition(AssignmentStatus.REMOVED, AssignmentStatus.REMOVED);
		}
		Instant now = clock.instant();
		store.remove(assignmentId, subject.userId(), now);
		if (assignment.status() == AssignmentStatus.ACCEPTED) {
			store.decrementAccepted(taskId, now);
		}
		notifications.removed(found, assignmentId, assignment.userId(), now, subject.userId());
		record(subject, "ASSIGNMENT_REMOVED", RESOURCE_ASSIGNMENT, assignmentId, found.operationId(),
				Map.of("taskId", taskId, "userId", assignment.userId()));
	}

	/** Denetim kaydı (backend §12.5); dayanak KOR veya (ADR-015) ADM. */
	private void record(Subject subject, String action, String resourceType, UUID resourceId, UUID operationId,
			Map<String, Object> details) {
		audit.record(subject.userId(), acting.operation(subject, operationId), subject.client(), action, resourceType,
				resourceId, operationId, details);
	}

	/** TK-07. Gönüllü izdüşümünde yalnızca kendisine atanmış görevler ve {@code myAssignment} (§14 N-03 A). */
	@Transactional(readOnly = true)
	Page<TaskSummary> list(Subject subject, UUID operationId, TaskState state, PageCursor page) {
		OperationRef operation = operations.find(operationId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		ViewScope scope = access.viewScope(subject, operation.id());
		if (scope == ViewScope.NONE) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		boolean full = scope == ViewScope.ALL;
		Instant now = clock.instant();
		List<SummaryRow> rows = store.list(operation.id(), !full, subject.userId(), state, page.after(),
				page.limit() + 1, now);
		boolean more = rows.size() > page.limit();
		List<SummaryRow> current = more ? rows.subList(0, page.limit()) : rows;
		List<TaskSummary> items = current.stream()
			.map(row -> summary(row.task(), full ? new Counts(row.pendingCount(), row.overdueCount()) : null,
					full ? null : row.mine(), null, now))
			.toList();
		return new Page<>(items, more ? PageCursor.encode(current.getLast().task().id()) : null);
	}

	/** TK-08. Gönüllü yalnızca kendi atamasını görür; diğer atamalar, hedefler ve geçmiş yazılmaz. */
	@Transactional(readOnly = true)
	TaskView get(Subject subject, UUID taskId) {
		TaskRow task = store.find(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		ViewScope scope = access.viewScope(subject, task.operationId());
		if (scope == ViewScope.ALL) {
			return fullView(task);
		}
		if (scope == ViewScope.ASSIGNED) {
			Optional<AssignmentRow> mine = store.visibleAssignment(taskId, subject.userId());
			if (mine.isPresent()) {
				return assigneeView(task, mine.get());
			}
		}
		throw ApiException.of(Code.FORBIDDEN);
	}

	/** TK-09. Yalnızca KOR/ADM kapsamı. */
	@Transactional(readOnly = true)
	Page<AssignmentView> assignments(Subject subject, UUID taskId, Set<AssignmentStatus> statuses, Boolean overdue,
			PageCursor page) {
		TaskRow task = store.find(taskId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (access.viewScope(subject, task.operationId()) != ViewScope.ALL) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		List<AssignmentRow> rows = store.assignmentsOfTask(taskId, statuses, overdue, page.after(), page.limit() + 1,
				clock.instant());
		boolean more = rows.size() > page.limit();
		List<AssignmentRow> current = more ? rows.subList(0, page.limit()) : rows;
		Set<UUID> ids = new HashSet<>();
		current.forEach(row -> {
			ids.add(row.userId());
			if (row.removedBy() != null) {
				ids.add(row.removedBy());
			}
		});
		Map<UUID, String> names = users.displayNames(ids);
		Instant now = clock.instant();
		List<AssignmentView> items = current.stream().map(row -> assignmentView(row, names, now)).toList();
		return new Page<>(items, more ? PageCursor.encode(current.getLast().id()) : null);
	}

	/** TK-10 ({@code self}). */
	@Transactional(readOnly = true)
	Page<MyAssignmentView> myAssignments(Subject subject, Set<AssignmentStatus> statuses, UUID operationId,
			PageCursor page) {
		access.can(subject, TaskAction.LIST_OWN_ASSIGNMENTS, null, true).require();
		List<AssignmentRow> rows = store.myAssignments(subject.userId(), statuses, operationId, page.after(),
				page.limit() + 1);
		boolean more = rows.size() > page.limit();
		List<AssignmentRow> current = more ? rows.subList(0, page.limit()) : rows;
		Map<UUID, TaskRow> tasks = store.tasks(current.stream().map(AssignmentRow::taskId).toList());
		Map<UUID, String> operationNames = operations.names(tasks.values().stream().map(TaskRow::operationId).toList());
		Instant now = clock.instant();
		List<MyAssignmentView> items = current.stream()
			.map(row -> myAssignmentView(row, tasks.get(row.taskId()), operationNames, now))
			.toList();
		return new Page<>(items, more ? PageCursor.encode(current.getLast().id()) : null);
	}

	/**
	 * TK-11 (DB E-1, §9.1, LD-45, LD-46). §3.3 sırası: atama (404) → {@code task.respond} (403) → idempotency →
	 * görev kilidi ve durumu → atama kilidi ve durumu → kapasite. Atananın kimliği değişmediği için 403 kararı
	 * kilitsiz okumayla verilir.
	 * <p>
	 * Savunma yolu: kapasite doluyken {@code PENDING} atama bulunursa atama {@code CAPACITY_FULL} yapılır, kendi
	 * idempotency satırı silinir ve işlem <b>commit edilir</b>; hata yanıtı commit'ten sonra verilir.
	 */
	@Transactional
	IdempotencyResponse accept(Subject subject, UUID assignmentId, Idempotency.Request idem) {
		AssignmentRow found = store.findAssignment(assignmentId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		TaskRow unlocked = store.find(found.taskId()).orElseThrow();
		requireRespond(subject, unlocked, found);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		TaskRow task = store.lock(found.taskId()).orElseThrow();
		if (!task.state().isOpen()) {
			throw ApiException.invalidStateTransition(task.state(), AssignmentStatus.ACCEPTED);
		}
		AssignmentRow assignment = store.lockAssignment(assignmentId).orElseThrow();
		switch (assignment.status()) {
			case CAPACITY_FULL -> throw ApiException.capacityFull(task.acceptedCount(), task.capacity());
			case ACCEPTED, REJECTED, REMOVED -> throw ApiException.of(Code.ASSIGNMENT_NOT_PENDING);
			case PENDING -> {
			}
		}
		Instant now = clock.instant();
		if (task.full()) {
			store.markCapacityFull(assignmentId, now);
			idempotency.discard(idem);
			return IdempotencyResponse.failure(ApiException.capacityFull(task.acceptedCount(), task.capacity()));
		}
		store.accept(assignmentId, now);
		try {
			store.incrementAccepted(task.id(), now);
		}
		catch (DataIntegrityViolationException ex) {
			throw capacityViolation(ex, () -> ApiException.capacityFull(task.acceptedCount(), task.capacity()));
		}
		if (task.capacity() != null && task.acceptedCount() + 1 >= task.capacity()) {
			notifications.capacityFull(task, store.markPendingCapacityFull(task.id(), now), now);   // AC-14.5
		}
		TaskRow updated = store.find(task.id()).orElseThrow();
		return idempotency.complete(idem, 200,
				myAssignmentView(store.findAssignment(assignmentId).orElseThrow(), updated, null, now),
				RESOURCE_ASSIGNMENT, assignmentId);
	}

	/** TK-12 (DB E-4). Kabulden sonra ret yoktur (AC-14.4); görev kilidi gerekmez. */
	@Transactional
	IdempotencyResponse reject(Subject subject, UUID assignmentId, Idempotency.Request idem) {
		AssignmentRow found = store.findAssignment(assignmentId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		TaskRow task = store.find(found.taskId()).orElseThrow();
		requireRespond(subject, task, found);
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		task = store.find(found.taskId()).orElseThrow();
		if (!task.state().isOpen()) {
			throw ApiException.invalidStateTransition(task.state(), AssignmentStatus.REJECTED);
		}
		Instant now = clock.instant();
		if (store.reject(assignmentId, now) == 0) {
			throw ApiException.of(Code.ASSIGNMENT_NOT_PENDING);
		}
		return idempotency.complete(idem, 200,
				myAssignmentView(store.findAssignment(assignmentId).orElseThrow(), task, null, now),
				RESOURCE_ASSIGNMENT, assignmentId);
	}

	// --- kurallar ---

	private void requireManage(Subject subject, UUID operationId) {
		access.can(subject, TaskAction.MANAGE, operationId, false).require();
	}

	/** AC-14.3: kendisine atanmamış göreve yanıt veremez; karar access'tedir, atananlık burada da şarttır. */
	private void requireRespond(Subject subject, TaskRow task, AssignmentRow assignment) {
		boolean assignee = assignment.userId().equals(subject.userId());
		if (!assignee) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		access.can(subject, TaskAction.RESPOND, task.operationId(), true).require();
	}

	/** §9.3 {@code TaskTargets}: kişiler katılımcı, gruplar bu operasyonun aktif grupları; aksi {@code 403}. */
	private void requireTargetsInScope(UUID operationId, TargetsInput targets) {
		for (UUID userId : targets.userIds()) {
			if (!participation.isParticipant(operationId, userId)) {
				throw ApiException.of(Code.FORBIDDEN);
			}
		}
		if (!targets.groupIds().isEmpty()
				&& !groups.activeMembers(operationId, targets.groupIds()).keySet().containsAll(targets.groupIds())) {
			throw ApiException.of(Code.FORBIDDEN);
		}
	}

	/** Görev oluşturma, yayın ve elle atama operasyonu {@code PLANNED}/{@code ACTIVE} ister (§9.1, E-9). */
	private static void requireOpen(OperationRef operation) {
		if (operation.state() != OperationState.PLANNED && operation.state() != OperationState.ACTIVE) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
	}

	/** §15.3: {@code ck_tasks_capacity_not_exceeded} işleme göre koda çevrilir; diğer ihlaller {@code 500}. */
	private static RuntimeException capacityViolation(DataIntegrityViolationException ex,
			java.util.function.Supplier<ApiException> mapped) {
		if (String.valueOf(ex.getMessage()).contains("ck_tasks_capacity_not_exceeded")) {
			return mapped.get();
		}
		return ex;
	}

	// --- görünümler ---

	private TaskView fullView(TaskRow task) {
		Instant now = clock.instant();
		Counts counts = store.counts(task.id(), now);
		List<TaskStore.StateChangeRow> history = store.history(task.id());
		Set<UUID> ids = new HashSet<>();
		ids.add(task.createdBy());
		history.forEach(change -> ids.add(change.changedBy()));
		Map<UUID, String> names = users.displayNames(ids);
		List<TaskStateChange> changes = new ArrayList<>();
		history.forEach(change -> changes.add(new TaskStateChange(change.state(), ref(change.changedBy(), names),
				change.changedAt())));
		return new TaskView(task.id(), task.title(), task.priority(), task.state(), task.acceptedCount(),
				counts.pending(), counts.overdue(), task.capacity(), window(task), task.createdAt(), null,
				task.description(), task.meetingPoint(),
				new TaskTargets(task.targets().userIds(), task.targets().groupIds()), task.responseWindowMinutes(),
				task.version(), ref(task.createdBy(), names), task.publishedAt(), changes);
	}

	private TaskView assigneeView(TaskRow task, AssignmentRow mine) {
		Map<UUID, String> names = users.displayNames(List.of(task.createdBy()));
		return new TaskView(task.id(), task.title(), task.priority(), task.state(), task.acceptedCount(), null, null,
				task.capacity(), window(task), task.createdAt(), myRef(mine, clock.instant()), task.description(),
				task.meetingPoint(), null, task.responseWindowMinutes(), task.version(), ref(task.createdBy(), names),
				task.publishedAt(), null);
	}

	private TaskSummary summary(TaskRow task, Counts counts, AssignmentRow mine, Map<UUID, String> operationNames,
			Instant now) {
		return new TaskSummary(task.id(), operationNames != null ? task.operationId() : null,
				operationNames != null ? operationNames.get(task.operationId()) : null, task.title(), task.priority(),
				task.state(), task.acceptedCount(), counts != null ? counts.pending() : null,
				counts != null ? counts.overdue() : null, task.capacity(), window(task), task.createdAt(),
				mine != null ? myRef(mine, now) : null);
	}

	private MyAssignmentView myAssignmentView(AssignmentRow row, TaskRow task, Map<UUID, String> operationNames,
			Instant now) {
		Map<UUID, String> names = operationNames != null ? operationNames
				: operations.names(List.of(task.operationId()));
		return new MyAssignmentView(row.id(), row.status(), row.respondBy(), overdue(row, now),
				summary(task, null, null, names, now));
	}

	private AssignmentView assignmentView(AssignmentRow row) {
		Set<UUID> ids = new HashSet<>();
		ids.add(row.userId());
		if (row.removedBy() != null) {
			ids.add(row.removedBy());
		}
		return assignmentView(row, users.displayNames(ids), clock.instant());
	}

	private static AssignmentView assignmentView(AssignmentRow row, Map<UUID, String> names, Instant now) {
		return new AssignmentView(row.id(), row.taskId(), ref(row.userId(), names), row.status(), row.respondBy(),
				overdue(row, now), row.respondedAt(), row.removedBy() != null ? ref(row.removedBy(), names) : null);
	}

	private static MyAssignmentRef myRef(AssignmentRow row, Instant now) {
		return new MyAssignmentRef(row.id(), row.status(), row.respondBy(), overdue(row, now));
	}

	/** AC-16.3: {@code overdue} = {@code PENDING} ve {@code now > respondBy}. */
	private static boolean overdue(AssignmentRow row, Instant now) {
		return row.status() == AssignmentStatus.PENDING && now.isAfter(row.respondBy());
	}

	private static TimeWindow window(TaskRow task) {
		return task.windowStart() == null ? null : new TimeWindow(task.windowStart(), task.windowEnd());
	}

	private static UserRef ref(UUID userId, Map<UUID, String> names) {
		return userId == null ? null : new UserRef(userId, names.get(userId));
	}

}
