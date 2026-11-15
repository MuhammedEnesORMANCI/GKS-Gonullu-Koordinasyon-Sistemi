import { ApiError } from '../../api/problem';
import type {
  AssignmentView,
  GroupView,
  InvitationView,
  OperationView,
  Role,
  TaskView,
  UserSummary,
  Uuid,
} from '../types';

/**
 * Bellek içi örnek veri deposu (yalnızca `mock` servisleri). Gerçek kişi verisi içermez.
 * Tüm mock servisler aynı depoyu paylaşır; testler `resetMockStore()` ile sıfırlar.
 */

export type MockPersona = Extract<Role, 'ADM' | 'KOR' | 'TEK' | 'UYM'>;

const id = (prefix: string, n: number) => `0192f000-${prefix}-7000-8000-${String(n).padStart(12, '0')}`;

export const MOCK_IDS = {
  org: id('0000', 1),
  adm: id('1000', 1),
  kor: id('1000', 2),
  tek: id('1000', 3),
  uym: id('1000', 4),
  volunteer: (n: number) => id('2000', n),
  op1: id('3000', 1),
  op2: id('3000', 2),
  op3: id('3000', 3),
  group1: id('4000', 1),
  group2: id('4000', 2),
  task1: id('5000', 1),
  task2: id('5000', 2),
};

export interface MockParticipant {
  userId: Uuid;
  joinedAt: string;
}

export interface MockState {
  persona: MockPersona;
  loggedIn: boolean;
  /**
   * Yalnızca tanıtım: ADM/KOR örnek girişinde TOTP adımını açıkça atla. Varsayılan `false`: mock giriş backend
   * ID-02 gibi davranır ve ADM/KOR için `403 CLIENT_NOT_ALLOWED` döner (TOTP'siz ADM/KOR oturumu yok).
   */
  mfaBypass: boolean;
  users: Map<Uuid, UserSummary>;
  operations: Map<Uuid, OperationView>;
  participants: Map<Uuid, MockParticipant[]>;
  groups: Map<Uuid, GroupView>;
  tasks: Map<Uuid, TaskView & { operationId: Uuid }>;
  assignments: Map<Uuid, AssignmentView>;
  invitations: Map<Uuid, InvitationView>;
  idempotency: Map<string, unknown>;
  seq: number;
}

const VOLUNTEERS: Array<[string, string]> = [
  ['Ali K.', 'Ali Kaya'],
  ['Zeynep A.', 'Zeynep Arslan'],
  ['Can Ö.', 'Can Öztürk'],
  ['Elif Ş.', 'Elif Şahin'],
  ['Burak Ç.', 'Burak Çelik'],
  ['Deniz Y.', 'Deniz Yıldız'],
  ['Selin A.', 'Selin Aydın'],
  ['Emre K.', 'Emre Koç'],
];

function iso(offsetMinutes: number): string {
  return new Date(Date.UTC(2026, 10, 15, 8, 0, 0) + offsetMinutes * 60_000).toISOString();
}

