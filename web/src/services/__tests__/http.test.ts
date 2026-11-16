import { describe, expect, it } from 'vitest';
import { createApiClient } from '../../api/client';
import { SessionEvents } from '../../api/sessionEvents';
import { createFetchMock } from '../../test/fetchMock';
import {
  httpAuthService,
  httpGroupService,
  httpInvitationService,
  httpOperationListService,
  httpOperationService,
  httpParticipantService,
  httpTaskService,
  httpAlertService,
  pushStatusFromHealth,
} from '../http';

function api(status = 200, body: unknown = {}) {
  const mock = createFetchMock(() => ({ status, body }));
  const client = createApiClient({ fetchFn: mock.fetch, readCookie: () => 'csrf', events: new SessionEvents(), locks: null });
  return { client, calls: mock.calls };
}

const G = '0192f000-4000-7000-8000-000000000001';
const U = '0192f000-2000-7000-8000-000000000002';
const O = '0192f000-3000-7000-8000-000000000003';
const T = '0192f000-5000-7000-8000-000000000004';

describe('http genel alarm kişi adayları (RAPOR_7 madde 3)', () => {
  it('GET /general-alerts/candidates?q&limit&cursor; boş arama gönderilmez', async () => {
    const { client, calls } = api(200, { items: [] });
    await httpAlertService(client).generalCandidates(' Ayşe ', { limit: 50, cursor: 'abc' });
    await httpAlertService(client).generalCandidates('  ');
    expect(calls[0]!.url).toBe('/api/v1/general-alerts/candidates?q=Ay%C5%9Fe&limit=50&cursor=abc');
    expect(calls[1]!.url).toBe('/api/v1/general-alerts/candidates');
  });
});

describe('http AuthService (ID-01, ID-02, ID-06)', () => {
  it('login: POST /auth/login, clientType=WEB, CSRF başlığı, Idempotency-Key yok', async () => {
    const { client, calls } = api(200, { mfaRequired: false, mustChangePassword: false });
    await httpAuthService(client).login({ email: 'a@b.c', password: 'p' });
    expect(calls[0]).toMatchObject({
      method: 'POST',
      url: '/api/v1/auth/login',
      body: { email: 'a@b.c', password: 'p', clientType: 'WEB' },
    });
    expect(calls[0]!.headers['X-CSRF-Token']).toBe('csrf');
    expect(calls[0]!.headers['Idempotency-Key']).toBeUndefined();
  });

  it('fetchCsrf: GET /auth/csrf; logout: POST /auth/logout', async () => {
    const { client, calls } = api(200, { headerName: 'X-CSRF-Token', token: 't' });
    const auth = httpAuthService(client);
    await auth.fetchCsrf();
    await auth.logout();
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual(['GET /api/v1/auth/csrf', 'POST /api/v1/auth/logout']);
  });
});

describe('http AuthService MFA ve parola (ID-03, ID-04, ID-08)', () => {
  it('enrollMfa / verifyMfa bileti gövdede taşır; changePassword POST /me/password', async () => {
    const { client, calls } = api(200, { otpauthUri: 'otpauth://x', secret: 'S' });
    const auth = httpAuthService(client);
    await auth.enrollMfa('bilet');
    await auth.verifyMfa('bilet', '123456');
    await auth.changePassword({ currentPassword: 'eski-parola', newPassword: 'yeni-uzun-parola' });
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST /api/v1/auth/mfa/enroll',
      'POST /api/v1/auth/mfa',
      'POST /api/v1/me/password',
    ]);
    expect(calls[0]!.body).toEqual({ mfaTicket: 'bilet' });
    expect(calls[1]!.body).toEqual({ mfaTicket: 'bilet', code: '123456' });
    expect(calls[2]!.body).toEqual({ currentPassword: 'eski-parola', newPassword: 'yeni-uzun-parola' });
    expect(calls.every((c) => c.headers['X-CSRF-Token'] === 'csrf')).toBe(true);
  });
});

