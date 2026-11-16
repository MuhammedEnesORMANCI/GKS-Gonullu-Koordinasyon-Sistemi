import { describe, expect, it } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { sessionEvents } from '../../api/sessionEvents';
import { LEGACY_PANEL_PATHS } from '../../app/App';
import { isPanelPath } from '../../app/session';
import type { Services } from '../../services/contracts';
import { createServices } from '../../services/registry';
import { MOCK_PRESET } from '../../config/services';
import { mockStore } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';
import hazards from '../../content/hazards.json';
import guide from '../../content/guide.json';
import emergency from '../../content/emergency.json';
import site from '../../content/site.json';

const PUBLIC_ROUTES = ['/', '/afet/deprem', '/afet/sel', '/rehber', '/ne-yapmali', '/duyurular', '/gonulluluk', '/iletisim'];

/** İç veri servisleri: halka açık sayfalarda hiç çağrılmamalı (çağrılırsa test düşer). */
function forbiddenInternalServices(): { overrides: Partial<Services>; calls: string[] } {
  const calls: string[] = [];
  const base = createServices(MOCK_PRESET);
  const internal: (keyof Services)[] = [
    'operations', 'operationList', 'participants', 'groups', 'tasks', 'invitations', 'notifications', 'announcements',
    'roleAssignments', 'users', 'audit', 'alerts', 'locations', 'realtime',
  ];
  const overrides: Partial<Services> = {};
  for (const key of internal) {
    overrides[key] = new Proxy(base[key] as object, {
      get: (target, prop) => {
        const value = Reflect.get(target, prop);
        if (typeof value !== 'function') return value;
        return (...args: unknown[]) => {
          calls.push(`${String(key)}.${String(prop)}`);
          return (value as (...a: unknown[]) => unknown).apply(target, args);
        };
      },
    }) as never;
  }
  return { overrides, calls };
}

