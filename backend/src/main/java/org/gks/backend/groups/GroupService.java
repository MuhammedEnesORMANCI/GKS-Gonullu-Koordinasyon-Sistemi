package org.gks.backend.groups;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.gks.backend.ApiException;
import org.gks.backend.ApiException.FieldError;
import org.gks.backend.ApiProblems.Code;
import org.gks.backend.Ids;
import org.gks.backend.access.GroupAccess;
import org.gks.backend.access.GroupAccess.GroupAction;
import org.gks.backend.access.GroupRole;
import org.gks.backend.access.Participation;
import org.gks.backend.access.Subject;
import org.gks.backend.groups.GroupStore.GroupRow;
import org.gks.backend.groups.GroupStore.MemberRow;
import org.gks.backend.groups.GroupStore.SummaryRow;
import org.gks.backend.groups.GroupValidation.CreateInput;
import org.gks.backend.groups.GroupValidation.MeetingPointInput;
import org.gks.backend.groups.GroupValidation.PageRequest;
import org.gks.backend.groups.GroupValidation.UpdateInput;
import org.gks.backend.groups.GroupViews.GroupMemberView;
import org.gks.backend.groups.GroupViews.GroupPage;
import org.gks.backend.groups.GroupViews.GroupSummary;
import org.gks.backend.groups.GroupViews.GroupView;
import org.gks.backend.groups.GroupViews.MeetingPoint;
import org.gks.backend.groups.GroupViews.UserRef;
import org.gks.backend.identity.UserDirectory;
import org.gks.backend.organization.OperationState;
import org.gks.backend.organization.Operations;
import org.gks.backend.organization.Operations.OperationRef;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GR-01 – GR-08 (backend-api-design.md §8.1). Denetim sırası §3.3: doğrulama (400) → kaynak (404) → {@code can()}
 * (403) → kaynak kuralı (409). Yetki kararı access modülündedir; burada yalnızca K2 girdisi (öznenin gruptaki rolü)
 * hazırlanır.
 */
@Service
class GroupService {

	/** D-04, VS-02: grup başına en fazla 50 aktif üye (lider dahil). */
	static final int MEMBER_LIMIT = 50;

	private final GroupStore store;
	private final GroupAccess access;
	private final Participation participation;
	private final Operations operations;
	private final UserDirectory users;
	private final GroupNotifications notifications;
	private final Clock clock;

	GroupService(GroupStore store, GroupAccess access, Participation participation, Operations operations,
			UserDirectory users, GroupNotifications notifications, Clock clock) {
		this.store = store;
		this.access = access;
		this.participation = participation;
		this.operations = operations;
		this.users = users;
		this.notifications = notifications;
		this.clock = clock;
	}

