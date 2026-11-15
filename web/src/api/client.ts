import { ApiError, networkError, parseRetryAfter, toProblem } from './problem';
import { sessionEvents as defaultSessionEvents, type SessionEvents, type SessionEndReason } from './sessionEvents';

/**
 * API istemcisi (web-screen-api-mapping.md §0.4, §1.1, §1.3, §1.4; backend-api-design.md §2, §3).
 *
 * - Taban yol `/api/v1`, aynı köken; kimlik yalnızca HttpOnly çerezlerle (`__Host-access`, `__Secure-refresh`).
 *   JavaScript erişim/yenileme belirtecini hiçbir zaman okumaz veya saklamaz.
 * - CSRF çift gönderim: güvenli olmayan her istekte `XSRF-TOKEN` çerezi taze okunup `X-CSRF-Token` başlığına yazılır.
 *   Çerez yoksa önce `GET /auth/csrf`. `403 CSRF_TOKEN_INVALID` → tazele ve **bir kez** tekrarla.
 * - `401 AUTH_TOKEN_EXPIRED` → tekil uçuşlu `POST /auth/refresh`, sonra isteği bir kez tekrarla.
 * - `401 AUTH_SESSION_REVOKED` / `401 AUTH_UNAUTHENTICATED` / `403 ACCOUNT_DISABLED` → oturum sonu.
 * - `503 SERVICE_UNAVAILABLE` oturum sonu **değildir** (LD-48): temizlik, yenileme ve yönlendirme yapılmaz.
 */

export const API_BASE = '/api/v1';
export const CSRF_COOKIE = 'XSRF-TOKEN';
export const CSRF_HEADER = 'X-CSRF-Token';

export type QueryValue = string | number | boolean | readonly string[] | null | undefined;

export interface RequestOptions {
  query?: Record<string, QueryValue>;
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
  /**
   * Kimlik akışı uçları (`/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/csrf`): oturum sonu
   * olayı yayılmaz ve belirteç yenilemesi denenmez (hatayı ekran kendisi yorumlar).
   */
  authFlow?: boolean;
}

export interface ApiResponse<T> {
  status: number;
  data: T;
  headers: Headers;
}

export interface ApiClient {
  request<T>(method: HttpMethod, path: string, options?: RequestOptions): Promise<ApiResponse<T>>;
  get<T>(path: string, options?: RequestOptions): Promise<T>;
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  patch<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  delete<T = void>(path: string, options?: RequestOptions): Promise<T>;
  /** ID-01: `GET /auth/csrf`. Uygulama açılışında ve girişten önce çağrılır. */
  fetchCsrf(): Promise<void>;
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

const SAFE_METHODS = new Set<HttpMethod>(['GET']);
const SESSION_END_CODES = new Set<string>(['AUTH_SESSION_REVOKED', 'AUTH_UNAUTHENTICATED', 'ACCOUNT_DISABLED']);

export interface ApiClientConfig {
  baseUrl?: string;
  fetchFn?: typeof fetch;
  /** Çerez okuyucu (test için değiştirilebilir). */
  readCookie?: (name: string) => string | undefined;
  events?: SessionEvents;
  /** Sekmeler arası tek yenileme için Web Locks (yoksa sekme içi tekil uçuş yeterli). */
  locks?: Pick<LockManager, 'request'> | null;
}

export function readDocumentCookie(name: string): string | undefined {
  if (typeof document === 'undefined') return undefined;
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) return decodeURIComponent(trimmed.slice(prefix.length));
  }
  return undefined;
}

export function buildQuery(query: Record<string, QueryValue> | undefined): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      if (value.length > 0) params.set(key, value.join(','));
    } else {
      params.set(key, String(value));
    }
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

