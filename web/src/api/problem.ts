/**
 * RFC 9457 `application/problem+json` hata modeli (backend-api-design.md §4.1).
 * İstemci mesajı yalnızca `code`'a göre seçer; `title`/`detail` kullanıcıya gösterilmez.
 */

export type FieldErrorCode =
  | 'REQUIRED'
  | 'TOO_LONG'
  | 'TOO_SHORT'
  | 'OUT_OF_RANGE'
  | 'INVALID_FORMAT'
  | 'READ_ONLY'
  | 'UNKNOWN_FIELD'
  | 'NOT_ALLOWED_VALUE'
  | 'EMPTY_AUDIENCE'
  | 'CONFIRMATION_REQUIRED'
  | 'INVALID_CODE'
  | 'INVALID'
  | 'DUPLICATE'
  | (string & {});

export interface FieldError {
  field: string;
  code: FieldErrorCode;
  message?: string;
}

export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  code: string;
  detail?: string;
  traceId?: string;
  errors?: FieldError[];
  /** Koda özgü uzantı alanları (ör. `conflict`, `lockedUntil`, `currentVersion`). */
  extensions: Record<string, unknown>;
}

/** İstemcinin üretebildiği yerel hata türleri (sunucu kodu değildir). */
export const NETWORK_ERROR = 'NETWORK_ERROR';
export const UNKNOWN_ERROR = 'UNKNOWN_ERROR';

const KNOWN_KEYS = new Set(['type', 'title', 'status', 'code', 'detail', 'traceId', 'errors', 'instance']);

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | undefined;
  readonly errors: FieldError[];
  readonly extensions: Record<string, unknown>;
  /** Saniye cinsinden `Retry-After` (423, 429, 503). */
  readonly retryAfterSeconds: number | undefined;

  constructor(problem: ProblemDetail, retryAfterSeconds?: number) {
    super(problem.title || problem.code);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = problem.code;
    this.traceId = problem.traceId;
    this.errors = problem.errors ?? [];
    this.extensions = problem.extensions;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  get isNetworkError(): boolean {
    return this.code === NETWORK_ERROR;
  }

  /** Ağ hatası veya 5xx: TanStack Query yeniden denemesi yalnızca bunlarda yapılır (mapping §6.1). */
  get isRetryable(): boolean {
    return this.isNetworkError || this.status >= 500;
  }

  extension<T = unknown>(name: string): T | undefined {
    return this.extensions[name] as T | undefined;
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

/** `Retry-After` başlığı: saniye ya da HTTP tarihi. */
export function parseRetryAfter(header: string | null, now: number = Date.now()): number | undefined {
  if (!header) return undefined;
  const trimmed = header.trim();
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  const date = Date.parse(trimmed);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.ceil((date - now) / 1000));
}

/** Gövdeyi ProblemDetail'e çevirir; biçim bozuksa durum koduna göre güvenli varsayılan üretir. */
export function toProblem(status: number, body: unknown): ProblemDetail {
  if (body && typeof body === 'object' && typeof (body as { code?: unknown }).code === 'string') {
    const raw = body as Record<string, unknown>;
    const extensions: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(raw)) {
      if (!KNOWN_KEYS.has(key)) extensions[key] = value;
    }
    // Bazı sunucular uzantıları iç içe `extensions` nesnesinde verir; ikisini de kabul et.
    if (raw.extensions && typeof raw.extensions === 'object') {
      Object.assign(extensions, raw.extensions as Record<string, unknown>);
      delete extensions.extensions;
    }
    return {
      type: typeof raw.type === 'string' ? raw.type : 'about:blank',
      title: typeof raw.title === 'string' ? raw.title : '',
      status: typeof raw.status === 'number' ? raw.status : status,
      code: raw.code as string,
      detail: typeof raw.detail === 'string' ? raw.detail : undefined,
      traceId: typeof raw.traceId === 'string' ? raw.traceId : undefined,
      errors: Array.isArray(raw.errors) ? (raw.errors as FieldError[]) : undefined,
      extensions,
    };
  }
  return {
    type: 'about:blank',
    title: '',
    status,
    code: status === 503 ? 'SERVICE_UNAVAILABLE' : UNKNOWN_ERROR,
    extensions: {},
  };
}

export function networkError(): ApiError {
  return new ApiError({ type: 'about:blank', title: 'Network error', status: 0, code: NETWORK_ERROR, extensions: {} });
}
