package org.gks.backend.access;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Ids;
import org.gks.backend.access.AccessDirectory.ScopeInfo;
import org.gks.backend.access.RoleAssignmentStore.AssignmentRow;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rol atamaları (backend-api-design.md §6, AC-01 – AC-03; DB §4.1). {@code role.assign} yalnızca ADM'dir (yalnızca
 * K1, MFA'lı web). Diğer modüller sistem atamalarını (ilk ADM, operasyon oluştururken KOR, davet kabulünde GON) bu
 * servisin açık yöntemleriyle yapar.
 */
@Service
public class RoleAssignments {

	private final RoleAssignmentStore store;
	private final AccessDirectory.Users users;
	private final AccessDirectory.Scopes scopes;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	RoleAssignments(RoleAssignmentStore store, AccessDirectory.Users users, AccessDirectory.Scopes scopes,
			ApplicationEventPublisher events, Clock clock) {
		this.store = store;
		this.users = users;
		this.scopes = scopes;
		this.events = events;
		this.clock = clock;
	}

	/** Açılış kurulumu için: kurumda aktif ADM ataması var mı? */
	@Transactional(readOnly = true)
	public boolean hasActiveAdmin() {
		return store.activeAdminCount() > 0;
	}

	/** İlk ADM (DB §4.1: tohumda {@code granted_by} boş). Yalnızca kurumda aktif ADM yokken. */
	@Transactional
	public void grantInitialAdmin(UUID userId) {
		if (store.activeAdminCount() > 0) {
			throw new IllegalStateException("Kurumda zaten aktif bir yönetici var");
		}
		store.insert(Ids.newId(), userId, Role.ADM, scopes.organizationId(), null, clock.instant());
	}