describe('Halka açık site', () => {
  it('ana sayfa: üst menü, hero düğmeleri, 6 afet kartı, hızlı erişim, yalnızca yayındaki kamu duyuruları; Giriş', async () => {
    renderApp({ route: '/', loggedIn: false });
    expect(await screen.findByRole('heading', { level: 1, name: site.hero.title })).toBeInTheDocument();
    const menu = screen.getByRole('navigation', { name: 'Site menüsü' });
    for (const label of ['Afet Bilgisi', 'Hazırlık Rehberi', 'Duyurular', 'Gönüllülük', 'İletişim']) {
      expect(within(menu).getByRole('link', { name: label })).toBeInTheDocument();
    }
    expect(screen.getAllByRole('link', { name: 'Afet Anında Ne Yapmalı?' })[0]).toHaveAttribute('href', '/ne-yapmali');
    expect(screen.getAllByRole('link', { name: 'Hazırlık Rehberi' }).some((a) => a.getAttribute('href') === '/rehber')).toBe(true);
    for (const h of hazards.hazards) {
      expect(screen.getByRole('link', { name: new RegExp(h.title) })).toHaveAttribute('href', `/afet/${h.slug}`);
    }
    expect(screen.getByRole('heading', { name: 'Hızlı Erişim' })).toBeInTheDocument();
    expect(await screen.findByText('Örnek kamu duyurusu: toplanma alanları')).toBeInTheDocument();
    expect(screen.queryByText('Taslak kamu duyurusu')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Giriş' })[0]).toHaveAttribute('href', '/giris');
    expect(screen.queryByRole('link', { name: 'Panele git' })).not.toBeInTheDocument();
  });

  it('giriş yapmış kullanıcı üst menüde "Panele git" görür', async () => {
    renderApp({ route: '/', persona: 'ADM' });
    expect((await screen.findAllByRole('link', { name: 'Panele git' }))[0]).toHaveAttribute('href', '/panel');
  });

  it.each(PUBLIC_ROUTES)('%s: iç veri yok, iç servis çağrısı yok (ADM oturumu açıkken bile)', async (route) => {
    const { overrides, calls } = forbiddenInternalServices();
    renderApp({ route, persona: 'ADM', overrides });
    await screen.findAllByRole('link', { name: 'Panele git' });
    await act(async () => new Promise((r) => setTimeout(r, 20)));
    expect(calls).toEqual([]);
    const text = document.body.textContent ?? '';
    for (const op of mockStore.operations.values()) expect(text).not.toContain(op.name);
    for (const u of mockStore.users.values()) expect(text).not.toContain(u.displayName);
    expect(text).not.toContain('Arama Ekibi A');
  });

  it('afet sayfası: öncesinde / sırasında / sonrasında ve rehber bağlantıları; bilinmeyen tür 404', async () => {
    const { unmount } = renderApp({ route: '/afet/deprem', loggedIn: false });
    expect(await screen.findByRole('heading', { level: 1, name: 'Deprem' })).toBeInTheDocument();
    for (const phase of ['Öncesinde', 'Sırasında', 'Sonrasında']) {
      expect(screen.getByRole('heading', { name: phase })).toBeInTheDocument();
    }
    expect(screen.getByText(hazards.hazards[0]!.during[1]!)).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Rehberde aç' }).map((a) => a.getAttribute('href'))).toEqual(
      guide.sections.map((s) => `/rehber#${s.id}`),
    );
    expect(document.title).toContain('Deprem');
    expect(document.head.querySelector('meta[name="description"]')?.getAttribute('content')).toBe(hazards.hazards[0]!.summary);
    unmount();
    renderApp({ route: '/afet/yok-boyle', loggedIn: false });
    expect(await screen.findByRole('heading', { name: 'Sayfa bulunamadı' })).toBeInTheDocument();
  });

  it('rehber, ne yapmalı, gönüllülük (kayıt formu yok), iletişim (yalnızca 112)', async () => {
    let r = renderApp({ route: '/rehber', loggedIn: false });
    for (const s of guide.sections) expect(await screen.findByRole('heading', { name: s.title })).toBeInTheDocument();
    expect(document.getElementById('acil-durum-cantasi')).not.toBeNull();
    r.unmount();

    r = renderApp({ route: '/ne-yapmali', loggedIn: false });
    expect(await screen.findByText(emergency.callout)).toBeInTheDocument();
    expect(screen.getAllByRole('listitem').length).toBeGreaterThanOrEqual(emergency.steps.length);
    r.unmount();

    r = renderApp({ route: '/gonulluluk', loggedIn: false });
    expect(await screen.findByText(site.volunteer.paragraphs[1]!)).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    r.unmount();

    renderApp({ route: '/iletisim', loggedIn: false });
    const main = await screen.findByRole('main');
    await screen.findByText('Acil Çağrı Merkezi');
    const numbers = (main.textContent ?? '').match(/\d{3,}/g) ?? [];
    expect(numbers.every((n) => n === '112')).toBe(true);
  });

  it('bilinmeyen adres halka açık 404; anonim ziyaretçi oturum olayında yerinde kalır', async () => {
    renderApp({ route: '/olmayan-sayfa', loggedIn: false });
    expect(await screen.findByRole('heading', { name: 'Sayfa bulunamadı' })).toBeInTheDocument();
    act(() => sessionEvents.endSession('AUTH_UNAUTHENTICATED'));
    expect(screen.getByRole('heading', { name: 'Sayfa bulunamadı' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Yönetici girişi' })).not.toBeInTheDocument();
  });

  it('içerik dosyaları "Yetkili kaynaklarla doğrulanmalı" notunu taşır; uydurma numara yok', () => {
    for (const file of [hazards, guide, emergency, site]) {
      expect(file._dogrulama).toMatch(/^Yetkili kaynaklarla doğrulanmalı/);
      const numbers = JSON.stringify(file).match(/\b\d{3,}\b/g) ?? [];
      expect(numbers.every((n) => n === '112')).toBe(true);
    }
  });
});

describe('Panel adresleri', () => {
  function Where() {
    const l = useLocation();
    return <p data-testid="where">{l.pathname + l.search}</p>;
  }

  it.each([
    ['/operasyonlar', '/panel/operasyonlar'],
    ['/operasyonlar/abc/gorevler/def', '/panel/operasyonlar/abc/gorevler/def'],
    ['/davetler?kapsam=op1', '/panel/davetler?kapsam=op1'],
    ['/hesabim', '/panel/hesabim'],
    ['/bildirimler', '/panel/bildirimler'],
    ['/roller?rol=KOR', '/panel/roller?rol=KOR'],
    ['/kullanicilar', '/panel/kullanicilar'],
    ['/denetim', '/panel/denetim'],
    ['/alarmlar/x1', '/panel/alarmlar/x1'],
  ])('eski %s → %s (sorgu korunur)', async (from, to) => {
    const { legacyPanelRoutes } = await import('../../app/App');
    render(
      <MemoryRouter initialEntries={[from]}>
        <Routes>
          {legacyPanelRoutes()}
          <Route path="/panel/*" element={<Where />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId('where')).toHaveTextContent(to);
  });

  it('eski adresle açılan panel sayfası çalışır (ör. /operasyonlar → Operasyonlar)', async () => {
    renderApp({ route: '/operasyonlar', persona: 'KOR' });
    expect(await screen.findByRole('heading', { name: 'Operasyonlar' })).toBeInTheDocument();
  });

  it('isPanelPath ve yönlendirme listesi', () => {
    expect(isPanelPath('/panel')).toBe(true);
    expect(isPanelPath('/panel/operasyonlar')).toBe(true);
    expect(isPanelPath('/paneller')).toBe(false);
    expect(isPanelPath('/')).toBe(false);
    expect(LEGACY_PANEL_PATHS).not.toContain('/duyurular');
  });

  it('panel kökü anonim kullanıcıyı girişe götürür', async () => {
    renderApp({ route: '/panel', loggedIn: false });
    expect(await screen.findByRole('heading', { name: 'Yönetici girişi' })).toBeInTheDocument();
  });
});
