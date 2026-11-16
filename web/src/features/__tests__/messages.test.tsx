import { describe, expect, it } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { channelTitle } from '../messaging/MessagesPage';
import { emitMockLiveEvent } from '../../services/mock/services';
import { MOCK_IDS, mockStore } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

describe('Mesajlar (ADR-024)', () => {
  it('KOR: kanal listesi okunmamış sayılarıyla; kanal açılınca mesajlar; gönderilen mesaj görünür', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel/mesajlar', persona: 'KOR' });
    const nav = await screen.findByRole('navigation', { name: 'Kanallar' });
    expect(within(nav).getByText('Operasyon (tüm katılımcılar)')).toBeInTheDocument();
    expect(within(nav).getByText('Koordinasyon')).toBeInTheDocument();
    expect(within(nav).getAllByLabelText('1 okunmamış').length).toBeGreaterThan(0);
    expect(await screen.findByText('Anlaşıldı, yoldayım.')).toBeInTheDocument();

    await user.type(screen.getByRole('textbox', { name: 'Mesaj' }), 'Su ve battaniye yolda');
    await user.click(screen.getByRole('button', { name: 'Gönder' }));
    expect(await screen.findByText('Su ve battaniye yolda')).toBeInTheDocument();
    expect(mockStore.messages.some((m) => m.body === 'Su ve battaniye yolda')).toBe(true);
  });

  it('moderasyon: başkasının mesajı onayla gizlenir, "yönetici tarafından kaldırıldı" görünür', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel/mesajlar/0192f000-7b00-7000-8000-000000000001', persona: 'KOR' });
    await screen.findByText('Anlaşıldı, yoldayım.');
    await user.click(screen.getByRole('button', { name: 'Gizle' }));
    const dialog = await screen.findByRole('dialog', { name: 'Mesaj gizlensin mi?' });
    await user.click(within(dialog).getByRole('button', { name: 'Gizle' }));
    expect(await screen.findByText('Yönetici tarafından kaldırıldı')).toBeInTheDocument();
  });

  it('canlı akış: yeni mesaj olayı kanal listesini tazeler', async () => {
    renderApp({ route: '/panel/mesajlar/0192f000-7b00-7000-8000-000000000003', persona: 'KOR' });
    await screen.findByText('Ekip hazır.');
    mockStore.messages.push({ id: '0192f000-7c00-7000-8000-000000000099', channelId: '0192f000-7b00-7000-8000-000000000003', kind: 'USER',
      senderId: MOCK_IDS.volunteer(3), body: 'Yeni gelen mesaj', createdAt: new Date().toISOString() });
    act(() => emitMockLiveEvent({ type: 'message.created', data: { topic: `user:${MOCK_IDS.kor}` } }));
    await waitFor(() => expect(screen.getByText('Yeni gelen mesaj')).toBeInTheDocument());
  });

  it('akış bağlanamazsa (vekil/tünel) açık sohbet yoklamayla birkaç saniyede güncellenir', async () => {
    const realtime = { connect: () => () => undefined };   // hiç bağlanmayan akış
    renderApp({ route: '/panel/mesajlar/0192f000-7b00-7000-8000-000000000003', persona: 'KOR', overrides: { realtime } });
    await screen.findByText('Ekip hazır.');
    mockStore.messages.push({ id: '0192f000-7c00-7000-8000-000000000098', channelId: '0192f000-7b00-7000-8000-000000000003', kind: 'USER',
      senderId: MOCK_IDS.volunteer(3), body: 'Yoklamayla gelen mesaj', createdAt: new Date().toISOString() });
    await waitFor(() => expect(screen.getByText('Yoklamayla gelen mesaj')).toBeInTheDocument(), { timeout: 8_000 });
  }, 12_000);

  it('menüde Mesajlar yalnızca ADM/KOR; TEK görmez', async () => {
    const { unmount } = renderApp({ route: '/panel', persona: 'KOR' });
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    expect(within(await screen.findByRole('navigation', { name: 'Ana menü' })).getByRole('link', { name: 'Mesajlar' })).toBeInTheDocument();
    unmount();
    renderApp({ route: '/panel', persona: 'TEK' });
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    expect(within(await screen.findByRole('navigation', { name: 'Ana menü' })).queryByRole('link', { name: 'Mesajlar' })).not.toBeInTheDocument();
  });

  it('kanal başlıkları', () => {
    const base = { id: 'x', operationId: MOCK_IDS.op1, operationName: 'Op', adminsOnly: false, readOnly: false, canWrite: true, canModerate: false, muted: false, unread: 0 };
    expect(channelTitle({ ...base, kind: 'GROUP', groupName: 'Arama' })).toBe('Grup: Arama');
    expect(channelTitle({ ...base, kind: 'COORDINATION' })).toBe('Koordinasyon');
  });
});
