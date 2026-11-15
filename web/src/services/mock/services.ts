import type {
  AccountService,
  AnnouncementService,
  AuthService,
  GroupService,
  InvitationService,
  MeContextService,
  NotificationService,
  OperationListService,
  OperationService,
  ParticipantService,
  TaskService,
} from '../contracts';
import type {
  AssignmentView,
  EffectivePermissions,
  AnnouncementView,
  GroupView,
  InboxItem,
  InvitationView,
  MeContext,
  MeProfile,
  OperationState,
  OperationView,
  ParticipantView,
  RoleAssignmentView,
  TaskState,
  TaskView,
  UserRef,
  Uuid,
} from '../types';
import { fail, mockStore, MOCK_IDS, nextId, nowIso, respond, type MockPersona } from './store';

/**
 * `mock` uygulamaları: sözleşmedeki kuralları (durum geçişleri, lider zorunluluğu, kapasite, sürüm)
 * bellek içinde taklit eder ve aynı hata kodlarını üretir. Gerçek backend davranışının kanıtı değildir.
 */

const OPERATIONAL_PERMS = [
  'operation.manage',
  'group.view',
  'group.create.operational',
  'group.update',
  'membership.add',
  'membership.remove',
  'membership.role.update',
  'meeting_point.update',
  'task.manage',
  'invitation.create',
  'alert.send',
  'location.view',
  'announcement.send.group',
];

const PERSONA_USER: Record<MockPersona, Uuid> = {
  ADM: MOCK_IDS.adm,
  KOR: MOCK_IDS.kor,
  TEK: MOCK_IDS.tek,
  UYM: MOCK_IDS.uym,
};

function currentUserRef(): UserRef {
  const user = mockStore.users.get(PERSONA_USER[mockStore.persona])!;
  return { id: user.id, displayName: user.displayName };
}

function isCoordinatorOf(operationId: Uuid): boolean {
  const op = mockStore.operations.get(operationId);
  return !!op?.coordinators.some((c) => c.id === PERSONA_USER[mockStore.persona]);
}

function canSee(operationId: Uuid): boolean {
  return mockStore.persona === 'ADM' || isCoordinatorOf(operationId);
}

function can(operationId: Uuid, permission: string): boolean {
  if (isCoordinatorOf(operationId)) return OPERATIONAL_PERMS.includes(permission);
  if (mockStore.persona === 'ADM') return permission === 'operation.manage' || permission === 'group.view';
  return false;
}

function isClosed(state: OperationState): boolean {
  return state === 'COMPLETED' || state === 'CANCELLED';
}

// ------------------------------------------------------------------ auth

export function mockAuthService(): AuthService {
  return {
    fetchCsrf: () => respond(undefined),
    login: async ({ email, password }) => {
      const normalized = email.trim().toLowerCase();
      // Hata durumlarını denemek için ayrılmış örnek adresler (yalnızca mock).
      if (normalized.startsWith('kilitli@')) return fail(423, 'ACCOUNT_LOCKED', {}, undefined, 30);
      if (normalized.startsWith('pasif@')) return fail(403, 'ACCOUNT_DISABLED');
      if (normalized.startsWith('gonullu@')) return fail(403, 'CLIENT_NOT_ALLOWED');
      if (normalized.startsWith('mfa@')) {
        return respond({ mfaRequired: true, mfaEnrollmentRequired: false, mfaTicket: 'mock-ticket', mustChangePassword: false });
      }
      if (!password || password === 'yanlis') return fail(401, 'AUTH_INVALID_CREDENTIALS');
      // Backend ID-02 gibi: ADM/KOR web girişinde oturum açılmaz, MFA bileti döner (tanıtım atlaması seçilmediyse).
      if ((mockStore.persona === 'ADM' || mockStore.persona === 'KOR') && !mockStore.mfaBypass) {
        return respond({ mfaRequired: true, mfaEnrollmentRequired: false, mfaTicket: 'mock-ticket', mustChangePassword: false });
      }
      mockStore.loggedIn = true;
      return respond({
        mfaRequired: false,
        mustChangePassword: false,
        session: { sessionId: nextId('7000'), clientType: 'WEB' as const, mfaVerified: false, expiresAt: nowIso() },
      });
    },
    enrollMfa: async (mfaTicket) => {
      if (mfaTicket !== 'mock-ticket') return fail(401, 'AUTH_MFA_REQUIRED');
      return respond({ otpauthUri: 'otpauth://totp/GKS:ornek?secret=JBSWY3DPEHPK3PXP&issuer=GKS', secret: 'JBSWY3DPEHPK3PXP' });
    },
    verifyMfa: async (mfaTicket, code) => {
      if (mfaTicket !== 'mock-ticket') return fail(401, 'AUTH_MFA_REQUIRED');
      // Örnek veri: 6 haneli her kod kabul edilir, 000000 hatalı sayılır.
      if (!/^\d{6}$/.test(code)) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'code', code: 'INVALID_FORMAT' }]);
      if (code === '000000') return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'code', code: 'INVALID_CODE' }]);
      mockStore.loggedIn = true;
      mockStore.mfaBypass = true;
      return respond({
        mfaRequired: false,
        mustChangePassword: false,
        session: { sessionId: nextId('7000'), clientType: 'WEB' as const, mfaVerified: true, expiresAt: nowIso() },
      });
    },
    changePassword: async ({ currentPassword, newPassword }) => {
      if (currentPassword === 'yanlis') return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'currentPassword', code: 'INVALID' }]);
      if (newPassword.length < 10) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'newPassword', code: 'TOO_SHORT' }]);
      return respond(undefined);
    },
    logout: async () => {
      mockStore.loggedIn = false;
      return respond(undefined);
    },
  };
}

