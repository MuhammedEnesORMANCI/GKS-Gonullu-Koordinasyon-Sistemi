import type {
  AccountService,
  AlertService,
  AnnouncementService,
  AuditService,
  AuthService,
  GroupService,
  InvitationService,
  LiveEvent,
  LocationService,
  MeContextService,
  NotificationService,
  OperationListService,
  OperationService,
  ParticipantService,
  PublicAnnouncementService,
  MessagingService,
  RealtimeService,
  RoleAssignmentService,
  TaskService,
  UserAdminService,
  MapService,
} from '../contracts';
import type {
  AlertRecipientView,
  AlertView,
  AssignmentView,
  AuditEntryView,
  EffectivePermissions,
  AnnouncementView,
  GroupView,
  InboxItem,
  InvitationView,
  MessageView,
  MeContext,
  MeProfile,
  OperationState,
  OperationView,
  ParticipantView,
  RoleAssignmentView,
  TaskState,
  TaskView,
  UserRef,
  UserSummary,
  Uuid,
  MapOperationView,
} from '../types';
import { fail, mockStore, MOCK_IDS, nextId, nowIso, respond, type MockMessage, type MockPersona, type MockPublicAnnouncement } from './store';

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
  'alert.view',
  'alert.cancel',
  'alert.close',
  'alert.resend',
  'location.view',
  'announcement.send.group',
];

/** ADR-015/018/020: ADM'nin her operasyondaki izinleri = KOR setinin tamamı (backend `EffectivePermissions.ADM_OPERATION`). */
const ADM_OPERATIONAL_PERMS = [...OPERATIONAL_PERMS];

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
  if (mockStore.persona === 'ADM') return ADM_OPERATIONAL_PERMS.includes(permission);   // ADR-015
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
        permissions.organization = ['operation.create', 'operation.manage', 'invitation.create', 'role.assign', 'account.disable', 'session.revoke', 'user.status.view', 'audit.view', 'mfa.reset', 'password.reset', 'announcement.send.organization', 'public_announcement.manage'];
        for (const op of mockStore.operations.values()) permissions.operations[op.id] = [...ADM_OPERATIONAL_PERMS];   // ADR-015
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
        ...(request.area ? { area: request.area } : {}),
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
      if (request.area !== undefined) {
        if (request.area === null) delete op.area;
        else if (Math.abs(request.area.lat) > 90 || Math.abs(request.area.lon) > 180) {
          return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'area.lat', code: 'OUT_OF_RANGE' }]);
        } else op.area = request.area;
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

// ------------------------------------------------------------- role assignments (AC-01 – AC-03)

function allMockAssignments(): RoleAssignmentView[] {
  const ref = (userId: Uuid): UserRef => ({ id: userId, displayName: mockStore.users.get(userId)?.displayName ?? userId });
  const grantedBy = { id: MOCK_IDS.adm, displayName: 'Yönetici (örnek)' };
  const items: RoleAssignmentView[] = mockStore.orgRoles.map((r) => ({
    id: `rol-${r.role}-${r.userId}`,
    user: ref(r.userId),
    role: r.role,
    scopeType: 'ORGANIZATION',
    scopeId: MOCK_IDS.org,
    scopeName: 'Örnek Kurum',
    grantedBy,
    grantedAt: '2026-01-01T00:00:00Z',
    ...(r.revokedAt ? { revokedAt: r.revokedAt } : {}),
  }));
  for (const op of mockStore.operations.values()) {
    for (const c of op.coordinators) {
      items.push({ id: `rol-KOR-${op.id}-${c.id}`, user: ref(c.id!), role: 'KOR', scopeType: 'OPERATION', scopeId: op.id,
        scopeName: op.name, grantedBy, grantedAt: op.createdAt ?? '2026-01-01T00:00:00Z' });
    }
    for (const p of mockStore.participants.get(op.id) ?? []) {
      items.push({ id: `rol-GON-${op.id}-${p.userId}`, user: ref(p.userId), role: 'GON', scopeType: 'OPERATION', scopeId: op.id,
        scopeName: op.name, grantedBy, grantedAt: p.joinedAt });
    }
  }
  return items;
}

