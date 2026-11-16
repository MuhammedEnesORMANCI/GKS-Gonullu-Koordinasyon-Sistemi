import { API_BASE, type ApiClient } from '../api/client';
import type {
  AccountService,
  AlertService,
  AnnouncementService,
  AuditService,
  AuthService,
  GroupService,
  InvitationService,
  MeContextService,
  NotificationService,
  OperationListService,
  OperationService,
  LocationService,
  ParticipantService,
  PublicAnnouncementService,
  PushStatus,
  RealtimeService,
  RoleAssignmentService,
  TaskService,
  UserAdminService,
} from './contracts';
import type {
  AlertPreview,
  AlertRecipientView,
  AlertResendResult,
  AlertSummary,
  AlertView,
  AnnouncementPreview,
  LocationListView,
  AnnouncementView,
  AuditEntryView,
  AssignmentView,
  GroupMemberView,
  GroupSummary,
  GroupView,
  InboxItem,
  InvitationView,
  LoginResponse,
  MeContext,
  MeProfile,
  MeetingPoint,
  MfaEnrollResponse,
  OperationView,
  Page,
  ParticipantView,
  PublicAnnouncement,
  PublicAnnouncementAdminView,
  RoleAssignmentView,
  TaskSummary,
  UserDetail,
  UserPage,
  UserSummary,
  TaskView,
} from './types';

/**
 * `http` uygulamaları: yollar, gövde alan adları ve başlıklar backend-api-design.md §5–§9 ile birebir.
 * Yol parametreleri her zaman `encodeURIComponent` ile kodlanır.
 */

const p = encodeURIComponent;

export function httpAuthService(api: ApiClient): AuthService {
  return {
    fetchCsrf: () => api.fetchCsrf(),
    login: (request) =>
      api.post<LoginResponse>(
        '/auth/login',
        { email: request.email, password: request.password, clientType: 'WEB' },
        { authFlow: true },
      ),
    enrollMfa: (mfaTicket) => api.post<MfaEnrollResponse>('/auth/mfa/enroll', { mfaTicket }, { authFlow: true }),
    verifyMfa: (mfaTicket, code) => api.post<LoginResponse>('/auth/mfa', { mfaTicket, code }, { authFlow: true }),
    changePassword: async (request) => {
      // 200 TokenResponse: yeni belirteçler yalnızca çerezde; gövde kullanılmaz.
      await api.post<unknown>('/me/password', { currentPassword: request.currentPassword, newPassword: request.newPassword });
    },
    logout: async () => {
      await api.post<void>('/auth/logout', undefined, { authFlow: true });
    },
  };
}

export function httpAccountService(api: ApiClient): AccountService {
  return {
    getProfile: () => api.get<MeProfile>('/me'),
    updateProfile: (update) => api.patch<MeProfile>('/me', update),
  };
}

export function httpNotificationService(api: ApiClient): NotificationService {
  return {
    list: (filter = {}) =>
      api.get<Page<InboxItem>>('/me/notifications', {
        query: { unreadOnly: filter.unreadOnly ? true : undefined, limit: filter.limit, cursor: filter.cursor },
      }),
    get: (id) => api.get<InboxItem>(`/me/notifications/${p(id)}`),
    markRead: async (id) => {
      await api.post<void>(`/me/notifications/${p(id)}/read`);
    },
    unreadCount: async () => (await api.get<{ unreadCount: number }>('/me/notifications/unread-count')).unreadCount,
  };
}

export function httpAnnouncementService(api: ApiClient): AnnouncementService {
  return {
    preview: (target) => api.post<AnnouncementPreview>('/announcements/preview', target),
    create: (request, idempotencyKey) => api.post<AnnouncementView>('/announcements', request, { idempotencyKey }),
    list: (filter = {}) =>
      api.get<Page<AnnouncementView>>('/announcements', {
        query: { operationId: filter.operationId, limit: filter.limit, cursor: filter.cursor },
      }),
    get: (id) => api.get<AnnouncementView>(`/announcements/${p(id)}`),
  };
}