// --------------------------------------------------------------- account

/** Örnek profil (yalnızca mock); backend ID-10 kurallarının bir alt kümesi. */
let mockProfile: MeProfile | null = null;

export function mockAccountService(): AccountService {
  const current = (): MeProfile =>
    (mockProfile ??= {
      id: MOCK_IDS.kor,
      email: 'kor@ornek.invalid',
      fullName: 'Koordinatör Örnek',
      displayName: 'Koordinatör (örnek)',
      status: 'ACTIVE',
      mfaEnrolled: true,
      createdAt: nowIso(),
    });
  return {
    getProfile: () => respond(current()),
    updateProfile: async (update) => {
      if (update.phone && !/^\+[1-9][0-9]{7,14}$/.test(update.phone)) {
        return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'phone', code: 'INVALID_FORMAT' }]);
      }
      const next: MeProfile = { ...current(), ...update, phone: update.phone === null ? undefined : (update.phone ?? current().phone) };
      mockProfile = next;
      return respond(next);
    },
  };
}

// ---------------------------------------------------------- notifications

/** Örnek gelen kutusu (yalnızca mock): gerçek teslim backend'dedir. */
const mockInbox: InboxItem[] = [
  {
    id: 'mock-inbox-1',
    category: 'ANNOUNCEMENT',
    kind: 'ANNOUNCEMENT',
    priority: 'NORMAL',
    title: 'Örnek duyuru',
    body: 'Bu bir örnek veri kaydıdır.',
    createdAt: '2026-11-15T09:00:00Z',
    link: { type: 'ANNOUNCEMENT', id: 'mock-ann-1' },
  },
];

export function mockNotificationService(): NotificationService {
  return {
    list: ({ unreadOnly } = {}) => respond({ items: mockInbox.filter((i) => !unreadOnly || !i.readAt) }),
    get: async (id) => {
      const item = mockInbox.find((i) => i.id === id);
      return item ? respond(item) : fail(404, 'NOT_FOUND');
    },
    markRead: async (id) => {
      const item = mockInbox.find((i) => i.id === id);
      if (!item) return fail(404, 'NOT_FOUND');
      item.readAt ??= nowIso();
      return respond(undefined);
    },
    unreadCount: () => respond(mockInbox.filter((i) => !i.readAt).length),
  };
}

// ---------------------------------------------------------- announcements

const mockAnnouncements: AnnouncementView[] = [];

export function mockAnnouncementService(): AnnouncementService {
  const recipients = [{ id: MOCK_IDS.volunteer(1), displayName: 'Ali K.' }, { id: MOCK_IDS.volunteer(2), displayName: 'Ayşe D.' }];
  return {
    preview: () => respond({ recipientCount: recipients.length, recipients, confirmationRequired: false }),
    create: async (request) => {
      const view: AnnouncementView = {
        id: nextId('9000'),
        title: request.title,
        body: request.body,
        operationId: request.operationId,
        createdAt: nowIso(),
        recipientCount: recipients.length,
        delivery: { sent: recipients.length, failed: 0, read: 0 },
      };
      mockAnnouncements.unshift(view);
      return respond(view);
    },
    list: ({ operationId } = {}) =>
      // NT-03 listesi gövdeyi içermez (yalnızca NT-06 ayrıntısı).
      respond({ items: mockAnnouncements.filter((a) => !operationId || a.operationId === operationId).map((a) => ({ ...a, body: undefined })) }),
    get: async (id) => {
      const view = mockAnnouncements.find((a) => a.id === id);
      return view ? respond(view) : fail(404, 'NOT_FOUND');
    },
  };
}

// ------------------------------------------------------------- meContext

export function setMockPersona(persona: MockPersona): void {
  mockStore.persona = persona;
}

export function getMockPersona(): MockPersona {
  return mockStore.persona;
}

/** Yalnızca örnek veri tanıtımı: ADM/KOR için TOTP adımını açıkça atla (gerçek sunucu bu oturumu açmaz). */
export function setMockMfaBypass(value: boolean): void {
  mockStore.mfaBypass = value;
}

/** Giriş `http` iken bile mock bağlamın oturum açıldığını bilmesi için (bkz. SessionProvider). */
export function markMockLoggedIn(value: boolean): void {
  mockStore.loggedIn = value;
}