/** AC-01 – AC-03 taklidi: yalnızca ADM; kendi ADM'sini ve son ADM'yi kaldırma 403 (S-09); kapanmış operasyon 409. */
export function mockRoleAssignmentService(): RoleAssignmentService {
  const requireAdm = () => (mockStore.persona === 'ADM' ? undefined : fail(403, 'FORBIDDEN'));
  return {
    list: async (filter = {}) => {
      await requireAdm();
      const items = allMockAssignments().filter(
        (a) =>
          (!filter.userId || a.user.id === filter.userId) &&
          (!filter.scopeId || a.scopeId === filter.scopeId) &&
          (!filter.role || a.role === filter.role) &&
          (filter.active === undefined || filter.active === !a.revokedAt),
      );
      return respond({ items });
    },
    grant: async (request) => {
      await requireAdm();
      const user = mockStore.users.get(request.userId);
      const ref = { id: request.userId, displayName: user?.displayName ?? request.userId };
      if (request.scopeType === 'ORGANIZATION') {
        if (!['ADM', 'TEK', 'UYM'].includes(request.role)) {
          return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'role', code: 'NOT_ALLOWED_VALUE' }]);
        }
        if (mockStore.orgRoles.some((r) => r.userId === request.userId && r.role === request.role && !r.revokedAt)) {
          return fail(409, 'ALREADY_EXISTS', { conflict: 'ROLE_ACTIVE' });
        }
        mockStore.orgRoles.push({ userId: request.userId, role: request.role as 'ADM' | 'TEK' | 'UYM' });
      } else {
        const op = mockStore.operations.get(request.scopeId);
        if (!op) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'scopeId', code: 'NOT_ALLOWED_VALUE' }]);
        if (isClosed(op.state)) return fail(409, 'OPERATION_CLOSED');
        if (request.role !== 'KOR') {
          return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'role', code: 'NOT_ALLOWED_VALUE' }]);
        }
        if (op.coordinators.some((c) => c.id === request.userId)) return fail(409, 'ALREADY_EXISTS', { conflict: 'ROLE_ACTIVE' });
        op.coordinators = [...op.coordinators, ref];
      }
      return respond(allMockAssignments().find((a) => a.user.id === request.userId && a.role === request.role && a.scopeId === request.scopeId
        && !a.revokedAt)!);
    },
    revoke: async (id) => {
      await requireAdm();
      const found = allMockAssignments().find((a) => a.id === id);
      if (!found) return fail(404, 'NOT_FOUND');
      if (found.revokedAt) return fail(409, 'INVALID_STATE_TRANSITION');
      if (found.role === 'ADM') {
        const activeAdmins = mockStore.orgRoles.filter((r) => r.role === 'ADM' && !r.revokedAt).length;
        if (found.user.id === PERSONA_USER.ADM || activeAdmins <= 1) return fail(403, 'FORBIDDEN');
      }
      if (found.scopeType === 'ORGANIZATION') {
        const row = mockStore.orgRoles.find((r) => r.userId === found.user.id && r.role === found.role && !r.revokedAt)!;
        row.revokedAt = nowIso();
      } else if (found.role === 'KOR') {
        const op = mockStore.operations.get(found.scopeId)!;
        op.coordinators = op.coordinators.filter((c) => c.id !== found.user.id);
      } else {
        const list = mockStore.participants.get(found.scopeId) ?? [];
        mockStore.participants.set(found.scopeId, list.filter((p) => p.userId !== found.user.id));
      }
      return respond(undefined);
    },
  };
}

// ------------------------------------------------------------- users (ID-19 – ID-25)

/** TEK izdüşümü: yalnızca ad ve durum (AC-06.5). */
function statusOnly(u: UserSummary): UserSummary {
  return { id: u.id, displayName: u.displayName, status: u.status };
}

