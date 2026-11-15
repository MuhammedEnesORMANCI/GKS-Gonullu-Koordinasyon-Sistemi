import type { AssignmentFilter, InvitationFilter, OperationListFilter, TaskListFilter } from '../services/contracts';

/** Sorgu anahtarları (web-screen-api-mapping.md §6.1); hiyerarşik, ön ekle toplu geçersiz kılma. */
export const Q = {
  meContext: () => ['me', 'context'] as const,
  profile: () => ['me', 'profile'] as const,
  operationsRoot: () => ['operations'] as const,
  operations: (f: OperationListFilter = {}) => ['operations', 'list', f] as const,
  operation: (id: string) => ['operations', id] as const,
  participants: (id: string, notInGroup?: string) =>
    notInGroup ? (['operations', id, 'participants', { notInGroup }] as const) : (['operations', id, 'participants'] as const),
  groups: (opId: string) => ['operations', opId, 'groups'] as const,
  group: (id: string) => ['groups', id] as const,
  tasks: (opId: string, f: TaskListFilter = {}) => ['operations', opId, 'tasks', f] as const,
  tasksRoot: (opId: string) => ['operations', opId, 'tasks'] as const,
  task: (id: string) => ['tasks', id] as const,
  taskAssignments: (id: string, f: AssignmentFilter = {}) => ['tasks', id, 'assignments', f] as const,
  invitationsRoot: () => ['invitations'] as const,
  inboxRoot: () => ['me', 'notifications'] as const,
  inbox: (unreadOnly: boolean) => ['me', 'notifications', { unreadOnly }] as const,
  unreadCount: () => ['me', 'notifications', 'unread-count'] as const,
  announcementsRoot: () => ['announcements'] as const,
  announcements: (operationId?: string) => ['announcements', { operationId }] as const,
  invitations: (f: InvitationFilter = {}) => ['invitations', f] as const,
};