export function mockMeContextService(): MeContextService {
  return {
    getContext: async (): Promise<MeContext> => {
      if (!mockStore.loggedIn) return fail(401, 'AUTH_UNAUTHENTICATED');
      const persona = mockStore.persona;
      const me = currentUserRef();
      const roles: RoleAssignmentView[] = [];
      const permissions: EffectivePermissions = { organization: [], operations: {}, groups: {} };
      const grantedBy = { id: MOCK_IDS.adm, displayName: 'Yönetici (örnek)' };
      const visibleOps = [...mockStore.operations.values()].filter((o) => !isClosed(o.state));

      if (persona === 'ADM') {
        roles.push({
          id: nextId('8000'),
          user: me,
          role: 'ADM',
          scopeType: 'ORGANIZATION',
          scopeId: MOCK_IDS.org,
          scopeName: 'Örnek Kurum',
          grantedBy,
          grantedAt: '2026-01-01T00:00:00Z',
        });
        permissions.organization = ['operation.create', 'operation.manage', 'invitation.create', 'role.assign', 'account.disable', 'session.revoke', 'user.status.view', 'audit.view', 'mfa.reset', 'announcement.send.organization'];
        for (const op of mockStore.operations.values()) permissions.operations[op.id] = ['operation.manage', 'group.view'];
      } else if (persona === 'KOR') {
        for (const op of visibleOps.filter((o) => isCoordinatorOf(o.id))) {
          roles.push({
            id: nextId('8000'),
            user: me,
            role: 'KOR',
            scopeType: 'OPERATION',
            scopeId: op.id,
            scopeName: op.name,
            grantedBy,
            grantedAt: op.createdAt,
          });
          permissions.operations[op.id] = [...OPERATIONAL_PERMS];
        }
      } else if (persona === 'TEK') {
        roles.push({ id: nextId('8000'), user: me, role: 'TEK', scopeType: 'ORGANIZATION', scopeId: MOCK_IDS.org, scopeName: 'Örnek Kurum', grantedBy, grantedAt: '2026-01-01T00:00:00Z' });
        permissions.organization = ['user.status.view', 'account.disable', 'session.revoke'];
      } else {
        roles.push({ id: nextId('8000'), user: me, role: 'UYM', scopeType: 'ORGANIZATION', scopeId: MOCK_IDS.org, scopeName: 'Örnek Kurum', grantedBy, grantedAt: '2026-01-01T00:00:00Z' });
        permissions.organization = ['audit.view'];
      }

      return respond({
        user: me,
        session: {
          sessionId: '0192f000-7000-7000-8000-000000000000',
          clientType: 'WEB',
          // Gerçek MFA doğrulaması değildir: yalnızca açıkça seçilen tanıtım atlamasını yansıtır.
          mfaVerified: (persona === 'ADM' || persona === 'KOR') && mockStore.mfaBypass,
          expiresAt: nowIso(),
          mustChangePassword: false,
        },
        roles,
        operations: visibleOps
          .filter((o) => persona === 'KOR' && isCoordinatorOf(o.id))
          .map((o) => ({ id: o.id, name: o.name, state: o.state, myRoles: ['KOR' as const] })),
        groups: [],
        pendingInvitations: [],
        permissions,
      });
    },
  };
}

// ------------------------------------------------------------- operations

const OPERATION_TRANSITIONS: Record<OperationState, OperationState[]> = {
  PLANNED: ['ACTIVE', 'CANCELLED'],
  ACTIVE: ['SUSPENDED', 'COMPLETED', 'CANCELLED'],
  SUSPENDED: ['ACTIVE', 'COMPLETED', 'CANCELLED'],
  COMPLETED: [],
  CANCELLED: [],
};

function loadOperation(id: Uuid): OperationView | Promise<never> {
  const op = mockStore.operations.get(id);
  if (!op) return fail(404, 'NOT_FOUND');
  if (!canSee(id)) return fail(403, 'FORBIDDEN');
  return op;
}