/** ID-19 – ID-25 taklidi: ADM tam, TEK ad+durum, KOR yalnızca kendi operasyonunun katılımcıları; kendi hesabı 403. */
export function mockUserAdminService(): UserAdminService {
  const persona = () => mockStore.persona;
  const me = () => PERSONA_USER[persona()];
  const directory = () => persona() === 'ADM' || persona() === 'TEK';
  const project = (u: UserSummary) => (persona() === 'TEK' ? statusOnly(u) : { ...u });
  const target = async (id: Uuid) => mockStore.users.get(id) ?? fail(404, 'NOT_FOUND');
  return {
    list: async (filter = {}) => {
      let users = [...mockStore.users.values()];
      if (filter.operationId) {
        if (!directory() && !(persona() === 'KOR' && isCoordinatorOf(filter.operationId))) return fail(403, 'FORBIDDEN');
        const ids = new Set((mockStore.participants.get(filter.operationId) ?? []).map((p) => p.userId));
        users = users.filter((u) => ids.has(u.id));
      } else if (!directory()) {
        return fail(403, 'FORBIDDEN');
      }
      if (filter.status) users = users.filter((u) => u.status === filter.status);
      users.sort((a, b) => a.id.localeCompare(b.id));
      return respond({ items: users.map(project), ...(filter.includeTotal ? { totalCount: users.length } : {}) });
    },
    get: async (id) => {
      const u = await target(id);
      if (!directory()) return fail(403, 'FORBIDDEN');
      if (persona() === 'TEK') return respond(statusOnly(u));
      const roles = (await mockRoleAssignmentService().list({ userId: id, active: true })).items;
      return respond({ ...u, roles, groups: [], mfaEnrolled: mockStore.mfaEnrolled.has(id), createdAt: '2026-01-01T00:00:00Z' });
    },
    disable: async (id, reason) => {
      const u = await target(id);
      if (!directory()) return fail(403, 'FORBIDDEN');
      if (persona() === 'TEK' && mockStore.orgRoles.some((r) => r.userId === id && r.role === 'ADM' && !r.revokedAt)) {
        return fail(403, 'FORBIDDEN');
      }
      if (id === me()) return fail(403, 'FORBIDDEN');
      if ((reason?.trim().length ?? 0) > 300) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'reason', code: 'TOO_LONG' }]);
      if (u.status !== 'ACTIVE') return fail(409, 'INVALID_STATE_TRANSITION', { currentState: u.status, requestedState: 'DISABLED' });
      u.status = 'DISABLED';
      return respond(project(u));
    },
    enable: async (id) => {
      const u = await target(id);
      if (persona() !== 'ADM') return fail(403, 'FORBIDDEN');
      if (u.status !== 'DISABLED') return fail(409, 'INVALID_STATE_TRANSITION', { currentState: u.status, requestedState: 'ACTIVE' });
      u.status = 'ACTIVE';
      return respond(project(u));
    },
    revokeSessions: async (id) => {
      await target(id);
      if (!directory()) return fail(403, 'FORBIDDEN');
      return respond(undefined);
    },
    resetPassword: async (id) => {
      await target(id);
      if (persona() !== 'ADM' || id === me()) return fail(403, 'FORBIDDEN');
      return respond(undefined);
    },
    resetMfa: async (id) => {
      await target(id);
      if (persona() !== 'ADM' || id === me()) return fail(403, 'FORBIDDEN');
      if (!mockStore.mfaEnrolled.has(id)) return fail(409, 'INVALID_STATE_TRANSITION', { currentState: 'NOT_ENROLLED', requestedState: 'MFA_RESET' });
      mockStore.mfaEnrolled.delete(id);
      return respond(undefined);
    },
  };
}

// ------------------------------------------------------------- audit (AU-01)

/** Örnek denetim kayıtları; ADM ve UYM görür (K-26). Gerçek kayıt değildir. */
export function mockAuditService(): AuditService {
  const items: AuditEntryView[] = [
    { id: '0192f000-a000-7000-8000-000000000003', actor: { id: MOCK_IDS.adm, displayName: 'Yönetici (örnek)' }, actorBasis: 'ADM',
      action: 'TASK_CREATED', resourceType: 'TASK', resourceId: MOCK_IDS.task1, operationId: MOCK_IDS.op1,
      operationName: 'Deprem Arama Kurtarma — Merkez', client: 'WEB', at: '2026-11-15T09:10:00Z', details: { priority: 'HIGH' } },
    { id: '0192f000-a000-7000-8000-000000000002', actor: { id: MOCK_IDS.kor, displayName: 'Koordinatör (örnek)' }, actorBasis: 'KOR',
      action: 'ANNOUNCEMENT_SENT', resourceType: 'NOTIFICATION', operationId: MOCK_IDS.op1,
      operationName: 'Deprem Arama Kurtarma — Merkez', client: 'ANDROID', at: '2026-11-15T09:00:00Z', details: { recipientCount: 6 } },
    { id: '0192f000-a000-7000-8000-000000000001', actor: { id: MOCK_IDS.adm, displayName: 'Yönetici (örnek)' }, actorBasis: 'ADM',
      action: 'ROLE_ASSIGNED', resourceType: 'ROLE_ASSIGNMENT', operationId: MOCK_IDS.op2, operationName: 'Sel Bölgesi Lojistik',
      client: 'WEB', at: '2026-11-15T08:30:00Z', details: { role: 'KOR' } },
  ];
  return {
    list: async (filter = {}) => {
      if (mockStore.persona !== 'ADM' && mockStore.persona !== 'UYM') return fail(403, 'FORBIDDEN');
      return respond({
        items: items.filter(
          (e) =>
            (!filter.action || e.action === filter.action) &&
            (!filter.actorBasis || e.actorBasis === filter.actorBasis) &&
            (!filter.operationId || e.operationId === filter.operationId),
        ),
      });
    },
  };
}

/** Örnek veri modunda akış yoktur; testler ve tanıtım olayları `emitMockLiveEvent` ile verir. */
const liveListeners = new Set<{ topics: string[]; onEvent: (event: LiveEvent) => void }>();