	/** GR-01. Lider aynı işlemde eklenir (E-5). */
	@Transactional
	GroupView create(Subject subject, UUID operationId, CreateInput input) {
		OperationRef operation = operations.findForShare(operationId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		access.can(subject, GroupAction.CREATE, operation.id(), null).require();
		requireOpen(operation);
		if (input.leaderUserId() == null) {
			throw ApiException.of(Code.LEADER_REQUIRED);   // AC-09.5
		}
		int total = input.memberUserIds().size() + 1;
		if (total > MEMBER_LIMIT) {
			throw ApiException.groupFull(total, MEMBER_LIMIT);
		}
		requireParticipant(operation.id(), input.leaderUserId());
		input.memberUserIds().forEach(member -> requireParticipant(operation.id(), member));

		UUID groupId = Ids.newId();
		Instant now = clock.instant();
		try {
			store.insertGroup(groupId, operation.organizationId(), operation.id(), input.name(), input.description(),
					subject.userId(), now);
		}
		catch (DuplicateKeyException ex) {
			throw duplicateName(ex);
		}
		store.insertMembership(Ids.newId(), groupId, input.leaderUserId(), GroupRole.LEADER, subject.userId(), now);
		for (UUID member : input.memberUserIds()) {
			store.insertMembership(Ids.newId(), groupId, member, GroupRole.MEMBER, subject.userId(), now);
		}
		Set<UUID> initial = new java.util.LinkedHashSet<>(input.memberUserIds());
		initial.add(input.leaderUserId());
		notifications.membersAdded(groupId, input.name(), operation.id(), "group:" + groupId + ":CREATED", initial,
				subject.userId());
		return view(store.find(groupId).orElseThrow(), subject.userId(), operation.name());
	}

	/** GR-02. */
	@Transactional(readOnly = true)
	GroupPage list(Subject subject, PageRequest page) {
		List<SummaryRow> rows = store.list(access.listScope(subject), subject.userId(), page.operationId(),
				page.includeArchived(), page.after(), page.limit() + 1);
		boolean more = rows.size() > page.limit();
		List<SummaryRow> current = more ? rows.subList(0, page.limit()) : rows;
		Set<UUID> operationIds = new HashSet<>();
		Set<UUID> leaderIds = new HashSet<>();
		current.forEach(row -> {
			operationIds.add(row.operationId());
			if (row.leaderId() != null) {
				leaderIds.add(row.leaderId());
			}
		});
		Map<UUID, String> operationNames = operations.names(operationIds);
		Map<UUID, String> names = users.displayNames(leaderIds);
		List<GroupSummary> items = current.stream()
			.map(row -> new GroupSummary(row.id(), row.name(), row.operationId(), operationNames.get(row.operationId()),
					ref(row.leaderId(), names), row.memberCount(), row.archived(), row.myRole()))
			.toList();
		return new GroupPage(items, more ? GroupValidation.cursor(current.getLast().id()) : null);
	}

	/** GR-03. Üye olmayan GON {@code 403} alır (AC-10.2); veri dönmez. */
	@Transactional(readOnly = true)
	GroupView get(Subject subject, UUID groupId) {
		GroupRow group = store.find(groupId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		require(subject, GroupAction.VIEW, group);
		return view(group, subject.userId(), null);
	}

	/** GR-04. */
	@Transactional
	GroupMemberView addMember(Subject subject, UUID groupId, UUID userId) {
		GroupRow group = store.lock(groupId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		require(subject, GroupAction.ADD_MEMBER, group);
		requireOpen(group);
		if (store.activeRole(groupId, userId).isPresent()) {
			throw ApiException.alreadyExists("ALREADY_MEMBER");
		}
		requireParticipant(group.operationId(), userId);
		int count = store.activeMemberCount(groupId);
		if (count >= MEMBER_LIMIT) {
			throw ApiException.groupFull(count, MEMBER_LIMIT);
		}
		Instant now = clock.instant();
		store.insertMembership(Ids.newId(), groupId, userId, GroupRole.MEMBER, subject.userId(), now);
		notifications.membersAdded(groupId, group.name(), group.operationId(),
				"group:" + groupId + ":MEMBER:" + userId + ":" + now.toEpochMilli(), List.of(userId), subject.userId());
		return new GroupMemberView(ref(userId, users.displayNames(List.of(userId))), GroupRole.MEMBER, now);
	}

	/** GR-05. Aktif lider çıkarılamaz; önce GR-06 (AC-09.5). Erişim bir sonraki istekte biter (D-01). */
	@Transactional
	void removeMember(Subject subject, UUID groupId, UUID userId) {
		GroupRow group = store.lock(groupId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		require(subject, GroupAction.REMOVE_MEMBER, group);
		requireOpen(group);
		GroupRole role = store.activeRole(groupId, userId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		if (role == GroupRole.LEADER) {
			throw ApiException.of(Code.LEADER_REQUIRED);
		}
		store.endMembership(groupId, userId, subject.userId(), clock.instant());
	}

	/**
	 * GR-06. Sıra önemlidir (E-5): önce eski lider {@code MEMBER}, sonra yeni lider {@code LEADER}; aksi hâlde
	 * {@code ux_group_memberships_active_leader} ara durumda ihlal edilir. Yeni lider üye değilse eklenir.
	 */
	@Transactional
	GroupView changeLeader(Subject subject, UUID groupId, UUID userId) {
		GroupRow group = store.lock(groupId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		require(subject, GroupAction.CHANGE_LEADER, group);
		requireOpen(group);
		Optional<GroupRole> targetRole = store.activeRole(groupId, userId);
		if (targetRole.orElse(null) == GroupRole.LEADER) {
			return view(group, subject.userId(), null);
		}
		if (targetRole.isEmpty()) {
			requireParticipant(group.operationId(), userId);
			int count = store.activeMemberCount(groupId);
			if (count >= MEMBER_LIMIT) {
				throw ApiException.groupFull(count, MEMBER_LIMIT);
			}
		}
		Instant now = clock.instant();
		store.activeMembers(groupId).stream()
			.filter(member -> member.role() == GroupRole.LEADER)
			.forEach(leader -> store.setRole(groupId, leader.userId(), GroupRole.MEMBER, now));
		if (targetRole.isPresent()) {
			store.setRole(groupId, userId, GroupRole.LEADER, now);
		}
		else {
			store.insertMembership(Ids.newId(), groupId, userId, GroupRole.LEADER, subject.userId(), now);
		}
		return view(group, subject.userId(), null);
	}

	/** GR-07. Ad yalnızca KOR; LEADER {@code name} gönderirse {@code 400 READ_ONLY} (§8.2). */
	@Transactional
	GroupView update(Subject subject, UUID groupId, UpdateInput input) {
		GroupRow group = store.lock(groupId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		GroupRole role = role(subject, group);
		access.can(subject, GroupAction.UPDATE, group.operationId(), role).require();
		if (input.name() != null && !access.can(subject, GroupAction.RENAME, group.operationId(), role).allowed()) {
			throw ApiException.validation(List.of(new FieldError("name", "READ_ONLY", "Grup adını yalnızca koordinatör değiştirebilir")));
		}
		requireOpen(group);
		String name = input.name() != null ? input.name() : group.name();
		String description = input.description() != null ? input.description().orElse(null) : group.description();
		try {
			store.updateDetails(groupId, name, description, clock.instant());
		}
		catch (DuplicateKeyException ex) {
			throw duplicateName(ex);
		}
		return view(store.find(groupId).orElseThrow(), subject.userId(), null);
	}

	/** GR-08. Son yazan kazanır (DB §5.1); üyelere bildirim notifications modülüyle gelecek. */
	@Transactional
	MeetingPoint updateMeetingPoint(Subject subject, UUID groupId, MeetingPointInput input) {
		GroupRow group = store.lock(groupId).orElseThrow(() -> ApiException.of(Code.NOT_FOUND));
		require(subject, GroupAction.UPDATE_MEETING_POINT, group);
		requireOpen(group);
		Instant now = clock.instant();
		store.updateMeetingPoint(groupId, input.address(), input.lat(), input.lon(), input.description(), input.time(),
				subject.userId(), now);
		notifications.meetingPointUpdated(groupId, group.name(), group.operationId(), input.address(), now,
				store.activeMembers(groupId).stream().map(MemberRow::userId).toList(), subject.userId());
		GroupRow updated = store.find(groupId).orElseThrow();
		return meetingPoint(updated, users.displayNames(List.of(subject.userId())));
	}

	private void require(Subject subject, GroupAction action, GroupRow group) {
		access.can(subject, action, group.operationId(), role(subject, group)).require();
	}

	/** K2 girdisi: arşivli grupta üyelik yetki vermez (DB §5.2). */
	private GroupRole role(Subject subject, GroupRow group) {
		return group.archivedAt() != null ? null : store.activeRole(group.id(), subject.userId()).orElse(null);
	}

	/** Kapalı operasyonun veya arşivli grubun kaynağına yazma {@code 409 OPERATION_CLOSED}. */
	private void requireOpen(GroupRow group) {
		if (group.archivedAt() != null) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
		requireOpen(operations.findForShare(group.operationId()).orElseThrow(() -> ApiException.of(Code.OPERATION_CLOSED)));
	}

	/** GR-01 operasyonu {@code PLANNED}/{@code ACTIVE} ister; {@code SUSPENDED} Çekirdek'te yoktur. */
	private static void requireOpen(OperationRef operation) {
		if (operation.state() != OperationState.PLANNED && operation.state() != OperationState.ACTIVE) {
			throw ApiException.of(Code.OPERATION_CLOSED);
		}
	}

	private void requireParticipant(UUID operationId, UUID userId) {
		if (!participation.isParticipant(operationId, userId)) {
			throw ApiException.of(Code.NOT_A_PARTICIPANT);   // AC-09.7
		}
	}

	private static ApiException duplicateName(DuplicateKeyException ex) {
		if (String.valueOf(ex.getMessage()).contains("ux_groups_operation_name")) {
			return ApiException.validation(List.of(new FieldError("name", "DUPLICATE", "Bu operasyonda aynı adlı bir grup var")));
		}
		throw ex;
	}

	private GroupView view(GroupRow group, UUID me, String operationName) {
		List<MemberRow> members = store.activeMembers(group.id());
		Set<UUID> ids = new HashSet<>();
		members.forEach(member -> ids.add(member.userId()));
		if (group.mpUpdatedBy() != null) {
			ids.add(group.mpUpdatedBy());
		}
		Map<UUID, String> names = users.displayNames(ids);
		String opName = operationName != null ? operationName
				: operations.find(group.operationId()).map(OperationRef::name).orElse(null);
		UUID leaderId = members.stream().filter(m -> m.role() == GroupRole.LEADER).map(MemberRow::userId).findFirst()
			.orElse(null);
		GroupRole myRole = group.archivedAt() != null ? null : members.stream().filter(m -> m.userId().equals(me))
			.map(MemberRow::role).findFirst().orElse(null);
		List<GroupMemberView> memberViews = new ArrayList<>();
		members.forEach(member -> memberViews.add(new GroupMemberView(ref(member.userId(), names), member.role(),
				member.joinedAt())));
		return new GroupView(group.id(), group.name(), group.operationId(), opName, ref(leaderId, names), members.size(),
				group.archivedAt() != null, myRole, group.description(), memberViews,
				group.mpUpdatedAt() != null ? meetingPoint(group, names) : null, group.createdAt(), "OPERATIONAL",
				"MANAGED");
	}

	private static MeetingPoint meetingPoint(GroupRow group, Map<UUID, String> names) {
		return new MeetingPoint(group.mpAddress(), group.mpLat(), group.mpLon(), group.mpDescription(), group.mpTime(),
				ref(group.mpUpdatedBy(), names), group.mpUpdatedAt());
	}

	private static UserRef ref(UUID userId, Map<UUID, String> names) {
		return userId == null ? null : new UserRef(userId, names.get(userId));
	}

}