export function httpRoleAssignmentService(api: ApiClient): RoleAssignmentService {
  return {
    list: (filter = {}) =>
      api.get<Page<RoleAssignmentView>>('/role-assignments', {
        query: {
          userId: filter.userId,
          scopeId: filter.scopeId,
          role: filter.role,
          active: filter.active === undefined ? undefined : filter.active,
          limit: filter.limit,
          cursor: filter.cursor,
        },
      }),
    grant: (request) =>
      api.post<RoleAssignmentView>('/role-assignments', {
        userId: request.userId,
        role: request.role,
        scopeType: request.scopeType,
        scopeId: request.scopeId,
      }),
    revoke: async (id) => {
      await api.delete<void>(`/role-assignments/${p(id)}`);
    },
  };
}

export function httpUserAdminService(api: ApiClient): UserAdminService {
  const action = (id: string, name: string, body?: unknown) => api.post<void>(`/users/${p(id)}/${name}`, body);
  return {
    list: (filter = {}) =>
      api.get<UserPage>('/users', {
        query: {
          status: filter.status,
          operationId: filter.operationId,
          includeTotal: filter.includeTotal ? true : undefined,
          limit: filter.limit,
          cursor: filter.cursor,
        },
      }),
    get: (id) => api.get<UserDetail>(`/users/${p(id)}`),
    disable: (id, reason) =>
      api.post<UserSummary>(`/users/${p(id)}/disable`, reason?.trim() ? { reason: reason.trim() } : undefined),
    enable: (id) => api.post<UserSummary>(`/users/${p(id)}/enable`),
    revokeSessions: async (id) => {
      await action(id, 'revoke-sessions');
    },
    resetPassword: async (id) => {
      await action(id, 'reset-password');
    },
    resetMfa: async (id) => {
      await action(id, 'reset-mfa');
    },
  };
}

export function httpAuditService(api: ApiClient): AuditService {
  return {
    list: (filter = {}) =>
      api.get<Page<AuditEntryView>>('/audit-entries', {
        query: {
          action: filter.action,
          actorBasis: filter.actorBasis,
          operationId: filter.operationId,
          limit: filter.limit,
          cursor: filter.cursor,
        },
      }),
  };
}

/** Sağlık ucu yanıtından push durumu; beklenmeyen biçimde `UNKNOWN`. */
export function pushStatusFromHealth(body: unknown): PushStatus {
  const status = (body as { components?: { push?: { status?: unknown } } } | null)?.components?.push?.status;
  return status === 'UP' || status === 'DISABLED' || status === 'DEGRADED' ? status : 'UNKNOWN';
}

export function httpAlertService(
  api: ApiClient,
  fetchFn: (input: string, init?: RequestInit) => Promise<Response> = (input, init) => fetch(input, init),
): AlertService {
  return {
    list: (filter = {}) =>
      api.get<Page<AlertSummary>>(filter.operationId ? `/operations/${p(filter.operationId)}/alerts` : '/alerts', {
        query: { state: filter.states },
      }),
    get: (id) => api.get<AlertView>(`/alerts/${p(id)}`),
    create: (operationId, request) => api.post<AlertPreview>(`/operations/${p(operationId)}/alerts`, request),
    preview: (id) => api.get<AlertPreview>(`/alerts/${p(id)}/preview`),
    send: (id, version, audienceHash, idempotencyKey) =>
      api.post<AlertView>(`/alerts/${p(id)}/send`, { version, audienceHash }, { idempotencyKey }),
    deleteDraft: (id) => api.delete(`/alerts/${p(id)}`),
    cancel: (id, reason, idempotencyKey) =>
      api.post<AlertView>(`/alerts/${p(id)}/cancel`, reason ? { reason } : {}, { idempotencyKey }),
    close: (id) => api.post<AlertView>(`/alerts/${p(id)}/close`),
    resend: (id, recipientIds, idempotencyKey) =>
      api.post<AlertResendResult>(`/alerts/${p(id)}/resend`, { recipientIds }, { idempotencyKey }),
    recipients: (id) => api.get<Page<AlertRecipientView>>(`/alerts/${p(id)}/recipients`),
    pushStatus: async () => {
      try {
        // Sağlık ucu genel durum UP iken 200, değilse 503 döner; ikisinde de gövde bileşenleri taşır.
        const response = await fetchFn('/actuator/health', { credentials: 'omit', headers: { Accept: 'application/json' } });
        return pushStatusFromHealth(await response.json());
      } catch {
        return 'UNKNOWN';
      }
    },
  };
}