export function emitMockLiveEvent(event: LiveEvent): void {
  const topic = typeof event.data.topic === 'string' ? event.data.topic : undefined;
  for (const listener of [...liveListeners]) {
    if (!topic || topic.startsWith('user:') || listener.topics.includes(topic)) listener.onEvent(event);
  }
}

/** Testler için: açık sahte akışların konuları. */
export function mockLiveTopics(): string[][] {
  return [...liveListeners].map((l) => l.topics);
}

export function mockPublicAnnouncementService(): PublicAnnouncementService {
  return {
    listPublished: async (page = {}) => {
      const limit = Math.min(page.limit ?? 20, 50);
      const all = mockStore.publicAnnouncements
        .filter((a) => a.state === 'PUBLISHED')
        .sort((a, b) => (b.publishedAt ?? '').localeCompare(a.publishedAt ?? ''));
      const start = page.cursor ? Number(page.cursor) : 0;
      const items = all.slice(start, start + limit).map(({ id, title, body, publishedAt }) => ({ id, title, body, publishedAt: publishedAt! }));
      return respond({ items, nextCursor: start + limit < all.length ? String(start + limit) : undefined });
    },
    list: async () => {
      if (!canManagePublic()) return fail(403, 'FORBIDDEN');
      const items = [...mockStore.publicAnnouncements].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map((a) => ({ ...a }));
      return respond({ items });
    },
    create: async ({ title, body }) => {
      if (!canManagePublic()) return fail(403, 'FORBIDDEN');
      const errors = publicContentErrors(title, body);
      if (errors.length) return fail(400, 'VALIDATION_FAILED', undefined, errors);
      const now = nowIso();
      const created = { id: nextId('7a00'), title: title.trim(), body: body.trim(), state: 'DRAFT' as const, version: 1, createdAt: now, updatedAt: now };
      mockStore.publicAnnouncements.push(created);
      return respond({ ...created });
    },
    update: async (id, { title, body, version }) => {
      const a = { row: await lockPublic(id, version) };
      const errors = publicContentErrors(title, body);
      if (errors.length) return fail(400, 'VALIDATION_FAILED', undefined, errors);
      Object.assign(a.row, { title: title.trim(), body: body.trim(), version: a.row.version + 1, updatedAt: nowIso() });
      return respond({ ...a.row });
    },
    publish: async (id, version) => {
      const a = { row: await lockPublic(id, version) };
      if (a.row.state === 'PUBLISHED') return fail(409, 'INVALID_STATE_TRANSITION');
      const now = nowIso();
      Object.assign(a.row, { state: 'PUBLISHED', publishedAt: now, version: a.row.version + 1, updatedAt: now });
      return respond({ ...a.row });
    },
    unpublish: async (id, version) => {
      const a = { row: await lockPublic(id, version) };
      if (a.row.state === 'DRAFT') return fail(409, 'INVALID_STATE_TRANSITION');
      Object.assign(a.row, { state: 'DRAFT', publishedAt: undefined, version: a.row.version + 1, updatedAt: nowIso() });
      return respond({ ...a.row });
    },
  };
}

function canManagePublic(): boolean {
  return mockStore.orgRoles.some((r) => r.userId === PERSONA_USER[mockStore.persona] && r.role === 'ADM' && !r.revokedAt);
}

function publicContentErrors(title: string, body: string) {
  const errors: { field: string; code: string; message: string }[] = [];
  if (title.trim().length < 3) errors.push({ field: 'title', code: 'TOO_SHORT', message: 'Başlık en az 3 karakter olmalıdır' });
  if (title.trim().length > 160) errors.push({ field: 'title', code: 'TOO_LONG', message: 'Başlık en fazla 160 karakter olabilir' });
  if (!body.trim()) errors.push({ field: 'body', code: 'REQUIRED', message: 'Metin zorunludur' });
  if (body.trim().length > 5000) errors.push({ field: 'body', code: 'TOO_LONG', message: 'Metin en fazla 5000 karakter olabilir' });
  return errors;
}

async function lockPublic(id: string, version: number): Promise<MockPublicAnnouncement> {
  if (!canManagePublic()) return fail(403, 'FORBIDDEN');
  const row = mockStore.publicAnnouncements.find((a) => a.id === id);
  if (!row) return fail(404, 'NOT_FOUND');
  if (row.version !== version) return fail(409, 'VERSION_CONFLICT', { currentVersion: row.version });
  return row;
}