function seed(): MockState {
  const users = new Map<Uuid, UserSummary>();
  users.set(MOCK_IDS.adm, {
    id: MOCK_IDS.adm,
    displayName: 'Yönetici (örnek)',
    fullName: 'Örnek Yönetici',
    status: 'ACTIVE',
    email: 'yonetici@ornek.invalid',
  });
  users.set(MOCK_IDS.kor, {
    id: MOCK_IDS.kor,
    displayName: 'Koordinatör (örnek)',
    fullName: 'Örnek Koordinatör',
    status: 'ACTIVE',
    email: 'koordinator@ornek.invalid',
  });
  users.set(MOCK_IDS.tek, { id: MOCK_IDS.tek, displayName: 'Teknik (örnek)', status: 'ACTIVE' });
  users.set(MOCK_IDS.uym, { id: MOCK_IDS.uym, displayName: 'Uyum (örnek)', status: 'ACTIVE' });
  VOLUNTEERS.forEach(([displayName, fullName], i) => {
    const uid = MOCK_IDS.volunteer(i + 1);
    users.set(uid, {
      id: uid,
      displayName,
      fullName,
      status: 'ACTIVE',
      email: `gonullu${i + 1}@ornek.invalid`,
      phone: `+90555000000${i + 1}`,
    });
  });

  const korRef = { id: MOCK_IDS.kor, displayName: 'Koordinatör (örnek)' };
  const operations = new Map<Uuid, OperationView>();
  operations.set(MOCK_IDS.op1, {
    id: MOCK_IDS.op1,
    name: 'Deprem Arama Kurtarma — Merkez',
    description: 'Örnek veri: merkez ilçede arama kurtarma koordinasyonu.',
    state: 'ACTIVE',
    createdAt: iso(-3000),
    activatedAt: iso(-2900),
    plannedEndAt: iso(4000),
    coordinators: [korRef],
    openAlertCount: 0,
    pendingAssignmentCount: 2,
    activeSharingCount: 4,
  });
  operations.set(MOCK_IDS.op2, {
    id: MOCK_IDS.op2,
    name: 'Sel Bölgesi Lojistik',
    description: 'Örnek veri: planlanan lojistik desteği.',
    state: 'PLANNED',
    createdAt: iso(-600),
    plannedEndAt: iso(10000),
    coordinators: [korRef],
    openAlertCount: 0,
    pendingAssignmentCount: 0,
  });
  operations.set(MOCK_IDS.op3, {
    id: MOCK_IDS.op3,
    name: 'Kış Yardımı (kapandı)',
    state: 'COMPLETED',
    createdAt: iso(-90000),
    activatedAt: iso(-89000),
    closedAt: iso(-40000),
    coordinators: [],
  });

  const participants = new Map<Uuid, MockParticipant[]>();
  participants.set(
    MOCK_IDS.op1,
    [1, 2, 3, 4, 5, 6].map((n) => ({ userId: MOCK_IDS.volunteer(n), joinedAt: iso(-2800 + n) })),
  );
  participants.set(
    MOCK_IDS.op2,
    [5, 6, 7, 8].map((n) => ({ userId: MOCK_IDS.volunteer(n), joinedAt: iso(-500 + n) })),
  );
  participants.set(MOCK_IDS.op3, []);

  const member = (n: number, role: 'LEADER' | 'MEMBER') => ({
    user: users.get(MOCK_IDS.volunteer(n))!,
    role,
    joinedAt: iso(-2700 + n),
  });

  const groups = new Map<Uuid, GroupView>();
  groups.set(MOCK_IDS.group1, {
    id: MOCK_IDS.group1,
    name: 'Arama Ekibi A',
    description: 'Kuzey mahalleleri.',
    operationId: MOCK_IDS.op1,
    operationName: 'Deprem Arama Kurtarma — Merkez',
    leader: { id: MOCK_IDS.volunteer(1), displayName: 'Ali K.' },
    memberCount: 3,
    archived: false,
    members: [member(1, 'LEADER'), member(2, 'MEMBER'), member(3, 'MEMBER')],
    meetingPoint: {
      address: 'Örnek Mahallesi, Toplanma Alanı 1',
      lat: 39.92,
      lon: 32.85,
      description: 'Okul bahçesi girişi',
      time: iso(120),
      updatedBy: korRef,
      updatedAt: iso(-100),
    },
    createdAt: iso(-2700),
    type: 'OPERATIONAL',
    joinPolicy: 'MANAGED',
  });
  groups.set(MOCK_IDS.group2, {
    id: MOCK_IDS.group2,
    name: 'Lojistik',
    operationId: MOCK_IDS.op1,
    operationName: 'Deprem Arama Kurtarma — Merkez',
    leader: { id: MOCK_IDS.volunteer(4), displayName: 'Elif Ş.' },
    memberCount: 2,
    archived: false,
    members: [member(4, 'LEADER'), member(5, 'MEMBER')],
    createdAt: iso(-2600),
    type: 'OPERATIONAL',
    joinPolicy: 'MANAGED',
  });

  const tasks = new Map<Uuid, TaskView & { operationId: Uuid }>();
  tasks.set(MOCK_IDS.task1, {
    id: MOCK_IDS.task1,
    operationId: MOCK_IDS.op1,
    title: 'Su dağıtımı — Kuzey çadır kenti',
    description: 'Örnek veri: 500 şişe su dağıtılacak.',
    priority: 'HIGH',
    state: 'PUBLISHED',
    acceptedCount: 1,
    pendingCount: 1,
    overdueCount: 1,
    capacity: 3,
    timeWindow: { start: iso(60), end: iso(240) },
    meetingPoint: 'Çadır kenti ana kapı',
    targets: { groupIds: [MOCK_IDS.group1], userIds: [] },
    responseWindowMinutes: 60,
    version: 2,
    createdBy: korRef,
    publishedAt: iso(-200),
    createdAt: iso(-300),
    history: [
      { state: 'DRAFT', changedBy: korRef, changedAt: iso(-300) },
      { state: 'PUBLISHED', changedBy: korRef, changedAt: iso(-200) },
    ],
  });
  tasks.set(MOCK_IDS.task2, {
    id: MOCK_IDS.task2,
    operationId: MOCK_IDS.op1,
    title: 'Enkaz alanı güvenlik şeridi',
    priority: 'NORMAL',
    state: 'DRAFT',
    acceptedCount: 0,
    pendingCount: 0,
    overdueCount: 0,
    targets: { userIds: [MOCK_IDS.volunteer(6)], groupIds: [] },
    responseWindowMinutes: 60,
    version: 1,
    createdBy: korRef,
    createdAt: iso(-50),
    history: [{ state: 'DRAFT', changedBy: korRef, changedAt: iso(-50) }],
  });

  const assignments = new Map<Uuid, AssignmentView>();
  const assign = (n: number, status: AssignmentView['status'], overdue: boolean, respondedAt?: string) => {
    const aid = id('6000', n);
    assignments.set(aid, {
      id: aid,
      taskId: MOCK_IDS.task1,
      user: users.get(MOCK_IDS.volunteer(n))!,
      status,
      respondBy: iso(overdue ? -140 : 60),
      overdue,
      respondedAt,
    });
  };
  assign(1, 'ACCEPTED', false, iso(-180));
  assign(2, 'PENDING', true);
  assign(3, 'REJECTED', false, iso(-170));

  const invitations = new Map<Uuid, InvitationView>();
  const inv1 = id('7000', 1);
  invitations.set(inv1, {
    id: inv1,
    kind: 'NEW_USER',
    status: 'PENDING',
    email: 'yeni.gonullu@ornek.invalid',
    scopeType: 'OPERATION',
    scopeId: MOCK_IDS.op1,
    scopeName: 'Deprem Arama Kurtarma — Merkez',
    expiresAt: iso(10080),
    createdAt: iso(-60),
    createdBy: korRef,
  });

  return {
    persona: 'KOR',
    loggedIn: false,
    mfaBypass: false,
    users,
    operations,
    participants,
    groups,
    tasks,
    assignments,
    invitations,
    idempotency: new Map(),
    seq: 100,
  };
}

