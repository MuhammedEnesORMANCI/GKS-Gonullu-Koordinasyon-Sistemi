import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '../client';
import { ApiError } from '../problem';
import { SessionEvents } from '../sessionEvents';
import { createFetchMock, problem } from '../../test/fetchMock';

function setup(handler: Parameters<typeof createFetchMock>[0], cookie: () => string | undefined = () => 'csrf-cookie') {
  const { fetch, calls } = createFetchMock(handler);
  const events = new SessionEvents();
  const ended = vi.fn();
  const forbidden = vi.fn();
  events.sessionEnded.on(ended);
  events.forbidden.on(forbidden);
  const api = createApiClient({ fetchFn: fetch, readCookie: () => cookie(), events, locks: null });
  return { api, calls, ended, forbidden };
}

describe('API istemcisi', () => {
  it('GET: /api/v1 öneki, sorgu dizgesi, CSRF başlığı yok, aynı köken çerezleri', async () => {
    const { api, calls } = setup([{ status: 200, body: { items: [] } }]);
    await api.get('/groups', { query: { operationId: 'op-1', includeArchived: undefined, state: ['A', 'B'] } });
    expect(calls[0]!.url).toBe('/api/v1/groups?operationId=op-1&state=A%2CB');
    expect(calls[0]!.method).toBe('GET');
    expect(calls[0]!.headers['X-CSRF-Token']).toBeUndefined();
    expect(calls[0]!.credentials).toBe('same-origin');
  });

  it('POST: XSRF-TOKEN çerezini X-CSRF-Token başlığına yazar; Idempotency-Key iletilir', async () => {
    const { api, calls } = setup([{ status: 201, body: { id: 'g1' } }]);
    await api.post('/groups/g1/members', { userId: 'u1' }, { idempotencyKey: 'key-1' });
    expect(calls[0]!.headers['X-CSRF-Token']).toBe('csrf-cookie');
    expect(calls[0]!.headers['Idempotency-Key']).toBe('key-1');
    expect(calls[0]!.headers['Content-Type']).toBe('application/json');
    expect(calls[0]!.body).toEqual({ userId: 'u1' });
  });

  it('çerez yoksa önce GET /auth/csrf çağrılır, gövdedeki belirteç yedek olarak kullanılır', async () => {
    const { api, calls } = setup(
      [
        { status: 200, body: { headerName: 'X-CSRF-Token', token: 'from-body' } },
        { status: 204 },
      ],
      () => undefined,
    );
    await api.delete('/groups/g1/members/u1');
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual(['GET /api/v1/auth/csrf', 'DELETE /api/v1/groups/g1/members/u1']);
    expect(calls[1]!.headers['X-CSRF-Token']).toBe('from-body');
  });

  it('403 CSRF_TOKEN_INVALID → CSRF tazelenir ve istek bir kez tekrarlanır (aynı Idempotency-Key)', async () => {
    let cookie = 'old';
    const { api, calls } = setup(
      [
        problem(403, 'CSRF_TOKEN_INVALID'),
        { status: 200, body: { token: 'new' } },
        { status: 200, body: { ok: true } },
      ],
      () => cookie,
    );
    const p = api.post('/operations/o1/state', { targetState: 'ACTIVE' }, { idempotencyKey: 'k' });
    // csrf çağrısı sırasında çerez güncellenir
    cookie = 'new';
    await expect(p).resolves.toEqual({ ok: true });
    expect(calls.map((c) => c.url)).toEqual(['/api/v1/operations/o1/state', '/api/v1/auth/csrf', '/api/v1/operations/o1/state']);
    expect(calls[2]!.headers['X-CSRF-Token']).toBe('new');
    expect(calls[2]!.headers['Idempotency-Key']).toBe('k');
  });

  it('CSRF ikinci kez reddedilirse hata fırlatır (tekrar yok)', async () => {
    const { api, calls } = setup([
      problem(403, 'CSRF_TOKEN_INVALID'),
      { status: 200, body: { token: 't' } },
      problem(403, 'CSRF_TOKEN_INVALID'),
    ]);
    await expect(api.post('/x')).rejects.toMatchObject({ code: 'CSRF_TOKEN_INVALID', status: 403 });
    expect(calls).toHaveLength(3);
  });

  it('401 AUTH_TOKEN_EXPIRED → tekil uçuşlu yenileme, eşzamanlı istekler bir kez tekrarlanır', async () => {
    let refreshed = false;
    const { api, calls, ended } = setup((call) => {
      if (call.url === '/api/v1/auth/refresh') {
        refreshed = true;
        return { status: 200, body: { expiresAt: 'x' } };
      }
      return refreshed ? { status: 200, body: { url: call.url } } : problem(401, 'AUTH_TOKEN_EXPIRED');
    });
    const [a, b] = await Promise.all([api.get('/a'), api.get('/b')]);
    expect(a).toEqual({ url: '/api/v1/a' });
    expect(b).toEqual({ url: '/api/v1/b' });
    expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1);
    expect(calls.find((c) => c.url === '/api/v1/auth/refresh')!.headers['X-CSRF-Token']).toBe('csrf-cookie');
    expect(ended).not.toHaveBeenCalled();
  });

  it('yenileme başarısızsa oturum sonu (REFRESH_FAILED)', async () => {
    const { api, ended } = setup([problem(401, 'AUTH_TOKEN_EXPIRED'), problem(401, 'AUTH_SESSION_REVOKED')]);
    await expect(api.get('/a')).rejects.toMatchObject({ code: 'AUTH_TOKEN_EXPIRED' });
    expect(ended).toHaveBeenCalledWith('REFRESH_FAILED');
  });

  it('401 AUTH_SESSION_REVOKED → oturum sonu, yenileme denenmez', async () => {
    const { api, calls, ended } = setup([problem(401, 'AUTH_SESSION_REVOKED')]);
    await expect(api.get('/groups/g1')).rejects.toBeInstanceOf(ApiError);
    expect(ended).toHaveBeenCalledWith('AUTH_SESSION_REVOKED');
    expect(calls).toHaveLength(1);
  });

  it('403 ACCOUNT_DISABLED ve 401 AUTH_UNAUTHENTICATED → oturum sonu', async () => {
    const { api, ended } = setup([problem(403, 'ACCOUNT_DISABLED'), problem(401, 'AUTH_UNAUTHENTICATED')]);
    await expect(api.get('/a')).rejects.toBeInstanceOf(ApiError);
    await expect(api.get('/b')).rejects.toBeInstanceOf(ApiError);
    expect(ended.mock.calls).toEqual([['ACCOUNT_DISABLED'], ['AUTH_UNAUTHENTICATED']]);
  });

  it('503 SERVICE_UNAVAILABLE oturum sonu DEĞİLDİR (LD-48): olay yok, yenileme yok', async () => {
    const { api, calls, ended, forbidden } = setup([problem(503, 'SERVICE_UNAVAILABLE')]);
    const error = await api.get('/operations/o1').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('SERVICE_UNAVAILABLE');
    expect((error as ApiError).isRetryable).toBe(true);
    expect(ended).not.toHaveBeenCalled();
    expect(forbidden).not.toHaveBeenCalled();
    expect(calls).toHaveLength(1);
  });

  it('403 FORBIDDEN → me/context yenileme olayı', async () => {
    const { api, forbidden, ended } = setup([problem(403, 'FORBIDDEN')]);
    await expect(api.get('/a')).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(forbidden).toHaveBeenCalledTimes(1);
    expect(ended).not.toHaveBeenCalled();
  });

  it('kimlik akışı uçlarında (authFlow) oturum olayı yayılmaz', async () => {
    const { api, ended } = setup([problem(403, 'ACCOUNT_DISABLED')]);
    await expect(api.post('/auth/login', {}, { authFlow: true })).rejects.toMatchObject({ code: 'ACCOUNT_DISABLED' });
    expect(ended).not.toHaveBeenCalled();
  });

  it('uzantılar ve Retry-After hataya taşınır', async () => {
    const { api } = setup([
      { ...problem(423, 'ACCOUNT_LOCKED', { lockedUntil: '2026-11-15T10:00:00Z' }), headers: { 'Retry-After': '42' } },
    ]);
    const e = (await api.post('/auth/login', {}, { authFlow: true }).catch((x: unknown) => x)) as ApiError;
    expect(e.status).toBe(423);
    expect(e.retryAfterSeconds).toBe(42);
    expect(e.extension('lockedUntil')).toBe('2026-11-15T10:00:00Z');
    expect(e.traceId).toBe('trace-1');
  });

  it('ağ hatası NETWORK_ERROR olur', async () => {
    const fetchFn = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch;
    const api = createApiClient({ fetchFn, readCookie: () => 't', events: new SessionEvents(), locks: null });
    await expect(api.get('/a')).rejects.toMatchObject({ code: 'NETWORK_ERROR', isNetworkError: true });
  });

  it('204 gövdesiz yanıt undefined döner', async () => {
    const { api } = setup([{ status: 204 }]);
    await expect(api.delete('/groups/g/members/u')).resolves.toBeUndefined();
  });
});