export function mockMapService(): MapService {
  const service: MapService = {
    operation: async (operationId) => {
      const op = mockStore.operations.get(operationId);
      if (!op) return fail(404, 'NOT_FOUND');
      if (mockStore.persona !== 'ADM' && !isCoordinatorOf(operationId)) return fail(403, 'FORBIDDEN');
      const all = await service.operations([op.state]);
      return all.find((o) => o.id === operationId)!;
    },
    operations: async (states = []) => {
      const wanted = states.length ? states : ['ACTIVE'];
      if (mockStore.persona !== 'ADM' && mockStore.persona !== 'KOR') return fail(403, 'FORBIDDEN');
      const items: MapOperationView[] = [...mockStore.operations.values()]
        .filter((op) => wanted.includes(op.state) && (mockStore.persona === 'ADM' || isCoordinatorOf(op.id)))
        .map((op) => {
          const alert = [...mockStore.alerts.values()].find((a) => a.view.operationId === op.id && a.view.state === 'OPEN');
          return {
            id: op.id, name: op.name, state: op.state, area: op.area,
            participantCount: mockStore.participants.get(op.id)?.length ?? 0,
            meetingPoints: [...mockStore.groups.values()]
              .filter((g) => g.operationId === op.id && !g.archived && g.meetingPoint?.lat !== undefined && g.meetingPoint.lon !== undefined)
              .map((g) => ({ groupId: g.id, groupName: g.name, lat: g.meetingPoint!.lat!, lon: g.meetingPoint!.lon!, address: g.meetingPoint!.address })),
            ...(alert ? { openAlert: { id: alert.view.id, sentAt: alert.view.sentAt ?? nowIso(), counts: alert.view.counts } } : {}),
          };
        });
      return respond(items);
    },
  };
  return service;
}

export function mockMessagingService(): MessagingService {
  const visible = () =>
    mockStore.channels.filter((ch) => mockStore.persona === 'ADM' || isCoordinatorOf(ch.operationId));
  const find = (channelId: string) => visible().find((ch) => ch.id === channelId);
  const view = (m: MockMessage): MessageView => {
    const state = m.hiddenAt ? 'HIDDEN' : m.deletedAt ? 'DELETED' : 'VISIBLE';
    const mine = m.senderId === PERSONA_USER[mockStore.persona];
    const sender = m.senderId ? mockStore.users.get(m.senderId) : undefined;
    return {
      id: m.id, channelId: m.channelId, kind: m.kind, state, createdAt: m.createdAt, mine,
      sender: sender ? { id: sender.id, displayName: sender.displayName } : undefined,
      body: state === 'VISIBLE' ? m.body : undefined,
      deletable: mine && state === 'VISIBLE' && Date.now() - Date.parse(m.createdAt) < 5 * 60_000,
    };
  };
  return {
    channels: async () =>
      respond(
        visible().map((ch) => {
          const op = mockStore.operations.get(ch.operationId);
          const msgs = mockStore.messages.filter((m) => m.channelId === ch.id);
          const unread = msgs.filter((m) => !m.deletedAt && !m.hiddenAt && m.senderId !== PERSONA_USER[mockStore.persona]
            && (!ch.lastReadId || m.id > ch.lastReadId)).length;
          return {
            id: ch.id, kind: ch.kind, operationId: ch.operationId, operationName: op?.name ?? '', groupId: ch.groupId,
            groupName: ch.groupId ? mockStore.groups.get(ch.groupId)?.name : undefined, adminsOnly: ch.adminsOnly,
            readOnly: op ? isClosed(op.state) : true, canWrite: !!op && !isClosed(op.state),
            canModerate: true, muted: ch.muted, unread, lastMessageAt: msgs.at(-1)?.createdAt,
          };
        }),
      ),
    messages: async (channelId) => {
      if (!find(channelId)) return fail(404, 'NOT_FOUND');
      const items = mockStore.messages.filter((m) => m.channelId === channelId).sort((a, b) => b.id.localeCompare(a.id)).map(view);
      return respond({ items });
    },
    send: async (channelId, body, key) => {
      const ch = find(channelId);
      if (!ch) return fail(404, 'NOT_FOUND');
      const cached = mockStore.idempotency.get(`msg:${key}`) as MockMessage | undefined;
      if (cached) return respond(view(cached));
      if (!body.trim()) return fail(400, 'VALIDATION_FAILED', undefined, [{ field: 'body', code: 'REQUIRED' }]);
      const m: MockMessage = { id: nextId('7c00'), channelId, kind: 'USER', senderId: PERSONA_USER[mockStore.persona], body: body.trim(), createdAt: nowIso() };
      mockStore.messages.push(m);
      mockStore.idempotency.set(`msg:${key}`, m);
      ch.lastReadId = m.id;
      return respond(view(m));
    },
    remove: async (channelId, messageId) => {
      const m = mockStore.messages.find((x) => x.id === messageId && x.channelId === channelId);
      if (!m || !find(channelId)) return fail(404, 'NOT_FOUND');
      if (m.senderId !== PERSONA_USER[mockStore.persona]) return fail(403, 'FORBIDDEN');
      m.deletedAt = nowIso();
      return respond(view(m));
    },
    hide: async (channelId, messageId) => {
      const m = mockStore.messages.find((x) => x.id === messageId && x.channelId === channelId);
      if (!m || !find(channelId)) return fail(404, 'NOT_FOUND');
      m.hiddenAt = nowIso();
      return respond(view(m));
    },
    markRead: async (channelId, messageId) => {
      const ch = find(channelId);
      if (!ch) return fail(404, 'NOT_FOUND');
      if (!ch.lastReadId || messageId > ch.lastReadId) ch.lastReadId = messageId;
      return respond(undefined);
    },
    mute: async (channelId, muted) => {
      const ch = find(channelId);
      if (!ch) return fail(404, 'NOT_FOUND');
      ch.muted = muted;
      return respond(undefined);
    },
    setAdminsOnly: async (channelId, adminsOnly) => {
      const ch = find(channelId);
      if (!ch) return fail(404, 'NOT_FOUND');
      ch.adminsOnly = adminsOnly;
      return respond(undefined);
    },
  };
}