describe('http InvitationService (ID-12 – ID-14)', () => {
  it('list süzgeçleri, create Idempotency-Key ile, revoke', async () => {
    const { client, calls } = api(200, { items: [] });
    const svc = httpInvitationService(client);
    await svc.list({ scopeType: 'OPERATION', scopeId: O, status: ['PENDING', 'EXPIRED'] });
    await svc.create({ email: 'a@b.c', scopeType: 'OPERATION', scopeId: O }, 'key-1');
    await svc.revoke(G);
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      `GET /api/v1/invitations?scopeType=OPERATION&scopeId=${O}&status=PENDING%2CEXPIRED`,
      'POST /api/v1/invitations',
      `POST /api/v1/invitations/${G}/revoke`,
    ]);
    expect(calls[1]!.body).toEqual({ email: 'a@b.c', scopeType: 'OPERATION', scopeId: O });
    expect(calls[1]!.headers['Idempotency-Key']).toBe('key-1');
  });
});

describe('http OperationService (OR-02, OR-03, OR-05) ve liste/katılımcı (OR-04, OR-06)', () => {
  it('get / update / changeState', async () => {
    const { client, calls } = api(200, { id: O });
    const svc = httpOperationService(client);
    await svc.get(O);
    await svc.update(O, { name: 'Yeni ad', description: null, plannedEndAt: null });
    await svc.changeState(O, { targetState: 'COMPLETED' });
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      `GET /api/v1/operations/${O}`,
      `PATCH /api/v1/operations/${O}`,
      `POST /api/v1/operations/${O}/state`,
    ]);
    // LD-52: açık null gövdede korunur (alan temizleme)
    expect(calls[1]!.body).toEqual({ name: 'Yeni ad', description: null, plannedEndAt: null });
    expect(calls[2]!.body).toEqual({ targetState: 'COMPLETED' });
    expect(calls[2]!.headers['Idempotency-Key']).toBeUndefined();
  });

  it('list: state çoklu, limit/cursor; participants: notInGroup', async () => {
    const { client, calls } = api(200, { items: [] });
    await httpOperationListService(client).list({ state: ['PLANNED', 'ACTIVE'], limit: 20, cursor: 'c1' });
    await httpParticipantService(client).list(O, { notInGroup: G });
    expect(calls[0]!.url).toBe('/api/v1/operations?state=PLANNED%2CACTIVE&limit=20&cursor=c1');
    expect(calls[1]!.url).toBe(`/api/v1/operations/${O}/participants?notInGroup=${G}`);
  });
});

describe('http GroupService (GR-01 … GR-08)', () => {
  it('tüm uçlar: yol, yöntem, gövde ve başlıklar', async () => {
    const { client, calls } = api(200, {});
    const svc = httpGroupService(client);
    await svc.list(O, { includeArchived: true });
    await svc.list(O);
    await svc.get(G);
    await svc.create(O, { name: 'Ekip', leaderUserId: U, memberUserIds: [] }, 'idem-1');
    await svc.addMember(G, U, 'idem-2');
    await svc.removeMember(G, U);
    await svc.changeLeader(G, U, 'idem-3');
    await svc.update(G, { description: null });
    await svc.setMeetingPoint(G, { address: 'Adres', lat: 39.9, lon: 32.8, time: '2026-11-15T10:00:00.000Z' });

    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      `GET /api/v1/groups?operationId=${O}&includeArchived=true`,
      `GET /api/v1/groups?operationId=${O}`,
      `GET /api/v1/groups/${G}`,
      `POST /api/v1/operations/${O}/groups`,
      `POST /api/v1/groups/${G}/members`,
      `DELETE /api/v1/groups/${G}/members/${U}`,
      `PUT /api/v1/groups/${G}/leader`,
      `PATCH /api/v1/groups/${G}`,
      `PUT /api/v1/groups/${G}/meeting-point`,
    ]);
    expect(calls[3]!.body).toEqual({ name: 'Ekip', leaderUserId: U, memberUserIds: [] });
    expect(calls[3]!.headers['Idempotency-Key']).toBe('idem-1');
    expect(calls[4]!.body).toEqual({ userId: U });
    expect(calls[4]!.headers['Idempotency-Key']).toBe('idem-2');
    expect(calls[5]!.body).toBeUndefined();
    expect(calls[6]!.body).toEqual({ userId: U });
    expect(calls[6]!.headers['Idempotency-Key']).toBe('idem-3');
    expect(calls[7]!.body).toEqual({ description: null });
    expect(calls[7]!.headers['Idempotency-Key']).toBeUndefined();
    expect(calls[8]!.body).toEqual({ address: 'Adres', lat: 39.9, lon: 32.8, time: '2026-11-15T10:00:00.000Z' });
    // Güvenli olmayan tüm isteklerde CSRF başlığı
    for (const c of calls.filter((x) => x.method !== 'GET')) expect(c.headers['X-CSRF-Token']).toBe('csrf');
  });
});