export function httpLocationService(api: ApiClient): LocationService {
  return {
    list: (operationId, filter = {}) =>
      api.get<LocationListView>(`/operations/${p(operationId)}/locations`, {
        query: { groupId: filter.groupId, freshness: filter.freshness },
      }),
  };
}

export function httpMeContextService(api: ApiClient): MeContextService {
  return { getContext: () => api.get<MeContext>('/me/context') };
}

export function httpOperationService(api: ApiClient): OperationService {
  return {
    create: (request, idempotencyKey) =>
      api.post<OperationView>(
        '/operations',
        {
          name: request.name,
          ...(request.description ? { description: request.description } : {}),
          ...(request.plannedEndAt ? { plannedEndAt: request.plannedEndAt } : {}),
          coordinatorIds: request.coordinatorIds,
        },
        { idempotencyKey },
      ),
    get: (id) => api.get<OperationView>(`/operations/${p(id)}`),
    update: (id, request) => api.patch<OperationView>(`/operations/${p(id)}`, request),
    // OR-03 idempotency "d" (destekler) ama mapping §0.5/§6.3: anahtar yok, otomatik tekrar yok.
    changeState: (id, request) =>
      api.post<OperationView>(`/operations/${p(id)}/state`, { targetState: request.targetState }),
  };
}

export function httpOperationListService(api: ApiClient): OperationListService {
  return {
    list: (filter = {}) =>
      api.get<Page<OperationView>>('/operations', {
        query: { state: filter.state, limit: filter.limit, cursor: filter.cursor },
      }),
  };
}

export function httpParticipantService(api: ApiClient): ParticipantService {
  return {
    list: (operationId, filter = {}) =>
      api.get<Page<ParticipantView>>(`/operations/${p(operationId)}/participants`, {
        query: { notInGroup: filter.notInGroup, limit: filter.limit, cursor: filter.cursor },
      }),
  };
}

export function httpGroupService(api: ApiClient): GroupService {
  return {
    list: (operationId, filter = {}) =>
      api.get<Page<GroupSummary>>('/groups', {
        query: {
          operationId,
          includeArchived: filter.includeArchived ? true : undefined,
          limit: filter.limit,
          cursor: filter.cursor,
        },
      }),
    get: (groupId) => api.get<GroupView>(`/groups/${p(groupId)}`),
    create: (operationId, request, idempotencyKey) =>
      api.post<GroupView>(`/operations/${p(operationId)}/groups`, request, { idempotencyKey }),
    addMember: (groupId, userId, idempotencyKey) =>
      api.post<GroupMemberView>(`/groups/${p(groupId)}/members`, { userId }, { idempotencyKey }),
    removeMember: async (groupId, userId) => {
      await api.delete<void>(`/groups/${p(groupId)}/members/${p(userId)}`);
    },
    changeLeader: (groupId, userId, idempotencyKey) =>
      api.put<GroupView>(`/groups/${p(groupId)}/leader`, { userId }, { idempotencyKey }),
    update: (groupId, request) => api.patch<GroupView>(`/groups/${p(groupId)}`, request),
    setMeetingPoint: (groupId, request) => api.put<MeetingPoint>(`/groups/${p(groupId)}/meeting-point`, request),
  };
}