export let mockStore: MockState = seed();

export function resetMockStore(): void {
  mockStore = seed();
  mockDelayMs = defaultDelay();
}

function defaultDelay(): number {
  return import.meta.env.MODE === 'test' ? 0 : 200;
}

let mockDelayMs = defaultDelay();

export function setMockDelay(ms: number): void {
  mockDelayMs = ms;
}

/** Ağ gecikmesi benzetimi; dönen değer kopyalanır ki çağıran depoyu değiştiremesin. */
export async function respond<T>(value: T): Promise<T> {
  if (mockDelayMs > 0) await new Promise((r) => setTimeout(r, mockDelayMs));
  return value === undefined ? value : (structuredClone(value) as T);
}

export async function fail(
  status: number,
  code: string,
  extensions: Record<string, unknown> = {},
  errors?: { field: string; code: string }[],
  retryAfterSeconds?: number,
): Promise<never> {
  if (mockDelayMs > 0) await new Promise((r) => setTimeout(r, mockDelayMs));
  throw new ApiError(
    {
      type: `/problems/${code.toLowerCase().replace(/_/g, '-')}`,
      title: code,
      status,
      code,
      traceId: `mock-${Date.now().toString(36)}`,
      errors,
      extensions,
    },
    retryAfterSeconds,
  );
}

export function nextId(prefix = '9000'): Uuid {
  mockStore.seq += 1;
  return id(prefix, mockStore.seq);
}

export const nowIso = (): string => new Date().toISOString();
