import { useCallback, useRef } from 'react';

function randomUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  // Yedek (eski tarayıcı): RFC 4122 v4 biçimi.
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * `Idempotency-Key` niyet anahtarı (mapping §0.5, backend §3.6): aynı gövdeyle yapılan tekrarlarda (çift tıklama,
 * "Tekrar dene", ağ tekrarı) aynı anahtar; gövde değişince yeni anahtar; başarıdan sonra `reset()` ile atılır.
 */
export function useIntentKey() {
  const ref = useRef<{ body: string; key: string } | null>(null);

  const keyFor = useCallback((body: unknown): string => {
    const serialized = JSON.stringify(body ?? null);
    if (!ref.current || ref.current.body !== serialized) {
      ref.current = { body: serialized, key: randomUuid() };
    }
    return ref.current.key;
  }, []);

  const reset = useCallback(() => {
    ref.current = null;
  }, []);

  return { keyFor, reset };
}