	/**
	 * OR-01: operasyon oluşturulurken her {@code coordinatorId} için KOR/OPERATION ataması (çağıranın işleminde).
	 * Yetki çağıranda ({@code operation.create}) denetlenmiştir.
	 *
	 * @throws ApiException {@code 400 VALIDATION_FAILED} ({@code coordinatorIds[i]}: aktif kullanıcı değil)
	 */
	@Transactional
	public void assignCoordinators(UUID operationId, List<UUID> userIds, UUID grantedBy) {
		List<FieldError> errors = new ArrayList<>();
		for (int i = 0; i < userIds.size(); i++) {
			if (users.find(userIds.get(i)).filter(AccessDirectory.UserInfo::active).isEmpty()) {
				errors.add(new FieldError("coordinatorIds[" + i + "]", "NOT_ALLOWED_VALUE", "Kullanıcı aktif değil"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		Instant now = clock.instant();
		userIds.forEach(userId -> store.insert(Ids.newId(), userId, Role.KOR, operationId, grantedBy, now));
	}

	/** Operasyonların aktif koordinatörleri ({@code OperationView.coordinators[]}). */
	@Transactional(readOnly = true)
	public Map<UUID, List<UUID>> coordinators(Collection<UUID> operationIds) {
		return store.activeHolders(Role.KOR, operationIds);
	}

	/** OR-06: operasyonun katılımcıları ({@code GON/OPERATION}), {@code id} azalan; {@code excluded} hariç. */
	@Transactional(readOnly = true)
	public List<Participant> participants(UUID operationId, Collection<UUID> excluded, UUID after, int limit) {
		return store.participants(operationId, excluded, after, limit);
	}

	/** {@code joinedAt} = katılım atamasının {@code grantedAt}'i (§7.4); imleç atama kimliğidir. */
	public record Participant(UUID assignmentId, UUID userId, Instant joinedAt) {
	}

	/** ID-11 {@code MeContext.roles[]}: kullanıcının aktif atamaları. */
	@Transactional(readOnly = true)
	public List<RoleAssignmentView> activeFor(UUID userId) {
		return views(store.list(userId, null, null, true, null, 1000));
	}

	/** ID-11 {@code MeContext.operations[].myRoles}: KOR veya katılımcı olduğu operasyonlar ve rolleri. */
	@Transactional(readOnly = true)
	public Map<UUID, Set<Role>> operationRoles(UUID userId) {
		Map<UUID, Set<Role>> roles = new java.util.HashMap<>();
		for (Grants.Grant grant : store.grants(userId).grants()) {
			if (grant.role().scopeType == ScopeType.OPERATION) {
				roles.computeIfAbsent(grant.scopeId(), k -> java.util.EnumSet.noneOf(Role.class)).add(grant.role());
			}
		}
		return roles;
	}

	/**
	 * Davet kabulü (ID-16, ID-17; TD-01): kullanıcı operasyona GON olarak katılır. Çağıranın işleminde çalışır;
	 * operasyon {@code FOR SHARE} ile kilitlenir. Zaten katılımcıysa değişiklik yapmaz.
	 *
	 * @return {@code false}: operasyon yok veya kapanmış (çağıran {@code 410 INVITATION_INVALID} döner)
	 */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public boolean joinOperation(UUID operationId, UUID userId, UUID invitedBy) {
		ScopeInfo operation = scopes.operationForShare(operationId).orElse(null);
		if (operation == null || operation.closed()) {
			return false;
		}
		if (!store.isParticipant(operationId, userId)) {
			store.insert(Ids.newId(), userId, Role.GON, operationId, invitedBy, clock.instant());
		}
		return true;
	}

	/** AC-01. */
	@Transactional
	RoleAssignmentView grant(Subject subject, UUID userId, Role role, ScopeType scopeType, UUID scopeId) {
		requireAssigner(subject);
		List<FieldError> errors = new ArrayList<>();
		if (role.scopeType != scopeType) {
			errors.add(new FieldError("role", "NOT_ALLOWED_VALUE", "Bu rol bu kapsamda atanamaz"));
		}
		if (users.find(userId).filter(AccessDirectory.UserInfo::active).isEmpty()) {
			errors.add(new FieldError("userId", "NOT_ALLOWED_VALUE", "Kullanıcı aktif değil"));
		}
		ScopeInfo operation = null;
		if (scopeType == ScopeType.ORGANIZATION && !scopes.organizationId().equals(scopeId)) {
			errors.add(new FieldError("scopeId", "NOT_ALLOWED_VALUE", "Kurum bulunamadı"));
		}
		if (scopeType == ScopeType.OPERATION) {
			operation = scopes.operationForShare(scopeId).orElse(null);
			if (operation == null) {
				errors.add(new FieldError("scopeId", "NOT_ALLOWED_VALUE", "Operasyon bulunamadı"));
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		if (operation != null && operation.closed()) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
		UUID id = Ids.newId();
		try {
			store.insert(id, userId, role, scopeId, subject.userId(), clock.instant());
		}
		catch (DuplicateKeyException ex) {
			throw ApiException.alreadyExists("ROLE_ACTIVE");
		}
		return view(store.find(id).orElseThrow());
	}

	/**
	 * AC-02. ADM kendi ADM atamasını ve kurumun son aktif ADM atamasını kaldıramaz ({@code 403}, S-09). Katılım
	 * kaldırılırsa {@link ParticipationRevoked} aynı işlemde yayımlanır.
	 */
	@Transactional
	void revoke(Subject subject, UUID assignmentId) {
		requireAssigner(subject);
		AssignmentRow row = store.lock(assignmentId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (row.revokedAt() != null) {
			throw ApiException.invalidStateTransition("REVOKED", "REVOKED");
		}
		if (row.role() == Role.ADM && (row.userId().equals(subject.userId()) || store.activeAdminCount() <= 1)) {
			throw ApiException.of(Code.FORBIDDEN);
		}
		Instant now = clock.instant();
		store.revoke(assignmentId, subject.userId(), now);
		if (row.role() == Role.GON) {
			events.publishEvent(new ParticipationRevoked(row.scopeId(), row.userId(), subject.userId(), now));
		}
	}

	/** AC-03. */
	@Transactional(readOnly = true)
	Page list(Subject subject, UUID userId, UUID scopeId, Role role, Boolean active, UUID after, int limit) {
		requireAssigner(subject);
		List<AssignmentRow> rows = store.list(userId, scopeId, role, active, after, limit + 1);
		boolean more = rows.size() > limit;
		List<AssignmentRow> current = more ? rows.subList(0, limit) : rows;
		List<RoleAssignmentView> items = views(current);
		return new Page(items, more ? current.getLast().id() : null);
	}

	record Page(List<RoleAssignmentView> items, UUID lastId) {
	}

	private void requireAssigner(Subject subject) {
		Grants grants = store.grants(subject.userId());
		Grants.corporate(subject, grants.has(Role.ADM), Role.ADM).require();   // role.assign: yalnızca ADM
	}

	private RoleAssignmentView view(AssignmentRow row) {
		return views(List.of(row)).getFirst();
	}

	private List<RoleAssignmentView> views(List<AssignmentRow> rows) {
		Set<UUID> userIds = new HashSet<>();
		Set<UUID> scopeIds = new HashSet<>();
		rows.forEach(row -> {
			userIds.add(row.userId());
			if (row.grantedBy() != null) {
				userIds.add(row.grantedBy());
			}
			scopeIds.add(row.scopeId());
		});
		Map<UUID, String> names = users.displayNames(userIds);
		Map<UUID, String> scopeNames = scopes.names(scopeIds);
		return rows.stream()
			.map(row -> new RoleAssignmentView(row.id(), new RoleAssignmentView.UserRef(row.userId(), names.get(row.userId())),
					row.role(), row.scopeType(), row.scopeId(), scopeNames.get(row.scopeId()),
					row.grantedBy() == null ? null : new RoleAssignmentView.UserRef(row.grantedBy(), names.get(row.grantedBy())),
					row.grantedAt(), row.revokedAt()))
			.toList();
	}

}