export function mockOperationService(): OperationService {
  return {
    create: async (request, idempotencyKey) => {
      const replayKey = idempotencyKey ? `OR-01:${idempotencyKey}` : undefined;
      if (replayKey && mockStore.idempotency.has(replayKey)) return respond(mockStore.idempotency.get(replayKey) as OperationView);
      if (mockStore.persona !== 'ADM') return fail(403, 'FORBIDDEN');
      const name = request.name.trim();
      const errors: { field: string; code: string }[] = [];
      if (name.length < 3) errors.push({ field: 'name', code: 'TOO_SHORT' });
      if (name.length > 120) errors.push({ field: 'name', code: 'TOO_LONG' });
      if (request.coordinatorIds.length === 0) errors.push({ field: 'coordinatorIds', code: 'REQUIRED' });
      if (request.plannedEndAt && new Date(request.plannedEndAt).getTime() <= Date.now()) {
        errors.push({ field: 'plannedEndAt', code: 'OUT_OF_RANGE' });
      }
      if (errors.length) return fail(400, 'VALIDATION_FAILED', {}, errors);
      const coordinators = request.coordinatorIds.map((id) => {
        const u = mockStore.users.get(id);
        return { id, displayName: u?.displayName ?? id };
      });
      const op: OperationView = {
        id: nextId('3000'),
        name,
        state: 'PLANNED',
        createdAt: nowIso(),
        coordinators,
        pendingAssignmentCount: 0,
        openAlertCount: 0,
        ...(request.description ? { description: request.description.trim() } : {}),
        ...(request.plannedEndAt ? { plannedEndAt: request.plannedEndAt } : {}),
      };
      mockStore.operations.set(op.id, op);
      mockStore.participants.set(op.id, []);
      if (replayKey) mockStore.idempotency.set(replayKey, structuredClone(op));
      return respond(op);
    },
    get: async (id) => respond(await loadOperation(id)),
    update: async (id, request) => {
      const op = await loadOperation(id);
      if (!can(id, 'operation.manage')) return fail(403, 'FORBIDDEN');
      if (isClosed(op.state)) return fail(409, 'OPERATION_CLOSED');
      if (request.name !== undefined) op.name = request.name.trim();
      if (request.description !== undefined) {
        if (request.description === null || request.description === '') delete op.description;
        else op.description = request.description.trim();
      }
      if (request.plannedEndAt !== undefined) {
        if (request.plannedEndAt === null) delete op.plannedEndAt;
        else op.plannedEndAt = request.plannedEndAt;
      }
      return respond(op);
    },
    changeState: async (id, { targetState }) => {
      const op = await loadOperation(id);
      if (!can(id, 'operation.manage')) return fail(403, 'FORBIDDEN');
      if (!OPERATION_TRANSITIONS[op.state].includes(targetState)) {
        return fail(409, 'INVALID_STATE_TRANSITION', { currentState: op.state, requestedState: targetState });
      }
      op.state = targetState;
      if (targetState === 'ACTIVE' && !op.activatedAt) op.activatedAt = nowIso();
      if (isClosed(targetState)) {
        op.closedAt = nowIso();
        for (const g of mockStore.groups.values()) if (g.operationId === id) g.archived = true;
      }
      return respond(op);
    },
  };
}

export function mockOperationListService(): OperationListService {
  return {
    list: async (filter = {}) => {
      const items = [...mockStore.operations.values()]
        .filter((o) => canSee(o.id))
        .filter((o) => !filter.state?.length || filter.state.includes(o.state))
        .sort((a, b) => (a.id < b.id ? 1 : -1));
      return respond({ items });
    },
  };
}

export function mockParticipantService(): ParticipantService {
  return {
    list: async (operationId, filter = {}) => {
      await loadOperation(operationId);
      const groupsOf = (userId: Uuid) =>
        [...mockStore.groups.values()]
          .filter((g) => g.operationId === operationId && !g.archived && g.members.some((m) => m.user.id === userId))
          .map((g) => ({ id: g.id, name: g.name }));
      const excluded = filter.notInGroup ? mockStore.groups.get(filter.notInGroup) : undefined;
      const items: ParticipantView[] = (mockStore.participants.get(operationId) ?? [])
        .filter((p) => !excluded || !excluded.members.some((m) => m.user.id === p.userId))
        .map((p) => ({
          user: mockStore.users.get(p.userId)!,
          joinedAt: p.joinedAt,
          groups: groupsOf(p.userId),
          openAssignmentCount: [...mockStore.assignments.values()].filter(
            (a) => a.user.id === p.userId && (a.status === 'PENDING' || a.status === 'ACCEPTED'),
          ).length,
        }));
      return respond({ items });
    },
  };
}

// ----------------------------------------------------------------- groups

const GROUP_LIMIT = 50;

function isParticipant(operationId: Uuid, userId: Uuid): boolean {
  return (mockStore.participants.get(operationId) ?? []).some((p) => p.userId === userId);
}

async function loadGroup(groupId: Uuid): Promise<GroupView> {
  const g = mockStore.groups.get(groupId);
  if (!g) return fail(404, 'NOT_FOUND');
  if (!canSee(g.operationId)) return fail(403, 'FORBIDDEN');
  return g;
}

async function guardGroupWrite(g: GroupView, permission: string): Promise<void> {
  if (!can(g.operationId, permission)) await fail(403, 'FORBIDDEN');
  const op = mockStore.operations.get(g.operationId);
  if (g.archived || (op && isClosed(op.state))) await fail(409, 'OPERATION_CLOSED');
}

function refreshCounts(g: GroupView): void {
  g.memberCount = g.members.length;
  const leader = g.members.find((m) => m.role === 'LEADER');
  if (leader) g.leader = { id: leader.user.id, displayName: leader.user.displayName };
}

