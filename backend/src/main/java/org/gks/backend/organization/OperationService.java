package org.gks.backend.organization;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;

import java.util.EnumSet;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.gks.backend.RealtimeBus;
import org.gks.backend.access.ActingBasis;
import org.gks.backend.AuditTrail;
import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Ids;
import org.gks.backend.Idempotency;
import org.gks.backend.IdempotencyResponse;
import org.gks.backend.access.OperationAccess;
import org.gks.backend.access.OperationAccess.ViewScope;
import org.gks.backend.access.RoleAssignments;
import org.gks.backend.access.Subject;
import org.gks.backend.identity.UserDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OR-01 – OR-06. Denetim sırası §3.3: doğrulama (400, çağırandan önce) → kaynak (404) → {@code can()} (403) →
 * idempotency → kaynak kuralı (409).
 */
@Service
class OperationService {

	private static final EnumSet<OperationState> CLOSED = EnumSet.of(OperationState.COMPLETED, OperationState.CANCELLED);
	static final String RESOURCE_OPERATION = "OPERATION";

	private final OperationRepository operations;
	private final OperationAccess access;
	private final RoleAssignments roles;
	private final UserDirectory users;
	private final ParticipantDetails.Groups groups;
	private final ParticipantDetails.Assignments assignments;
	private final Idempotency idempotency;
	private final JdbcClient jdbc;
	private final Clock clock;
	private final AuditTrail audit;
	private final RealtimeBus realtime;
	private final ApplicationEventPublisher events;
	private final ActingBasis acting;

