import { describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import { sessionEvents } from '../../api/sessionEvents';
import { liveTopics, MAX_LIVE_TOPICS } from '../../app/live';
import type { NotificationService } from '../../services/contracts';
import { emitMockLiveEvent, mockLiveTopics } from '../../services/mock/services';
import { MOCK_IDS } from '../../services/mock/store';
import type { InboxItem, MeContext } from '../../services/types';
import { renderApp } from '../../test/renderApp';

const item: InboxItem = {
  id: 'd9',
  category: 'ANNOUNCEMENT',
  kind: 'ANNOUNCEMENT',
  priority: 'NORMAL',
  title: 'Toplanma saati değişti',
  body: 'Saat 15:00',
  createdAt: '2026-11-16T09:00:00Z',
};

function liveInbox(items: InboxItem[]): NotificationService {
  return {
    list: vi.fn(async () => ({ items: [...items] })),
    get: vi.fn(async (id: string) => items.find((i) => i.id === id)!),
    markRead: vi.fn(async () => undefined),
    unreadCount: vi.fn(async () => items.filter((i) => !i.readAt).length),
  };
}

describe('Canlı güncellemeler (RT-01)', () => {
  it('konular etkin izinlerden: kurum geneli, duyuru gönderilebilen operasyonlar, en çok 10', () => {
    const ops = Object.fromEntries(Array.from({ length: 12 }, (_, i) => [`op-${String(i).padStart(2, '0')}`, ['announcement.send.group']]));
    const context = {
      user: { id: 'u1', displayName: 'Yönetici' },
      operations: [{ id: 'op-11', name: 'Kendi', state: 'ACTIVE', myRoles: ['KOR'] }],
      permissions: { organization: ['announcement.send.organization'], operations: { ...ops, 'op-x': ['task.view'] }, groups: {} },
    } as unknown as MeContext;
    const topics = liveTopics(context);
    expect(topics).toHaveLength(MAX_LIVE_TOPICS);
    expect(topics[0]).toBe('organization');
    expect(topics[1]).toBe('operation:op-11');   // katıldığı operasyon önce
    expect(topics).not.toContain('operation:op-x');
    expect(liveTopics(undefined)).toEqual([]);
  });

  it('gelen kutusu olayı listeyi ve okunmamış sayısını içerik almadan yeniler', async () => {
    const items: InboxItem[] = [];
    const notifications = liveInbox(items);
    renderApp({ route: '/bildirimler', overrides: { notifications } });
    expect(await screen.findByText('Henüz bildiriminiz yok.')).toBeInTheDocument();
    await waitFor(() => expect(mockLiveTopics().length).toBeGreaterThan(0));
    const [topics = []] = mockLiveTopics();
    expect(topics.some((t) => t.startsWith('operation:'))).toBe(true);
    expect(topics).not.toContain('organization');   // KOR kurum geneli duyuru göndermez

    items.push(item);
    act(() => emitMockLiveEvent({ type: 'inbox.changed', data: { topic: `user:${MOCK_IDS.kor}`, change: 'CREATED' } }));
    expect(await screen.findByText('Toplanma saati değişti')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Bildirimler, 1 okunmamış' })).toBeInTheDocument();
  });

  it('ADM kurum geneli konuya abone olur; oturum iptali oturumu sonlandırır', async () => {
    const end = vi.spyOn(sessionEvents, 'endSession');
    renderApp({ route: '/panel', persona: 'ADM' });
    await waitFor(() => expect(mockLiveTopics().some((topics) => topics.includes('organization'))).toBe(true));
    act(() => emitMockLiveEvent({ type: 'session.revoked', data: { topic: `user:${MOCK_IDS.adm}` } }));
    expect(end).toHaveBeenCalledWith('AUTH_SESSION_REVOKED');
    end.mockRestore();
  });
});