export function mockRealtimeService(): RealtimeService {
  return {
    connect: (topics, onEvent) => {
      const listener = { topics, onEvent };
      liveListeners.add(listener);
      return () => {
        liveListeners.delete(listener);
      };
    },
  };
}

// ------------------------------------------------------------- alerts (AL-01 – AL-16)

/** Örnek acil alarm yönetimi (KOR ve ADM, ADR-018). İletim yoktur; alıcı satırları "Gönderildi" doğar. */
export function mockAlertService(): AlertService {
  const allowed = () => mockStore.persona === 'KOR' || mockStore.persona === 'ADM';
  const me = () => (mockStore.persona === 'ADM' ? MOCK_IDS.adm : MOCK_IDS.kor);
  const counts = (rows: { state: string; responseOption: string | null }[]) => ({
    total: rows.length,
    responded: rows.filter((r) => r.state === 'RESPONDED').length,
    pending: rows.filter((r) => ['QUEUED', 'SENT', 'DELIVERED', 'SEEN'].includes(r.state)).length,
    unanswered: rows.filter((r) => r.state === 'UNANSWERED').length,
    failed: rows.filter((r) => r.state === 'FAILED').length,
    needHelp: rows.filter((r) => r.responseOption === 'NEED_HELP').length,
  });
  const audience = (operationId: string, targets: { userIds: string[]; groupIds: string[] }) => {
    const ids = new Set(targets.userIds);
    for (const groupId of targets.groupIds) {
      const group = mockStore.groups.get(groupId);
      if (group && group.operationId === operationId) group.members.forEach((m) => ids.add(m.user.id));
    }
    ids.delete(me());
    return [...ids];
  };
  const name = (userId: string) => mockStore.users.get(userId)?.displayName ?? 'Gönüllü';
  type GeneralTargetsInput = { organization: boolean; operationIds: string[]; groupIds: string[]; userIds: string[] };
  const general = new Map<string, GeneralTargetsInput>();
  const generalAudience = (t: GeneralTargetsInput) => {
    const ids = new Set<string>();
    if (t.organization) mockStore.users.forEach((u) => ids.add(u.id));
    for (const opId of t.operationIds) {
      (mockStore.participants.get(opId) ?? []).forEach((p) => ids.add(p.userId));
      mockStore.operations.get(opId)?.coordinators.forEach((c) => ids.add(c.id));
    }
    for (const groupId of t.groupIds) mockStore.groups.get(groupId)?.members.forEach((m) => ids.add(m.user.id));
    t.userIds.forEach((u) => ids.add(u));
    ids.delete(me());
    return [...ids];
  };
  const previewOf = (a: { view: AlertView; targets: { userIds: string[]; groupIds: string[] } }) => {
    const g = general.get(a.view.id);
    if (g) {
      const ids = generalAudience(g);
      return {
        alertId: a.view.id, version: a.view.version, audienceHash: [...ids].sort().join(','), recipientCount: ids.length,
        recipients: ids.map((id) => ({ id, displayName: name(id), groups: [] })), message: a.view.message,
        requestedAction: a.view.requestedAction, priority: a.view.priority, scope: 'GENERAL' as const,
        eventLocation: a.view.eventLocation, targets: a.view.targets,
      };
    }
    const ids = audience(a.view.operationId ?? '', a.targets);
    return {
      alertId: a.view.id, operationId: a.view.operationId, operationName: a.view.operationName ?? '', version: a.view.version,
      audienceHash: [...ids].sort().join(','), recipientCount: ids.length,
      recipients: ids.map((id) => ({ id, displayName: name(id), groups: [] })), message: a.view.message,
      requestedAction: a.view.requestedAction, priority: a.view.priority, correctsAlertId: a.view.correctsAlertId,
    };
  };
  const find = (id: string) => mockStore.alerts.get(id);
  const refresh = (a: { view: AlertView; recipients: AlertRecipientView[] }) => {
    a.view.counts = counts(a.recipients);
    return a.view;
  };
  return {
    list: async (filter = {}) => {
      if (!allowed()) return fail(403, 'FORBIDDEN');
      const states = filter.states ?? (filter.operationId ? [] : ['OPEN']);
      const items = [...mockStore.alerts.values()]
        .filter((a) => (!filter.operationId || a.view.operationId === filter.operationId) && (states.length === 0 || states.includes(a.view.state)))
        .sort((x, y) => (x.view.id < y.view.id ? 1 : -1))
        .map((a) => ({ id: a.view.id, operationId: a.view.operationId, operationName: a.view.operationName, state: a.view.state,
          message: a.view.message, priority: a.view.priority, createdAt: a.view.createdAt, sentAt: a.view.sentAt, counts: a.view.counts }));
      return respond({ items });
    },
    get: async (id) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      return respond(refresh(a));
    },
    create: async (operationId, request) => {
      if (!allowed()) return fail(403, 'FORBIDDEN');
      const operation = mockStore.operations.get(operationId);
      if (!operation) return fail(404, 'NOT_FOUND');
      if (operation.state !== 'ACTIVE') return fail(409, 'OPERATION_NOT_ACTIVE');
      const targets = { userIds: request.targets.userIds, groupIds: request.targets.groupIds };
      if (audience(operationId, targets).length === 0) {
        return fail(422, 'VALIDATION_FAILED', {}, [{ field: 'targets', code: 'EMPTY_AUDIENCE' }]);
      }
      const id = nextId('7000');
      const view: AlertView = {
        id, operationId, operationName: operation.name, state: 'DRAFT', version: 1, message: request.message,
        requestedAction: request.requestedAction, priority: request.priority ?? 'CRITICAL',
        responseOptions: ['RECEIVED', 'NEED_HELP', 'ON_MY_WAY'], createdBy: { id: me(), displayName: name(me()) },
        createdAt: nowIso(), counts: counts([]), deliveryDelayed: false, correctsAlertId: request.correctsAlertId,
      };
      const created = { view, targets, recipients: [] as AlertRecipientView[] };
      mockStore.alerts.set(id, created);
      if (request.correctsAlertId) {
        const old = find(request.correctsAlertId);
        if (old) old.view.correctedByAlertId = id;
      }
      return respond(previewOf(created));
    },
    createGeneral: async (request) => {
      if (!allowed()) return fail(403, 'FORBIDDEN');
      const t = request.targets;
      if (t.organization && mockStore.persona !== 'ADM') return fail(403, 'FORBIDDEN');
      if (mockStore.persona === 'KOR' && t.operationIds.some((op) => !isCoordinatorOf(op))) return fail(403, 'FORBIDDEN');
      if (!t.organization && !t.operationIds.length && !t.groupIds.length && !t.userIds.length) {
        return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'targets', code: 'REQUIRED' }]);
      }
      if (generalAudience(t).length === 0) return fail(422, 'VALIDATION_FAILED', {}, [{ field: 'targets', code: 'EMPTY_AUDIENCE' }]);
      const id = nextId('7000');
      const view: AlertView = {
        id, scope: 'GENERAL', state: 'DRAFT', version: 1, message: request.message, requestedAction: request.requestedAction,
        priority: 'CRITICAL', responseOptions: ['RECEIVED', 'NEED_HELP', 'ON_MY_WAY'], createdBy: { id: me(), displayName: name(me()) },
        createdAt: nowIso(), counts: counts([]), deliveryDelayed: false, eventLocation: request.eventLocation,
        targets: {
          organization: t.organization, groupCount: t.groupIds.length, userCount: t.userIds.length,
          operations: t.operationIds.map((op) => ({ id: op, displayName: mockStore.operations.get(op)?.name })),
        },
      };
      const created = { view, targets: { userIds: t.userIds, groupIds: t.groupIds }, recipients: [] as AlertRecipientView[] };
      mockStore.alerts.set(id, created);
      general.set(id, t);
      return respond(previewOf(created));
    },
    preview: async (id) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      return respond(previewOf(a));
    },
    send: async (id, version, audienceHash, _key, confirmed) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      if (general.has(id) && confirmed !== true) return fail(400, 'VALIDATION_FAILED', {}, [{ field: 'confirmed', code: 'REQUIRED' }]);
      if (a.view.state !== 'DRAFT') return fail(409, 'INVALID_STATE_TRANSITION', { currentState: a.view.state });
      if (a.view.version !== version) return fail(409, 'VERSION_CONFLICT', { currentVersion: a.view.version });
      const preview = previewOf(a);
      if (preview.audienceHash !== audienceHash) return fail(409, 'AUDIENCE_CHANGED', { preview });
      const at = nowIso();
      a.recipients = preview.recipients.map((r) => ({
        id: nextId('7100'), user: { id: r.id, displayName: r.displayName }, group: null, state: 'SENT', reminderCount: 0,
        responseOption: null, sentAt: at, deliveredAt: null, seenAt: null, respondedAt: null, respondedAtDevice: null, failureReason: null,
      }));
      Object.assign(a.view, { state: 'OPEN', sentAt: at, sentBy: { id: me(), displayName: name(me()) }, recipientCount: a.recipients.length });
      return respond(refresh(a));
    },
    deleteDraft: async (id) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      if (a.view.state !== 'DRAFT') return fail(409, 'INVALID_STATE_TRANSITION');
      mockStore.alerts.delete(id);
      return respond(undefined);
    },
    cancel: async (id) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      if (a.view.state !== 'OPEN') return fail(409, 'ALERT_NOT_OPEN', { alertState: a.view.state });
      Object.assign(a.view, { state: 'CANCELLED', cancelledAt: nowIso(), cancelledBy: { id: me(), displayName: name(me()) } });
      return respond(refresh(a));
    },
    close: async (id) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      if (a.view.state !== 'OPEN') return fail(409, 'ALERT_NOT_OPEN', { alertState: a.view.state });
      Object.assign(a.view, { state: 'CLOSED', closedAt: nowIso(), closeReason: 'MANUAL', closedBy: { id: me(), displayName: name(me()) } });
      return respond(refresh(a));
    },
    resend: async (id, recipientIds) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      if (a.view.state !== 'OPEN') return fail(409, 'ALERT_NOT_OPEN', { alertState: a.view.state });
      let requeued = 0;
      for (const r of a.recipients) {
        if ((r.state === 'UNANSWERED' || r.state === 'FAILED') && (recipientIds.length === 0 || recipientIds.includes(r.id))) {
          r.state = 'SENT';
          r.reminderCount = 0;
          r.failureReason = null;
          requeued += 1;
        }
      }
      return respond({ alert: refresh(a), requeuedCount: requeued, skippedCount: Math.max(0, recipientIds.length - requeued) });
    },
    pushStatus: async () => 'DISABLED',
    recipients: async (id) => {
      const a = find(id);
      if (!a) return fail(404, 'NOT_FOUND');
      const order = ['QUEUED', 'SENT', 'DELIVERED', 'SEEN', 'RESPONDED', 'UNANSWERED', 'FAILED'];
      const items = [...a.recipients].sort(
        (x, y) =>
          Number(y.responseOption === 'NEED_HELP') - Number(x.responseOption === 'NEED_HELP') ||
          order.indexOf(x.state) - order.indexOf(y.state) ||
          x.user.displayName.localeCompare(y.user.displayName, 'tr'),
      );
      return respond({ items });
    },
  };
}