export function mockGroupService(): GroupService {
  return {
    list: async (operationId, filter = {}) => {
      await loadOperation(operationId);
      const items = [...mockStore.groups.values()]
        .filter((g) => g.operationId === operationId && (filter.includeArchived || !g.archived))
        .map(({ id, name, operationId: oid, operationName, leader, memberCount, archived }) => ({
          id,
          name,
          operationId: oid,
          operationName,
          leader,
          memberCount,
          archived,
        }));
      return respond({ items });
    },
    get: async (groupId) => respond(await loadGroup(groupId)),
    create: async (operationId, request, idempotencyKey) => {
      const replayKey = idempotencyKey ? `GR-01:${idempotencyKey}` : undefined;
      if (replayKey && mockStore.idempotency.has(replayKey)) return respond(mockStore.idempotency.get(replayKey) as GroupView);
      const op = await loadOperation(operationId);
      if (!can(operationId, 'group.create.operational')) return fail(403, 'FORBIDDEN');
      if (isClosed(op.state)) return fail(409, 'OPERATION_CLOSED');
      if (!request.leaderUserId) return fail(409, 'LEADER_REQUIRED');
      const name = request.name.trim();
      const duplicate = [...mockStore.groups.values()].some(
        (g) => g.operationId === operationId && !g.archived && g.name.toLocaleLowerCase('tr') === name.toLocaleLowerCase('tr'),
      );
      if (duplicate) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'name', code: 'DUPLICATE' }]);
      const memberIds = [...new Set((request.memberUserIds ?? []).filter((u) => u !== request.leaderUserId))];
      if (memberIds.length + 1 > GROUP_LIMIT) return fail(409, 'GROUP_FULL', { memberCount: memberIds.length + 1, limit: GROUP_LIMIT });
      for (const uid of [request.leaderUserId, ...memberIds]) {
        if (!isParticipant(operationId, uid)) return fail(409, 'NOT_A_PARTICIPANT');
      }
      const now = nowIso();
      const leader = mockStore.users.get(request.leaderUserId)!;
      const group: GroupView = {
        id: nextId('4000'),
        name,
        description: request.description?.trim() || undefined,
        operationId,
        operationName: op.name,
        leader: { id: leader.id, displayName: leader.displayName },
        memberCount: 1 + memberIds.length,
        archived: false,
        members: [
          { user: leader, role: 'LEADER', joinedAt: now },
          ...memberIds.map((uid) => ({ user: mockStore.users.get(uid)!, role: 'MEMBER' as const, joinedAt: now })),
        ],
        createdAt: now,
        type: 'OPERATIONAL',
        joinPolicy: 'MANAGED',
      };
      mockStore.groups.set(group.id, group);
      if (replayKey) mockStore.idempotency.set(replayKey, structuredClone(group));
      return respond(group);
    },
    addMember: async (groupId, userId) => {
      const g = await loadGroup(groupId);
      await guardGroupWrite(g, 'membership.add');
      if (!isParticipant(g.operationId, userId)) return fail(409, 'NOT_A_PARTICIPANT');
      if (g.members.some((m) => m.user.id === userId)) return fail(409, 'ALREADY_EXISTS', { conflict: 'ALREADY_MEMBER' });
      if (g.members.length >= GROUP_LIMIT) return fail(409, 'GROUP_FULL', { memberCount: g.members.length, limit: GROUP_LIMIT });
      const m = { user: mockStore.users.get(userId)!, role: 'MEMBER' as const, joinedAt: nowIso() };
      g.members.push(m);
      refreshCounts(g);
      return respond(m);
    },
    removeMember: async (groupId, userId) => {
      const g = await loadGroup(groupId);
      await guardGroupWrite(g, 'membership.remove');
      const m = g.members.find((x) => x.user.id === userId);
      if (!m) return fail(404, 'NOT_FOUND');
      if (m.role === 'LEADER') return fail(409, 'LEADER_REQUIRED');
      g.members = g.members.filter((x) => x.user.id !== userId);
      refreshCounts(g);
      return respond(undefined);
    },
    changeLeader: async (groupId, userId) => {
      const g = await loadGroup(groupId);
      await guardGroupWrite(g, 'membership.role.update');
      if (!isParticipant(g.operationId, userId)) return fail(409, 'NOT_A_PARTICIPANT');
      let target = g.members.find((m) => m.user.id === userId);
      if (!target) {
        if (g.members.length >= GROUP_LIMIT) return fail(409, 'GROUP_FULL', { memberCount: g.members.length, limit: GROUP_LIMIT });
        target = { user: mockStore.users.get(userId)!, role: 'MEMBER', joinedAt: nowIso() };
        g.members.push(target);
      }
      for (const m of g.members) if (m.role === 'LEADER') m.role = 'MEMBER';
      target.role = 'LEADER';
      refreshCounts(g);
      return respond(g);
    },
    update: async (groupId, request) => {
      const g = await loadGroup(groupId);
      await guardGroupWrite(g, 'group.update');
      if (request.name !== undefined) g.name = request.name.trim();
      if (request.name === null) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'name', code: 'REQUIRED' }]);
      if (request.description !== undefined) g.description = request.description?.trim() || undefined;
      return respond(g);
    },
    setMeetingPoint: async (groupId, request) => {
      const g = await loadGroup(groupId);
      await guardGroupWrite(g, 'meeting_point.update');
      g.meetingPoint = { ...request, updatedBy: currentUserRef(), updatedAt: nowIso() };
      return respond(g.meetingPoint);
    },
  };
}

