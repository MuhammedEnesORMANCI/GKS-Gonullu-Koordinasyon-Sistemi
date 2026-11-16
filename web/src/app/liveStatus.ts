import { useSyncExternalStore } from 'react';

/**
 * Canlı akışın (RT-01) bağlantı durumu. Akış bağlı değilken açık sohbet kısa aralıkla yoklanır (RAPOR_7 madde 2):
 * tünel/vekil olayları geciktirir ya da akış kopuk kalırsa yeni mesaj yine birkaç saniyede görünür.
 */
export const CHAT_POLL_MS = 5_000;

let connected = false;
const listeners = new Set<() => void>();

export function setLiveConnected(value: boolean): void {
  if (connected === value) return;
  connected = value;
  listeners.forEach((l) => l());
}

export function isLiveConnected(): boolean {
  return connected;
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function useLiveConnected(): boolean {
  return useSyncExternalStore(subscribe, isLiveConnected, isLiveConnected);
}

/** Açık sohbetin yeniden çekim aralığı: akış bağlıysa olaylar yeter, değilse yoklama. */
export function chatRefetchInterval(live: boolean): number | false {
  return live ? false : CHAT_POLL_MS;
}