	OperationService(OperationRepository operations, OperationAccess access, RoleAssignments roles, UserDirectory users,
			ParticipantDetails.Groups groups, ParticipantDetails.Assignments assignments, Idempotency idempotency,
			JdbcClient jdbc, Clock clock, AuditTrail audit, ActingBasis acting, RealtimeBus realtime,
			ApplicationEventPublisher events) {
		this.events = events;
		this.realtime = realtime;
		this.audit = audit;
		this.acting = acting;
		this.operations = operations;
		this.access = access;
		this.roles = roles;
		this.users = users;
		this.groups = groups;
		this.assignments = assignments;
		this.idempotency = idempotency;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** OR-01: operasyon {@code PLANNED} oluşur; her koordinatör için KOR/OPERATION ataması aynı işlemde (AC-08.4). */
	@Transactional
	IdempotencyResponse create(Subject subject, OperationCreate input, Idempotency.Request idem) {
		access.canCreate(subject).require();
		Optional<IdempotencyResponse> replay = idempotency.begin(idem);
		if (replay.isPresent()) {
			return replay.get();
		}
		UUID id = Ids.newId();
		Instant now = clock.instant();
		operations.insert(id, input.name(), input.description(), input.plannedEndAt(), subject.userId(), now);
		roles.assignCoordinators(id, input.coordinatorIds(), subject.userId());
		audit.record(subject.userId(), acting.organization(subject), subject.client(), "OPERATION_CREATED", "OPERATION", id, id,
				Map.of("coordinatorIds", input.coordinatorIds()));
		return idempotency.complete(idem, 201, views(List.of(load(id)), subject).getFirst(), RESOURCE_OPERATION, id);
	}

	/** OR-04: kapsam dışı operasyon hiç dönmez; sıralama {@code id} azalan (§3.5). */
	@Transactional(readOnly = true)
	Page<OperationView> list(Subject subject, Set<OperationState> states, UUID after, int limit) {
		ViewScope scope = access.viewScope(subject);
		if (!scope.allOperations() && scope.operationIds().isEmpty()) {
			return new Page<>(List.of(), null);
		}
		StringBuilder sql = new StringBuilder("select id from organization.operations where true");
		if (!scope.allOperations()) {
			sql.append(" and id in (:scope)");
		}
		if (!states.isEmpty()) {
			sql.append(" and state in (:states)");
		}
		if (after != null) {
			sql.append(" and id < :after");
		}
		sql.append(" order by id desc limit :limit");
		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", limit + 1);
		if (!scope.allOperations()) {
			statement = statement.param("scope", scope.operationIds());
		}
		if (!states.isEmpty()) {
			statement = statement.param("states", states.stream().map(Enum::name).toList());
		}
		if (after != null) {
			statement = statement.param("after", after);
		}
		List<UUID> ids = statement.query(UUID.class).list();
		boolean more = ids.size() > limit;
		List<UUID> current = more ? ids.subList(0, limit) : ids;
		Map<UUID, Operation> byId = operations.findAllById(current).stream()
			.collect(Collectors.toMap(Operation::id, op -> op));
		List<OperationView> items = views(current.stream().map(byId::get).toList(), subject);
		return new Page<>(items, more ? current.getLast() : null);
	}

	@Transactional(readOnly = true)
	OperationView get(UUID id, Subject subject) {
		Operation operation = load(id);
		access.canView(subject, id).require();
		return views(List.of(operation), subject).getFirst();
	}

	/** OR-06: KOR (kendi operasyonu) ve ADM; kişi alanı KOR izdüşümüdür (§3.8). */
	@Transactional(readOnly = true)
	Page<ParticipantView> participants(Subject subject, UUID operationId, UUID notInGroup, UUID after, int limit) {
		load(operationId);
		access.canManage(subject, operationId).require();
		Set<UUID> excluded = Set.of();
		if (notInGroup != null) {
			excluded = groups.activeMembers(operationId, notInGroup).orElseThrow(() -> ApiException.validation(
					List.of(new FieldError("notInGroup", "NOT_ALLOWED_VALUE", "Grup bu operasyonda değil"))));
		}
		List<RoleAssignments.Participant> rows = roles.participants(operationId, excluded, after, limit + 1);
		boolean more = rows.size() > limit;
		List<RoleAssignments.Participant> current = more ? rows.subList(0, limit) : rows;
		List<UUID> userIds = current.stream().map(RoleAssignments.Participant::userId).toList();
		Map<UUID, UserDirectory.UserSummary> summaries = users.summaries(userIds);
		Map<UUID, List<ParticipantDetails.GroupRef>> memberships = groups.activeGroups(operationId, userIds);
		Map<UUID, Integer> openCounts = assignments.openCounts(operationId, userIds);
		List<ParticipantView> items = current.stream()
			.map(p -> new ParticipantView(summaries.get(p.userId()), p.joinedAt(),
					memberships.getOrDefault(p.userId(), List.of()), openCounts.getOrDefault(p.userId(), 0)))
			.toList();
		return new Page<>(items, more ? current.getLast().assignmentId() : null);
	}

	@Transactional
	OperationView update(UUID id, OperationUpdate update, Subject subject) {
		Operation operation = load(id);
		access.canManage(subject, id).require();
		if (operation.state().isClosed()) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
		String name = update.name() != null ? update.name() : operation.name();
		String description = update.description() != null
				? update.description().orElse(null) : operation.description();
		Instant plannedEndAt = update.plannedEndAt() != null
				? update.plannedEndAt().orElse(null) : operation.plannedEndAt();
		if (operations.updateDetails(id, name, description, plannedEndAt, clock.instant(), CLOSED) == 0) {
			throw ApiException.of(Code.OPERATION_CLOSED);   // okuma ile yazma arasında kapatıldı
		}
		List<String> changed = new ArrayList<>();
		if (update.name() != null) {
			changed.add("name");
		}
		if (update.description() != null) {
			changed.add("description");
		}
		if (update.plannedEndAt() != null) {
			changed.add("plannedEndAt");
		}
		audit.record(subject.userId(), acting.operation(subject, id), subject.client(), "OPERATION_UPDATED", "OPERATION", id,
				id, Map.of("fields", changed));
		return views(List.of(load(id)), subject).getFirst();
	}

	@Transactional
	OperationView changeState(UUID id, OperationState target, Subject subject) {
		Operation operation = load(id);
		access.canManage(subject, id).require();
		OperationState current = operation.state();
		if (!current.canTransitionTo(target)) {
			throw ApiException.invalidStateTransition(current, target);
		}
		Instant now = clock.instant();
		boolean closing = target.isClosed();
		if (operations.transition(id, current, target, now, closing ? now : null,
				closing ? subject.userId() : null) == 0) {
			throw ApiException.invalidStateTransition(load(id).state(), target);   // eşzamanlı geçiş kazandı
		}
		// Kapanış zinciri (§7.3): tüketiciler aynı işlemde (OperationClosed); diğerleri tüketen modüllerle eklenecek.
		if (closing) {
			events.publishEvent(new OperationClosed(id, subject.userId(), now));
		}
		audit.record(subject.userId(), acting.operation(subject, id), subject.client(), "OPERATION_STATE_CHANGED", "OPERATION",
				id, id, Map.of("from", current.name(), "to", target.name()));
		realtime.publish(RealtimeBus.operationTopic(id), "operation.state.changed", id, Map.of("state", target.name()));
		if (closing) {
			realtime.accessChanged(null, id, "OPERATION_CLOSED");   // operation:{id} abonelikleri düşer (§7.3)
		}
		return views(List.of(load(id)), subject).getFirst();
	}

	/** Görünüm izdüşümü: yönetim yetkisi olan (KOR kendi operasyonu, ADM) tam görünüm; katılımcı dar görünüm. */
	private List<OperationView> views(List<Operation> list, Subject subject) {
		List<UUID> ids = list.stream().map(Operation::id).toList();
		Map<UUID, List<UUID>> coordinators = roles.coordinators(ids);
		Set<UUID> userIds = new HashSet<>();
		coordinators.values().forEach(userIds::addAll);
		Map<UUID, String> names = users.displayNames(userIds);
		Instant now = clock.instant();
		List<OperationView> views = new ArrayList<>();
		for (Operation operation : list) {
			List<OperationView.UserRef> refs = coordinators.getOrDefault(operation.id(), List.of()).stream()
				.map(userId -> new OperationView.UserRef(userId, names.get(userId)))
				.toList();
			views.add(access.canManage(subject, operation.id()).allowed()
					? OperationView.managerView(operation, refs, assignments.pendingCount(operation.id()), now)
					: OperationView.participantView(operation, refs));
		}
		return views;
	}

	private Operation load(UUID id) {
		return operations.findById(id).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
	}

	record Page<T>(List<T> items, UUID lastId) {
	}

	/** §7.4 {@code ParticipantView}. */
	record ParticipantView(UserDirectory.UserSummary user, Instant joinedAt, List<ParticipantDetails.GroupRef> groups,
			int openAssignmentCount) {
	}

}