export function httpTaskService(api: ApiClient): TaskService {
  return {
    list: (operationId, filter = {}) =>
      api.get<Page<TaskSummary>>(`/operations/${p(operationId)}/tasks`, {
        query: { state: filter.state, limit: filter.limit, cursor: filter.cursor },
      }),
    get: (taskId) => api.get<TaskView>(`/tasks/${p(taskId)}`),
    assignments: (taskId, filter = {}) =>
      api.get<Page<AssignmentView>>(`/tasks/${p(taskId)}/assignments`, {
        query: { status: filter.status, overdue: filter.overdue, limit: filter.limit, cursor: filter.cursor },
      }),
    create: (operationId, request, idempotencyKey) =>
      api.post<TaskView>(`/operations/${p(operationId)}/tasks`, request, { idempotencyKey }),
    update: (taskId, request) => api.patch<TaskView>(`/tasks/${p(taskId)}`, request),
    publish: (taskId, version, idempotencyKey) =>
      api.post<TaskView>(`/tasks/${p(taskId)}/publish`, { version }, { idempotencyKey }),
    changeState: (taskId, request) =>
      api.post<TaskView>(`/tasks/${p(taskId)}/state`, { targetState: request.targetState }),
    addAssignment: (taskId, userId, idempotencyKey) =>
      api.post<AssignmentView>(`/tasks/${p(taskId)}/assignments`, { userId }, { idempotencyKey }),
    removeAssignment: async (taskId, assignmentId) => {
      await api.delete<void>(`/tasks/${p(taskId)}/assignments/${p(assignmentId)}`);
    },
  };
}

export function httpInvitationService(api: ApiClient): InvitationService {
  return {
    list: (filter = {}) =>
      api.get<Page<InvitationView>>('/invitations', {
        query: {
          scopeType: filter.scopeType,
          scopeId: filter.scopeId,
          status: filter.status,
          limit: filter.limit,
          cursor: filter.cursor,
        },
      }),
    create: (request, idempotencyKey) =>
      api.post<InvitationView>(
        '/invitations',
        { email: request.email, scopeType: request.scopeType, scopeId: request.scopeId },
        { idempotencyKey },
      ),
    revoke: (id) => api.post<InvitationView>(`/invitations/${p(id)}/revoke`),
  };
}

const LIVE_EVENTS = [
  'inbox.changed',
  'announcement.sent',
  'operation.state.changed',
  'alert.opened',
  'alert.recipient.changed',
  'alert.cancelled',
  'alert.closed',
  'location.updated',
  'location.sharing.changed',
  'subscription.revoked',
  'session.revoked',
  'resync',
] as const;

/**
 * RT-01: tarayıcının `EventSource`'u (çerezle kimlik, CSRF gerekmez). Geçici kopmada tarayıcı `Last-Event-ID` ile
 * kendisi yeniden bağlanır; sunucu bağlantıyı reddederse (401/403) akış kapanır ve `onClosed` çağrılır.
 */
export function httpPublicAnnouncementService(api: ApiClient): PublicAnnouncementService {
  return {
    listPublished: (page = {}) =>
      api.get<Page<PublicAnnouncement>>('/public/announcements', { query: { limit: page.limit, cursor: page.cursor } }),
    list: (page = {}) =>
      api.get<Page<PublicAnnouncementAdminView>>('/public-announcements', { query: { limit: page.limit, cursor: page.cursor } }),
    create: (content) => api.post<PublicAnnouncementAdminView>('/public-announcements', content),
    update: (id, content) => api.patch<PublicAnnouncementAdminView>(`/public-announcements/${p(id)}`, content),
    publish: (id, version) => api.post<PublicAnnouncementAdminView>(`/public-announcements/${p(id)}/publish`, { version }),
    unpublish: (id, version) => api.post<PublicAnnouncementAdminView>(`/public-announcements/${p(id)}/unpublish`, { version }),
  };
}

export function httpRealtimeService(baseUrl: string = API_BASE): RealtimeService {
  return {
    connect: (topics, onEvent, onClosed) => {
      if (typeof EventSource === 'undefined') return () => undefined;
      const query = topics.length > 0 ? `?topics=${topics.map(p).join(',')}` : '';
      const source = new EventSource(`${baseUrl}/stream${query}`, { withCredentials: true });
      let closed = false;
      for (const type of LIVE_EVENTS) {
        source.addEventListener(type, (message) => {
          const event = message as MessageEvent<string>;
          let data: Record<string, unknown> = {};
          try {
            data = JSON.parse(event.data) as Record<string, unknown>;
          } catch {
            // bozuk çerçeve: yalnızca tür bilgisiyle iletilir
          }
          onEvent({ type, id: event.lastEventId || undefined, data });
        });
      }
      source.onerror = () => {
        if (source.readyState === EventSource.CLOSED && !closed) {
          closed = true;
          onClosed();
        }
      };
      return () => {
        closed = true;
        source.close();
      };
    },
  };
}
