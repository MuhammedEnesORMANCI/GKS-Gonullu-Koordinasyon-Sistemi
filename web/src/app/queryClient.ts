import { QueryClient } from '@tanstack/react-query';
import { isApiError } from '../api/problem';

/**
 * Genel ayarlar (web-screen-api-mapping.md §6.1): yeniden deneme yalnızca ağ hatası ve 5xx için en fazla 2 kez;
 * 4xx'te tekrar yok. 503 SERVICE_UNAVAILABLE de üstel geri çekilmeyle yeniden denenir (LD-48).
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (!isApiError(error)) return false;
  return error.isRetryable && failureCount < 2;
}

/** Idempotent mutasyonlar (aynı `Idempotency-Key`): ağ/5xx'te en fazla 3 deneme (§6.3). */
export function shouldRetryIdempotentMutation(failureCount: number, error: unknown): boolean {
  if (!isApiError(error)) return false;
  return error.isRetryable && failureCount < 2;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 15_000),
        refetchOnWindowFocus: true,
        staleTime: 30_000,
      },
      mutations: {
        retry: false,
      },
    },
  });
}