// ------------------------------------------------------------------ tasks

const TASK_TRANSITIONS: Record<TaskState, TaskState[]> = {
  DRAFT: ['CANCELLED'],
  PUBLISHED: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['DONE', 'CANCELLED'],
  DONE: [],
  CANCELLED: [],
};

async function loadTask(taskId: Uuid) {
  const t = mockStore.tasks.get(taskId);
  if (!t) return fail(404, 'NOT_FOUND');
  if (!canSee(t.operationId)) return fail(403, 'FORBIDDEN');
  return t;
}

async function guardTaskWrite(operationId: Uuid): Promise<void> {
  if (!can(operationId, 'task.manage')) await fail(403, 'FORBIDDEN');
  const op = mockStore.operations.get(operationId);
  if (op && isClosed(op.state)) await fail(409, 'OPERATION_CLOSED');
}

function recountTask(t: TaskView & { operationId: Uuid }): void {
  const list = [...mockStore.assignments.values()].filter((a) => a.taskId === t.id);
  t.acceptedCount = list.filter((a) => a.status === 'ACCEPTED').length;
  t.pendingCount = list.filter((a) => a.status === 'PENDING').length;
  t.overdueCount = list.filter((a) => a.status === 'PENDING' && a.overdue).length;
}

function toView(t: TaskView & { operationId: Uuid }): TaskView {
  const { operationId: _omit, ...view } = t;
  void _omit;
  return view;
}

function pushHistory(t: TaskView, state: TaskState) {
  t.history = [...(t.history ?? []), { state, changedBy: currentUserRef(), changedAt: nowIso() }];
}

/** DB ck_tasks_time_window ile aynı: bitiş varsa başlangıç zorunlu, başlangıç < bitiş. */
async function checkTimeWindow(tw: TaskView['timeWindow'] | null | undefined): Promise<void> {
  if (!tw) return;
  if (tw.end && !tw.start) await fail(400, 'VALIDATION_FAILED', {}, [{ field: 'timeWindow.start', code: 'REQUIRED' }]);
  if (tw.start && tw.end && new Date(tw.start).getTime() >= new Date(tw.end).getTime()) {
    await fail(400, 'VALIDATION_FAILED', {}, [{ field: 'timeWindow.end', code: 'OUT_OF_RANGE' }]);
  }
}

function newAssignment(taskId: Uuid, userId: Uuid, windowMinutes: number): AssignmentView {
  return {
    id: nextId('6000'),
    taskId,
    user: mockStore.users.get(userId)!,
    status: 'PENDING',
    respondBy: new Date(Date.now() + windowMinutes * 60_000).toISOString(),
    overdue: false,
  };
}

