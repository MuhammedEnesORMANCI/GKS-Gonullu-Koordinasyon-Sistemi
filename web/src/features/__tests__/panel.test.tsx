import { describe, expect, it } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { panelPageTitle } from '../../app/AppShell';
import { sumCounts } from '../dashboard/HomePage';
import { emitMockLiveEvent } from '../../services/mock/services';
import { MOCK_IDS, mockStore, type MockPersona } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

async function menuLabels(persona: MockPersona): Promise<string[]> {
  renderApp({ route: '/panel', persona });
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
  const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
  return within(nav).getAllByRole('link').map((a) => a.getAttribute('aria-label') ?? a.textContent ?? '');
}

describe('Panel düzeni: menü izinlere göre (ADR-023)', () => {
  it('ADM: yönetim ve kamu duyuruları dahil tüm menü', async () => {
    const labels = await menuLabels('ADM');
    for (const l of ['Özet', 'Operasyonlar', 'Acil Alarmlar', 'Bildirimler', 'Duyurular', 'Kamu Duyuruları', 'Kullanıcılar', 'Roller', 'Denetim Kayıtları', 'Hesabım']) {
      expect(labels, l).toContain(l);
    }
  });

  it('KOR: operasyon ve alarm var; kamu duyuruları, kullanıcılar, roller, denetim yok', async () => {
    const labels = await menuLabels('KOR');
    expect(labels).toEqual(expect.arrayContaining(['Özet', 'Operasyonlar', 'Acil Alarmlar', 'Duyurular']));
    for (const l of ['Kamu Duyuruları', 'Kullanıcılar', 'Roller', 'Denetim Kayıtları']) expect(labels, l).not.toContain(l);
  });

  it('TEK ve UYM: yalnızca rollerine ait öğeler', async () => {
    const tek = await menuLabels('TEK');
    expect(tek).toContain('Kullanıcılar');
    for (const l of ['Operasyonlar', 'Acil Alarmlar', 'Kamu Duyuruları', 'Roller']) expect(tek, l).not.toContain(l);
  });

  it('UYM: denetim kayıtları, operasyon yok', async () => {
    const uym = await menuLabels('UYM');
    expect(uym).toContain('Denetim Kayıtları');
    expect(uym).not.toContain('Operasyonlar');
    expect(uym).not.toContain('Kamu Duyuruları');
  });

  it('seçili operasyon menüsü: görevler, gruplar, konumlar', async () => {
    renderApp({ route: `/panel/operasyonlar/${MOCK_IDS.op1}`, persona: 'KOR' });
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).getByRole('link', { name: 'Görevler' })).toHaveAttribute('href', `/panel/operasyonlar/${MOCK_IDS.op1}/gorevler`);
    expect(within(nav).getByRole('link', { name: 'Gruplar' })).toHaveAttribute('href', `/panel/operasyonlar/${MOCK_IDS.op1}/gruplar`);
    expect(within(nav).getByRole('link', { name: 'Konumlar' })).toHaveAttribute('href', `/panel/operasyonlar/${MOCK_IDS.op1}/konum`);
  });

  it('üst çubuk: sayfa başlığı, kullanıcı menüsünde Hesabım, halka açık site ve Çıkış', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel/operasyonlar', persona: 'KOR' });
    await screen.findByRole('heading', { name: 'Operasyonlar' });
    await user.click(screen.getByRole('button', { name: 'Kullanıcı menüsü' }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: /Hesabım/ })).toHaveAttribute('href', '/panel/hesabim');
    expect(within(menu).getByRole('menuitem', { name: /Halka açık site/ })).toHaveAttribute('href', '/');
    expect(within(menu).getByRole('menuitem', { name: /Çıkış/ })).toBeInTheDocument();
  });

  it('sayfa başlığı eşlemesi', () => {
    expect(panelPageTitle('/panel')).toBe('Özet');
    expect(panelPageTitle('/panel/operasyonlar/abc/gorevler/x')).toBe('Görevler');
    expect(panelPageTitle('/panel/alarmlar/x')).toBe('Acil Alarmlar');
    expect(panelPageTitle('/panel/kamu-duyurulari')).toBe('Kamu Duyuruları');
  });
});

describe('Özet (WEB-02)', () => {
  it('ADM: açık alarm kırmızı bandı (tıklanabilir), kartlar, son denetim olayları, sistem durumu', async () => {
    renderApp({ route: '/panel', persona: 'ADM' });
    const band = await screen.findByRole('alert');
    expect(band).toHaveTextContent(/açık acil alarm/);
    expect(within(band).getByRole('link', { name: 'Alarmlara git' })).toHaveAttribute('href', '/panel/alarmlar');
    expect(await screen.findByRole('link', { name: /^Aktif operasyon: \d+/ })).toHaveAttribute('href', '/panel/operasyonlar');
    expect(screen.getByRole('link', { name: /^Açık acil alarm: \d+/ })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Son denetim olayları' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Sistem durumu' })).toBeInTheDocument();
    expect(await screen.findByText('Telefon bildirimi (FCM) kapalı')).toBeInTheDocument();
  });

  it('KOR: denetim kartı yok; TEK: operasyon ve alarm kartı yok', async () => {
    const { unmount } = renderApp({ route: '/panel', persona: 'KOR' });
    expect(await screen.findByRole('link', { name: /^Aktif operasyon/ })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Son denetim olayları' })).not.toBeInTheDocument();
    unmount();
    renderApp({ route: '/panel', persona: 'TEK' });
    expect(await screen.findByRole('heading', { name: 'Sistem durumu' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Aktif operasyon/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /^Açık acil alarm/ })).not.toBeInTheDocument();
  });

  it('canlı akış: alarm kapanınca bant kaybolur', async () => {
    renderApp({ route: '/panel', persona: 'KOR' });
    expect(await screen.findByRole('alert')).toHaveTextContent(/açık acil alarm/);
    for (const a of mockStore.alerts.values()) a.view.state = 'CLOSED';
    act(() => emitMockLiveEvent({ type: 'alert.closed', data: { topic: `operation:${MOCK_IDS.op1}` } }));
    await waitFor(() => expect(screen.queryByText(/açık acil alarm/)).not.toBeInTheDocument());
  });

  it('yanıt sayıları toplanır', () => {
    const c = { total: 2, responded: 1, pending: 1, unanswered: 0, failed: 0, needHelp: 1 };
    expect(sumCounts([c, c])).toEqual({ total: 4, responded: 2, pending: 2, unanswered: 0, failed: 0, needHelp: 2 });
  });
});