// ------------------------------------------------------------- locations (LC-05)

/** Örnek son konumlar (KOR ve ADM, ADR-020). Gerçek konum değildir. */
export function mockLocationService(): LocationService {
  return {
    list: async (operationId, filter = {}) => {
      if (mockStore.persona !== 'KOR' && mockStore.persona !== 'ADM') return fail(403, 'FORBIDDEN');
      const now = Date.now();
      const at = (minutes: number) => new Date(now - minutes * 60_000).toISOString();
      const name = (n: number) => mockStore.users.get(MOCK_IDS.volunteer(n))?.displayName ?? `Gönüllü ${n}`;
      const items = [
        { n: 1, minutes: 0.5, freshness: 'LIVE' as const },
        { n: 2, minutes: 6, freshness: 'STALE' as const },
        { n: 3, minutes: 40, freshness: 'LAST_KNOWN' as const },
      ]
        .filter((x) => !filter.freshness || filter.freshness === x.freshness)
        .map((x) => ({
          user: { id: MOCK_IDS.volunteer(x.n), displayName: name(x.n) },
          sharingState: 'ACTIVE' as const,
          lat: 41.0 + x.n / 100,
          lon: 28.97 + x.n / 100,
          accuracy: 10 * x.n,
          measuredAt: at(x.minutes),
          receivedAt: at(x.minutes),
          freshness: x.freshness,
          clockSkew: x.n === 2,
        }));
      return respond({
        operationId,
        generatedAt: new Date(now).toISOString(),
        items,
        notSharing: [{ user: { id: MOCK_IDS.volunteer(4), displayName: name(4) }, state: 'STOPPED' as const }],
      });
    },
  };
}