export function mockTaskService(): TaskService {
  return {
    list: async (operationId, filter = {}) => {
      await loadOperation(operationId);
      const items = [...mockStore.tasks.values()]
        .filter((t) => t.operationId === operationId && (!filter.state || filter.state === t.state))
        .sort((a, b) => (a.id < b.id ? 1 : -1))
        .map(({ id, title, priority, state, acceptedCount, pendingCount, overdueCount, capacity, timeWindow, createdAt }) => ({
          id,
          title,
          priority,
          state,
          acceptedCount,
          pendingCount,
          overdueCount,
          capacity,
          timeWindow,
          createdAt,
        }));
      return respond({ items });
    },
    get: async (taskId) => respond(toView(await loadTask(taskId))),
    assignments: async (taskId, filter = {}) => {
      await loadTask(taskId);
      const items = [...mockStore.assignments.values()].filter(
        (a) =>
          a.taskId === taskId &&
          (!filter.status?.length || filter.status.includes(a.status)) &&
          (filter.overdue === undefined || a.overdue === filter.overdue),
      );
      return respond({ items });
    },
    create: async (operationId, request) => {
      const op = await loadOperation(operationId);
      await guardTaskWrite(operationId);
      if (op.state !== 'PLANNED' && op.state !== 'ACTIVE') return fail(409, 'OPERATION_CLOSED');
      const userIds = request.targets.userIds ?? [];
      const groupIds = request.targets.groupIds ?? [];
      if (userIds.length + groupIds.length === 0) {
        return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'targets', code: 'REQUIRED' }]);
      }
      await checkTimeWindow(request.timeWindow);
      if (userIds.some((u) => !isParticipant(operationId, u))) return fail(403, 'FORBIDDEN');
      const me = currentUserRef();
      const now = nowIso();
      const task = {
        id: nextId('5000'),
        operationId,
        title: request.title.trim(),
        description: request.description,
        priority: request.priority ?? 'NORMAL',
        state: 'DRAFT' as const,
        acceptedCount: 0,
        pendingCount: 0,
        overdueCount: 0,
        capacity: request.capacity,
        timeWindow: request.timeWindow,
        meetingPoint: request.meetingPoint,
        targets: { userIds, groupIds },
        responseWindowMinutes: request.responseWindowMinutes ?? 60,
        version: 1,
        createdBy: me,
        createdAt: now,
        history: [{ state: 'DRAFT' as const, changedBy: me, changedAt: now }],
      };
      mockStore.tasks.set(task.id, task);
      return respond(toView(task));
    },
    update: async (taskId, request) => {
      const t = await loadTask(taskId);
      await guardTaskWrite(t.operationId);
      if (t.state === 'DONE' || t.state === 'CANCELLED') {
        return fail(409, 'INVALID_STATE_TRANSITION', { currentState: t.state });
      }
      if (request.version !== t.version) return fail(409, 'VERSION_CONFLICT', { currentVersion: t.version });
      if (request.targets && t.state !== 'DRAFT') {
        return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'targets', code: 'READ_ONLY' }]);
      }
      // LD-52: zorunlu alana null → 400 REQUIRED.
      for (const field of ['title', 'targets'] as const) {
        if (request[field] === null) return fail(400, 'VALIDATION_FAILED', {}, [{ field, code: 'REQUIRED' }]);
      }
      await checkTimeWindow(request.timeWindow);
      if (typeof request.capacity === 'number' && request.capacity < t.acceptedCount) {
        return fail(409, 'CAPACITY_BELOW_ACCEPTED', { acceptedCount: t.acceptedCount });
      }
      const { version: _v, ...changes } = request;
      void _v;
      // Gönderilmeyen alan korunur; null → temizlenir (yanıtta alan yazılmaz); yanıt süresi varsayılana döner.
      for (const [key, value] of Object.entries(changes)) {
        const target = t as unknown as Record<string, unknown>;
        if (value === undefined) continue;
        if (value === null) {
          if (key === 'responseWindowMinutes') t.responseWindowMinutes = 60;
          else delete target[key];
        } else {
          target[key] = value;
        }
      }
      t.version += 1;
      return respond(toView(t));
    },
    publish: async (taskId, version, idempotencyKey) => {
      const replayKey = `TK-03:${idempotencyKey}`;
      if (mockStore.idempotency.has(replayKey)) return respond(mockStore.idempotency.get(replayKey) as TaskView);
      const t = await loadTask(taskId);
      await guardTaskWrite(t.operationId);
      if (t.state !== 'DRAFT') return fail(409, 'INVALID_STATE_TRANSITION', { currentState: t.state, requestedState: 'PUBLISHED' });
      if (version !== t.version) return fail(409, 'VERSION_CONFLICT', { currentVersion: t.version });
      const recipients = new Set<Uuid>(t.targets?.userIds ?? []);
      for (const gid of t.targets?.groupIds ?? []) {
        mockStore.groups.get(gid)?.members.forEach((m) => recipients.add(m.user.id));
      }
      if (recipients.size === 0) {
        return fail(422, 'VALIDATION_FAILED', {}, [{ field: 'targets', code: 'EMPTY_AUDIENCE' }]);
      }
      for (const uid of recipients) {
        const a = newAssignment(t.id, uid, t.responseWindowMinutes);
        mockStore.assignments.set(a.id, a);
      }
      t.state = 'PUBLISHED';
      t.publishedAt = nowIso();
      t.version += 1;
      pushHistory(t, 'PUBLISHED');
      recountTask(t);
      const view = toView(t);
      mockStore.idempotency.set(replayKey, structuredClone(view));
      return respond(view);
    },
    changeState: async (taskId, { targetState }) => {
      const t = await loadTask(taskId);
      await guardTaskWrite(t.operationId);
      if (!TASK_TRANSITIONS[t.state].includes(targetState)) {
        return fail(409, 'INVALID_STATE_TRANSITION', { currentState: t.state, requestedState: targetState });
      }
      t.state = targetState;
      t.version += 1;
      pushHistory(t, targetState);
      return respond(toView(t));
    },
    addAssignment: async (taskId, userId) => {
      const t = await loadTask(taskId);
      await guardTaskWrite(t.operationId);
      if (t.state !== 'PUBLISHED' && t.state !== 'IN_PROGRESS') {
        return fail(409, 'INVALID_STATE_TRANSITION', { currentState: t.state });
      }
      if (!isParticipant(t.operationId, userId)) return fail(409, 'NOT_A_PARTICIPANT');
      const existing = [...mockStore.assignments.values()].find((a) => a.taskId === taskId && a.user.id === userId);
      if (existing && (existing.status === 'PENDING' || existing.status === 'ACCEPTED')) {
        return fail(409, 'ALREADY_EXISTS', { conflict: 'ASSIGNMENT_ACTIVE' });
      }
      if (t.capacity !== undefined && t.acceptedCount >= t.capacity) {
        return fail(409, 'CAPACITY_FULL', { acceptedCount: t.acceptedCount, capacity: t.capacity });
      }
      let assignment: AssignmentView;
      if (existing) {
        existing.status = 'PENDING';
        existing.overdue = false;
        existing.respondBy = new Date(Date.now() + t.responseWindowMinutes * 60_000).toISOString();
        delete existing.respondedAt;
        delete existing.removedBy;
        assignment = existing;
      } else {
        assignment = newAssignment(taskId, userId, t.responseWindowMinutes);
        mockStore.assignments.set(assignment.id, assignment);
      }
      recountTask(t);
      return respond(assignment);
    },
    removeAssignment: async (taskId, assignmentId) => {
      const t = await loadTask(taskId);
      await guardTaskWrite(t.operationId);
      const a = mockStore.assignments.get(assignmentId);
      if (!a || a.taskId !== taskId) return fail(404, 'NOT_FOUND');
      if (a.status === 'REMOVED') return fail(409, 'INVALID_STATE_TRANSITION', { currentState: 'REMOVED' });
      a.status = 'REMOVED';
      a.overdue = false;
      a.removedBy = currentUserRef();
      recountTask(t);
      return respond(undefined);
    },
  };
}

