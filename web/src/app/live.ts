import { useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { sessionEvents } from '../api/sessionEvents';
import type { LiveEvent } from '../services/contracts';
import { useServices } from '../services/registry';
import type { MeContext } from '../services/types';
import { Q } from './queryKeys';
import { useSession } from './session';

/** RT-01: bir bağlantıda en çok 10 konu (kullanıcı konusu sunucuda kendiliğinden eklenir). */
export const MAX_LIVE_TOPICS = 10;
/** Akış kalıcı kapanınca (ör. erişim belirtecinin süresi) yeniden deneme aralığı. */
export const LIVE_RECONNECT_MS = 5_000;

/**
 * Abone olunacak konular, sunucunun etkin izinlerinden (yetki kaynağı değildir; sunucu her bağlantıda denetler):
 * kurum geneli duyuru izni → `organization`; duyuru gönderebildiği operasyonlar → `operation:{id}` (ADR-015 ile ADM
 * tüm operasyonlarda). Sınır aşılırsa kullanıcının katıldığı/aktif operasyonlar önce gelir.
 */
export function liveTopics(context: MeContext | undefined): string[] {
  if (!context) return [];
  const topics: string[] = [];
  if (context.permissions.organization.includes('announcement.send.organization')) topics.push('organization');
  const own = new Set(context.operations.map((op) => op.id));
  const operations = Object.entries(context.permissions.operations)
    .filter(([, list]) => list.includes('announcement.send.group'))
    .map(([id]) => id)
    .sort((a, b) => Number(own.has(b)) - Number(own.has(a)) || a.localeCompare(b));
  for (const id of operations) {
    if (topics.length >= MAX_LIVE_TOPICS) break;
    topics.push(`operation:${id}`);
  }
  return topics;
}

/**
 * Gelen kutusu ve duyuruların canlı güncellenmesi (§12.2). Olaylar içerik taşımaz; ilgili sorgular geçersiz kılınır ve
 * REST ile yeniden çekilir. Yoklama (okunmamış sayısı) akış kopuk kaldığında yedek olarak sürer.
 */
export function useLiveUpdates(): void {
  const services = useServices();
  const queryClient = useQueryClient();
  const { context } = useSession();
  const topics = useMemo(() => liveTopics(context), [context]);
  const key = topics.join(',');
  const userId = context?.user.id;
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (!userId) return undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const handle = (event: LiveEvent) => {
      switch (event.type) {
        case 'inbox.changed':
          void queryClient.invalidateQueries({ queryKey: Q.inboxRoot() });
          break;
        case 'announcement.sent':
          void queryClient.invalidateQueries({ queryKey: Q.announcementsRoot() });
          break;
        case 'operation.state.changed':
          void queryClient.invalidateQueries({ queryKey: Q.operationsRoot() });
          break;
        case 'alert.opened':
        case 'alert.recipient.changed':
        case 'alert.cancelled':
        case 'alert.closed':
          // WEB-09/10 canlı: alarm, sayılar ve alıcı tablosu yeniden çekilir.
          void queryClient.invalidateQueries({ queryKey: Q.alertsRoot() });
          break;
        case 'subscription.revoked':
          // İzinler değişti: bağlam yenilenir, konular yeniden hesaplanır ve akış yeniden açılır.
          void queryClient.invalidateQueries({ queryKey: Q.meContext() });
          break;
        case 'session.revoked':
          sessionEvents.endSession('AUTH_SESSION_REVOKED');
          break;
        case 'resync':
          void queryClient.invalidateQueries({ queryKey: Q.alertsRoot() });
          void queryClient.invalidateQueries({ queryKey: Q.inboxRoot() });
          void queryClient.invalidateQueries({ queryKey: Q.announcementsRoot() });
          break;
      }
    };
    const close = services.realtime.connect(key ? key.split(',') : [], handle, () => {
      // Kalıcı kapanış: bir REST çağrısı gerekirse belirteci yeniler (401 → yenileme), ardından yeniden bağlanılır.
      timer = setTimeout(() => {
        void queryClient.invalidateQueries({ queryKey: Q.unreadCount() });
        setGeneration((g) => g + 1);
      }, LIVE_RECONNECT_MS);
    });
    return () => {
      close();
      if (timer) clearTimeout(timer);
    };
  }, [services, queryClient, userId, key, generation]);
}