export function createApiClient(config: ApiClientConfig = {}): ApiClient {
  const baseUrl = config.baseUrl ?? API_BASE;
  const fetchFn = config.fetchFn ?? ((input: RequestInfo | URL, init?: RequestInit) => fetch(input, init));
  const readCookie = config.readCookie ?? readDocumentCookie;
  const events = config.events ?? defaultSessionEvents;
  const locks =
    config.locks !== undefined
      ? config.locks
      : typeof navigator !== 'undefined' && 'locks' in navigator
        ? navigator.locks
        : null;

  /** `GET /auth/csrf` gövdesinden gelen değer; çerez okunamadığında yedek (belirteç kimlik bilgisi değildir). */
  let csrfFallback: string | undefined;
  let csrfInFlight: Promise<void> | null = null;
  let refreshInFlight: Promise<boolean> | null = null;

  async function rawFetch(method: HttpMethod, path: string, options: RequestOptions, csrfToken?: string) {
    const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
    let body: string | undefined;
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(options.body);
    }
    if (csrfToken) headers[CSRF_HEADER] = csrfToken;
    if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;
    try {
      return await fetchFn(`${baseUrl}${path}${buildQuery(options.query)}`, {
        method,
        headers,
        body,
        credentials: 'same-origin',
        signal: options.signal,
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      throw networkError();
    }
  }

  async function parseBody(response: Response): Promise<unknown> {
    if (response.status === 204) return undefined;
    const text = await response.text();
    if (!text) return undefined;
    try {
      return JSON.parse(text) as unknown;
    } catch {
      return undefined;
    }
  }

  async function fetchCsrf(): Promise<void> {
    if (!csrfInFlight) {
      csrfInFlight = (async () => {
        const response = await rawFetch('GET', '/auth/csrf', { authFlow: true });
        const body = await parseBody(response);
        if (!response.ok) throw new ApiError(toProblem(response.status, body));
        const token = (body as { token?: unknown } | undefined)?.token;
        if (typeof token === 'string') csrfFallback = token;
      })().finally(() => {
        csrfInFlight = null;
      });
    }
    return csrfInFlight;
  }

  async function csrfToken(): Promise<string | undefined> {
    const fromCookie = readCookie(CSRF_COOKIE);
    if (fromCookie) return fromCookie;
    if (!csrfFallback) await fetchCsrf();
    return readCookie(CSRF_COOKIE) ?? csrfFallback;
  }

  async function doRefresh(): Promise<boolean> {
    try {
      const token = await csrfToken();
      const response = await rawFetch('POST', '/auth/refresh', { authFlow: true }, token);
      return response.ok;
    } catch {
      return false;
    }
  }

  /** Tekil uçuş: eşzamanlı 401'ler tek `POST /auth/refresh` bekler (mapping §1.3). */
  function refreshSession(): Promise<boolean> {
    if (!refreshInFlight) {
      const run = locks ? () => locks.request('gks-auth-refresh', doRefresh) as Promise<boolean> : doRefresh;
      refreshInFlight = run().finally(() => {
        refreshInFlight = null;
      });
    }
    return refreshInFlight;
  }

  function endSession(reason: SessionEndReason) {
    events.endSession(reason);
  }

  async function request<T>(method: HttpMethod, path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
    let csrfRetried = false;
    let refreshRetried = false;

    for (;;) {
      const token = SAFE_METHODS.has(method) ? undefined : await csrfToken();
      const response = await rawFetch(method, path, options, token);
      const body = await parseBody(response);

      if (response.ok) {
        return { status: response.status, data: body as T, headers: response.headers };
      }

      const error = new ApiError(toProblem(response.status, body), parseRetryAfter(response.headers.get('Retry-After')));

      if (error.code === 'CSRF_TOKEN_INVALID' && !csrfRetried) {
        csrfRetried = true;
        csrfFallback = undefined;
        await fetchCsrf();
        continue;
      }

      if (!options.authFlow) {
        if (error.code === 'AUTH_TOKEN_EXPIRED' && !refreshRetried) {
          refreshRetried = true;
          if (await refreshSession()) continue;
          endSession('REFRESH_FAILED');
          throw error;
        }
        if (SESSION_END_CODES.has(error.code)) {
          endSession(error.code as SessionEndReason);
        } else if (error.code === 'FORBIDDEN') {
          events.forbidden.emit();
        }
        // SERVICE_UNAVAILABLE (503): bilinçli olarak hiçbir oturum eylemi yok (LD-48).
      }
      throw error;
    }
  }

  return {
    request,
    fetchCsrf,
    get: async <T>(path: string, options?: RequestOptions) => (await request<T>('GET', path, options)).data,
    post: async <T>(path: string, body?: unknown, options?: RequestOptions) =>
      (await request<T>('POST', path, { ...options, body })).data,
    put: async <T>(path: string, body?: unknown, options?: RequestOptions) =>
      (await request<T>('PUT', path, { ...options, body })).data,
    patch: async <T>(path: string, body?: unknown, options?: RequestOptions) =>
      (await request<T>('PATCH', path, { ...options, body })).data,
    delete: async <T = void>(path: string, options?: RequestOptions) =>
      (await request<T>('DELETE', path, options)).data,
  };
}

/** Uygulama genelindeki tek istemci. */
export const apiClient: ApiClient = createApiClient();