// ------------------------------------------------------------ invitations

function canInvite(scopeType: InvitationView['scopeType'], scopeId: Uuid): boolean {
  if (mockStore.persona === 'ADM') return true;
  return scopeType === 'OPERATION' && mockStore.persona === 'KOR' && isCoordinatorOf(scopeId);
}

/** ID-12 – ID-14 taklidi: kapsam yetkisi, çatışma nedenleri ve süresi dolan davetler backend gibi. */
export function mockInvitationService(): InvitationService {
  return {
    list: async (filter = {}) => {
      if (mockStore.persona !== 'ADM' && mockStore.persona !== 'KOR') return fail(403, 'FORBIDDEN');
      if (filter.scopeId && !canInvite(filter.scopeType ?? 'OPERATION', filter.scopeId)) return fail(403, 'FORBIDDEN');
      const now = Date.now();
      const items = [...mockStore.invitations.values()]
        .map((i) => (i.status === 'PENDING' && new Date(i.expiresAt).getTime() < now ? { ...i, status: 'EXPIRED' as const } : i))
        .filter((i) => canInvite(i.scopeType, i.scopeId))
        .filter((i) => !filter.scopeType || i.scopeType === filter.scopeType)
        .filter((i) => !filter.scopeId || i.scopeId === filter.scopeId)
        .filter((i) => !filter.status?.length || filter.status.includes(i.status))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return respond({ items });
    },
    create: async (request, idempotencyKey) => {
      const replayKey = `ID-12:${idempotencyKey}`;
      if (mockStore.idempotency.has(replayKey)) return respond(mockStore.idempotency.get(replayKey) as InvitationView);
      const email = request.email.trim().toLowerCase();
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
        return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'email', code: 'INVALID_FORMAT' }]);
      }
      let scopeName = 'Örnek Kurum';
      if (request.scopeType === 'OPERATION') {
        const op = mockStore.operations.get(request.scopeId);
        if (!op) return fail(404, 'NOT_FOUND');
        if (!canInvite('OPERATION', op.id)) return fail(403, 'FORBIDDEN');
        if (isClosed(op.state)) return fail(409, 'OPERATION_CLOSED');
        scopeName = op.name;
      } else if (mockStore.persona !== 'ADM') {
        return fail(403, 'FORBIDDEN');
      }
      const registered = [...mockStore.users.values()].find((u) => u.email?.toLowerCase() === email);
      if (registered && request.scopeType === 'ORGANIZATION') return fail(409, 'ALREADY_EXISTS', { conflict: 'EMAIL_REGISTERED' });
      if (registered && (mockStore.participants.get(request.scopeId) ?? []).some((p) => p.userId === registered.id)) {
        return fail(409, 'ALREADY_EXISTS', { conflict: 'ALREADY_PARTICIPANT' });
      }
      const pending = [...mockStore.invitations.values()].some(
        (i) => i.status === 'PENDING' && i.scopeId === request.scopeId && i.email?.toLowerCase() === email,
      );
      if (pending) return fail(409, 'ALREADY_EXISTS', { conflict: 'INVITATION_PENDING' });
      const invitation: InvitationView = {
        id: nextId('7000'),
        kind: registered ? 'OPERATION' : 'NEW_USER',
        status: 'PENDING',
        email,
        scopeType: request.scopeType,
        scopeId: request.scopeId,
        scopeName,
        expiresAt: new Date(Date.now() + 7 * 24 * 3_600_000).toISOString(),
        createdAt: nowIso(),
        createdBy: currentUserRef(),
      };
      mockStore.invitations.set(invitation.id, invitation);
      mockStore.idempotency.set(replayKey, structuredClone(invitation));
      return respond(invitation);
    },
    revoke: async (id) => {
      const invitation = mockStore.invitations.get(id);
      if (!invitation || !canInvite(invitation.scopeType, invitation.scopeId)) return fail(404, 'NOT_FOUND');
      if (invitation.status !== 'PENDING') {
        return fail(409, 'INVALID_STATE_TRANSITION', { currentState: invitation.status, requestedState: 'REVOKED' });
      }
      invitation.status = 'REVOKED';
      return respond(invitation);
    },
  };
}
