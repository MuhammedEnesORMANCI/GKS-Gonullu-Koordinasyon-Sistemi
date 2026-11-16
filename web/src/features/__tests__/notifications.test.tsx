import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AnnouncementService, NotificationService } from '../../services/contracts';
import { MOCK_IDS } from '../../services/mock/store';
import type { AnnouncementView, InboxItem } from '../../services/types';
import { renderApp } from '../../test/renderApp';

const unreadItem: InboxItem = {
  id: 'd1',
  category: 'TASK',
  kind: 'TASK_ASSIGNED',
  priority: 'HIGH',
  title: 'Yeni görev: Su dağıtımı',
  body: 'Deprem müdahalesi operasyonunda size görev atandı.',
  createdAt: '2026-11-15T14:00:00Z',
  link: { type: 'TASK', id: 't1' },
};

function inboxWith(items: InboxItem[]): NotificationService {
  return {
    list: vi.fn(async () => ({ items })),
    get: vi.fn(async (id: string) => items.find((i) => i.id === id)!),
    markRead: vi.fn(async () => undefined),
    unreadCount: vi.fn(async () => items.filter((i) => !i.readAt).length),
  };
}

describe('Bildirimler (NT-04/05/08)', () => {
  it('okunmamış sayısı zilde görünür; açınca okundu işaretlenir', async () => {
    const notifications = inboxWith([unreadItem, { ...unreadItem, id: 'd2', title: 'Eski', readAt: '2026-11-15T15:00:00Z' }]);
    renderApp({ route: '/bildirimler', overrides: { notifications } });
    expect(await screen.findByRole('link', { name: 'Bildirimler, 1 okunmamış' })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(await screen.findByText('Yeni görev: Su dağıtımı'));
    await waitFor(() => expect(notifications.markRead).toHaveBeenCalledWith('d1'));
    await user.click(screen.getByText('Eski'));
    expect(notifications.markRead).toHaveBeenCalledTimes(1);   // okunmuş kayıt yeniden işaretlenmez
  });

  it('boş gelen kutusu', async () => {
    renderApp({ route: '/bildirimler', overrides: { notifications: inboxWith([]) } });
    expect(await screen.findByText('Henüz bildiriminiz yok.')).toBeInTheDocument();
  });
});

describe('Duyurular (NT-01/02/03)', () => {
  function announcementsWith(recipientCount: number): AnnouncementService {
    const view: AnnouncementView = {
      id: 'a1', title: 'Toplantı', createdAt: '2026-11-15T14:00:00Z', recipientCount,
      delivery: { sent: recipientCount, failed: 0, read: 0 },
    };
    return {
      preview: vi.fn(async () => ({ recipientCount, recipients: [{ id: 'u1', displayName: 'Ali K.' }], confirmationRequired: recipientCount > 50 })),
      create: vi.fn(async () => view),
      list: vi.fn(async () => ({ items: [] })),
      get: vi.fn(async () => view),
    };
  }

  async function fill() {
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/Başlık/), 'Toplantı');
    await user.type(screen.getByLabelText(/Duyuru metni/), "Saat 18'de çadırda.");
    return user;
  }

  it('KOR: önizlemeden sonra anahtarla gönderir', async () => {
    const announcements = announcementsWith(2);
    renderApp({ route: '/panel/duyurular', persona: 'KOR', overrides: { announcements } });
    const user = await fill();
    const send = screen.getByRole('button', { name: 'Duyuruyu gönder' });
    expect(send).toBeDisabled();   // önizleme olmadan gönderilmez
    await user.click(screen.getByRole('button', { name: 'Alıcıları önizle' }));
    expect(await screen.findByText(/kişiye gidecek/)).toBeInTheDocument();
    await user.click(send);
    await waitFor(() => expect(announcements.create).toHaveBeenCalled());
    const [request, key] = vi.mocked(announcements.create).mock.calls[0]!;
    expect(request).toMatchObject({ operationId: MOCK_IDS.op1, title: 'Toplantı', body: "Saat 18'de çadırda." });
    expect(key).toMatch(/[0-9a-f-]{36}/);
    expect(await screen.findByText('"Toplantı" 2 kişiye gönderildi.')).toBeInTheDocument();
  });

  it('50 kişiden fazlasında açık onay ister (AC-11.3)', async () => {
    const announcements = announcementsWith(120);
    renderApp({ route: '/panel/duyurular', persona: 'KOR', overrides: { announcements } });
    const user = await fill();
    await user.click(screen.getByRole('button', { name: 'Alıcıları önizle' }));
    const send = screen.getByRole('button', { name: 'Duyuruyu gönder' });
    await waitFor(() => expect(screen.getByLabelText('120 kişiye gönderileceğini onaylıyorum.')).toBeInTheDocument());
    expect(send).toBeDisabled();
    await user.click(screen.getByLabelText('120 kişiye gönderileceğini onaylıyorum.'));
    await user.click(send);
    await waitFor(() => expect(vi.mocked(announcements.create).mock.calls[0]?.[0]).toMatchObject({ confirm: true }));
  });

  it('izni olmayan rolde menüde görünmez', async () => {
    renderApp({ route: '/panel', persona: 'TEK' });
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).queryByText('Duyurular')).not.toBeInTheDocument();
    expect(within(nav).getByText('Bildirimler')).toBeInTheDocument();
  });
});