describe('http TaskService (TK-01 … TK-09)', () => {
  it('tüm web uçları', async () => {
    const { client, calls } = api(200, {});
    const svc = httpTaskService(client);
    await svc.list(O, { state: 'DRAFT' });
    await svc.get(T);
    await svc.assignments(T, { status: ['PENDING', 'CAPACITY_FULL'], overdue: true });
    await svc.create(O, { title: 'Görev', targets: { groupIds: [G] } }, 'k1');
    await svc.update(T, {
      title: 'Yeni',
      capacity: null,
      description: null,
      meetingPoint: null,
      timeWindow: null,
      responseWindowMinutes: null,
      version: 3,
    });
    await svc.publish(T, 4, 'k2');
    await svc.changeState(T, { targetState: 'CANCELLED' });
    await svc.addAssignment(T, U, 'k3');
    await svc.removeAssignment(T, 'a1');

    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      `GET /api/v1/operations/${O}/tasks?state=DRAFT`,
      `GET /api/v1/tasks/${T}`,
      `GET /api/v1/tasks/${T}/assignments?status=PENDING%2CCAPACITY_FULL&overdue=true`,
      `POST /api/v1/operations/${O}/tasks`,
      `PATCH /api/v1/tasks/${T}`,
      `POST /api/v1/tasks/${T}/publish`,
      `POST /api/v1/tasks/${T}/state`,
      `POST /api/v1/tasks/${T}/assignments`,
      `DELETE /api/v1/tasks/${T}/assignments/a1`,
    ]);
    expect(calls[3]!.body).toEqual({ title: 'Görev', targets: { groupIds: [G] } });
    expect(calls[3]!.headers['Idempotency-Key']).toBe('k1');
    expect(calls[4]!.body).toEqual({
      title: 'Yeni',
      capacity: null,
      description: null,
      meetingPoint: null,
      timeWindow: null,
      responseWindowMinutes: null,
      version: 3,
    });
    expect(calls[5]!.body).toEqual({ version: 4 });
    expect(calls[5]!.headers['Idempotency-Key']).toBe('k2');
    expect(calls[6]!.body).toEqual({ targetState: 'CANCELLED' });
    expect(calls[6]!.headers['Idempotency-Key']).toBeUndefined();
    expect(calls[7]!.body).toEqual({ userId: U });
  });

  it('yol parametreleri kodlanır', async () => {
    const { client, calls } = api(200, {});
    await httpTaskService(client).get('a/b');
    expect(calls[0]!.url).toBe('/api/v1/tasks/a%2Fb');
  });
});

describe('http AlertService.pushStatus (ADR-022)', () => {
  it('sağlık ucundaki push bileşenini okur; beklenmeyen biçim ve hata UNKNOWN', async () => {
    expect(pushStatusFromHealth({ status: 'UP', components: { push: { status: 'UP' } } })).toBe('UP');
    expect(pushStatusFromHealth({ components: { push: { status: 'DEGRADED' } } })).toBe('DEGRADED');
    expect(pushStatusFromHealth({ components: { push: { status: 'DISABLED' } } })).toBe('DISABLED');
    expect(pushStatusFromHealth({ status: 'UP' })).toBe('UNKNOWN');
    expect(pushStatusFromHealth(null)).toBe('UNKNOWN');

    const { client } = api();
    const urls: string[] = [];
    const ok = httpAlertService(client, async (input) => {
      urls.push(input);
      return new Response(JSON.stringify({ status: 'UP', components: { push: { status: 'UP' } } }), { status: 200 });
    });
    expect(await ok.pushStatus()).toBe('UP');
    expect(urls).toEqual(['/actuator/health']);

    const down = httpAlertService(client, async () => {
      throw new TypeError('ağ yok');
    });
    expect(await down.pushStatus()).toBe('UNKNOWN');
  });
});
