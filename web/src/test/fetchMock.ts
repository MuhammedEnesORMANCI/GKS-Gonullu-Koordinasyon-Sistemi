import { vi } from 'vitest';

export interface RecordedCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
  credentials: RequestCredentials | undefined;
}

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Handler = (call: RecordedCall) => Reply | Promise<Reply>;

export function jsonResponse({ status, body, headers = {} }: Reply): Response {
  const isProblem = status >= 400;
  const text = body === undefined ? '' : JSON.stringify(body);
  return new Response(status === 204 ? null : text, {
    status,
    headers: {
      'Content-Type': isProblem ? 'application/problem+json' : 'application/json',
      ...headers,
    },
  });
}

export function problem(status: number, code: string, extra: Record<string, unknown> = {}) {
  return {
    status,
    body: { type: `/problems/${code.toLowerCase()}`, title: code, status, code, traceId: 'trace-1', ...extra },
  };
}

/** Sıralı yanıtlar veya işleyici ile sahte `fetch`; tüm çağrıları kaydeder. */
export function createFetchMock(handler: Handler | Reply[]) {
  const calls: RecordedCall[] = [];
  const queue = Array.isArray(handler) ? [...handler] : null;
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const headers: Record<string, string> = {};
    const raw = init?.headers;
    if (raw && !(raw instanceof Headers) && !Array.isArray(raw)) Object.assign(headers, raw);
    const call: RecordedCall = {
      url: String(input),
      method: init?.method ?? 'GET',
      headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      credentials: init?.credentials,
    };
    calls.push(call);
    const reply = queue ? queue.shift() : await (handler as Handler)(call);
    if (!reply) throw new Error(`Beklenmeyen istek: ${call.method} ${call.url}`);
    return jsonResponse(reply);
  });
  return { fetch: fn as unknown as typeof fetch, calls };
}
