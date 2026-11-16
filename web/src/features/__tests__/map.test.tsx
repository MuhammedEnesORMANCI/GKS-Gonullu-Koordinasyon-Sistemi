import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { circlePolygon, distanceM, parseCoordinate } from '../../components/map/geo';
import { MAP_CONFIG, OSM_DEV_TILE_URL } from '../../config/map';
import { parseArea } from '../map/AreaPicker';
import { anchor, MAP_COLORS, operationMarkers } from '../map/mapModel';
import { MOCK_IDS, mockStore } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

describe('Harita (ADR-025)', () => {
  it('koordinat doğrulaması ve bölge ayrıştırma', () => {
    expect(parseCoordinate('39,92')).toBe(39.92);
    expect(parseCoordinate('')).toBeUndefined();
    expect(Number.isNaN(parseCoordinate('abc'))).toBe(true);
    expect(parseArea({ lat: '', lon: '', radius: '' })).toEqual({ area: null, errors: {} });
    expect(parseArea({ lat: '95', lon: '30', radius: '' }).errors['area.lat']).toMatch(/−90 ile 90/);
    expect(parseArea({ lat: '40', lon: '200', radius: '' }).errors['area.lon']).toMatch(/−180 ile 180/);
    expect(parseArea({ lat: '40', lon: '', radius: '' }).errors['area.lat']).toMatch(/birlikte/);
    expect(parseArea({ lat: '40', lon: '29', radius: '10' }).errors['area.radiusM']).toBeDefined();
    expect(parseArea({ lat: '', lon: '', radius: '500' }).errors['area.radiusM']).toBeDefined();
    expect(parseArea({ lat: '40.5', lon: '29,1', radius: '1500' })).toEqual({ area: { lat: 40.5, lon: 29.1, radiusM: 1500 }, errors: {} });
  });

  it('yarıçap çokgeni kapalı halka ve kenar noktaları merkezden yarıçap uzaklığında', () => {
    const ring = circlePolygon({ lat: 40, lon: 29 }, 2000, 32);
    expect(ring).toHaveLength(33);
    expect(ring[0]).toEqual(ring[32]);
    for (const [lon, lat] of ring) expect(Math.abs(distanceM({ lat: 40, lon: 29 }, { lat, lon }) - 2000)).toBeLessThan(1);
  });

  it('işaret rengi duruma göre, açık alarmda kırmızı; konumsuz operasyon haritada yok', () => {
    const base = { name: 'Op', participantCount: 0, meetingPoints: [] };
    const markers = operationMarkers(
      [
        { ...base, id: 'a', state: 'ACTIVE', area: { lat: 40, lon: 29 } },
        { ...base, id: 'b', state: 'PLANNED', area: { lat: 41, lon: 29 }, openAlert: { id: 'x', sentAt: '2026-11-16T10:00:00Z', counts: { total: 1, responded: 0, pending: 1, unanswered: 0, failed: 0, needHelp: 0 } } },
        { ...base, id: 'c', state: 'ACTIVE' },
      ],
      (s) => s,
    );
    expect(markers.map((m) => [m.id, m.color])).toEqual([
      ['a', MAP_COLORS.ACTIVE],
      ['b', MAP_COLORS.ALERT],
    ]);
    expect(markers[1]?.label).toContain('AÇIK ALARM');
    expect(anchor({ ...base, id: 'd', state: 'ACTIVE', meetingPoints: [{ groupId: 'g', groupName: 'G', lat: 1, lon: 2 }] })).toEqual({ lat: 1, lon: 2 });
  });

  it('karo adresi yapılandırmadan; varsayılan OSM ve Türkçe atıf', () => {
    expect(MAP_CONFIG.tileUrl).toBe(OSM_DEV_TILE_URL);
    expect(MAP_CONFIG.attribution).toBe('© OpenStreetMap katkıcıları');
  });

  it('ADM Operasyon Haritası: haritasız ortamda metin yedeği, Planlandı filtresi, seçilince özet kartı', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel/harita', persona: 'ADM' });
    expect(await screen.findByText(/Harita bu tarayıcıda gösterilemiyor/)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Haritadaki operasyonlar' });
    expect(within(table).getByText('Deprem Arama Kurtarma — Merkez')).toBeInTheDocument();
    expect(within(table).queryByText('Sel Bölgesi Lojistik')).not.toBeInTheDocument();
    expect(within(table).getByText('39.92000, 32.85400')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Planlanan operasyonları da göster' }));
    expect(await within(await screen.findByRole('table', { name: 'Haritadaki operasyonlar' })).findByText('Sel Bölgesi Lojistik')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Deprem Arama Kurtarma — Merkez' }));
    const card = await screen.findByRole('region', { name: 'Seçili operasyon: Deprem Arama Kurtarma — Merkez' });
    expect(within(card).getByText('Katılımcı: 6')).toBeInTheDocument();
    expect(within(card).getByText(/Açık alarm \(/)).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: 'Operasyona git' })).toHaveAttribute('href', `/panel/operasyonlar/${MOCK_IDS.op1}`);
  });

  it('TEK haritayı göremez', async () => {
    renderApp({ route: '/panel/harita', persona: 'TEK' });
    expect(await screen.findByText('Harita yalnızca koordinatörler ve kurum yöneticileri içindir.')).toBeInTheDocument();
  });

  it('operasyon sayfası: bölge ve buluşma noktaları metin olarak; konumlar yalnızca istenince', async () => {
    const user = userEvent.setup();
    renderApp({ route: `/panel/operasyonlar/${MOCK_IDS.op1}`, persona: 'KOR' });
    const region = await screen.findByRole('region', { name: 'Operasyon haritası' });
    expect(await within(region).findByText('Buluşma noktası — Arama Ekibi A')).toBeInTheDocument();
    expect(within(region).getByText('39.92000, 32.85400 · yarıçap 2500 m')).toBeInTheDocument();
    const toggle = within(region).getByRole('checkbox', { name: /Gönüllülerin son konumlarını göster/ });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    expect(await within(region).findByText(/kişinin son konumu var/)).toBeInTheDocument();
  });

  it('düzenleme: geçersiz bölge kaydedilmez, geçerli bölge kaydedilir', async () => {
    const user = userEvent.setup();
    renderApp({ route: `/panel/operasyonlar/${MOCK_IDS.op2}`, persona: 'KOR' });
    await user.click(await screen.findByRole('button', { name: 'Düzenle' }));
    const dialog = await screen.findByRole('dialog', { name: 'Operasyonu düzenle' });
    const lat = within(dialog).getByRole('textbox', { name: 'Merkez enlem' });
    await user.clear(lat);
    await user.type(lat, '95');
    await user.tab();
    expect(await within(dialog).findByText('Enlem −90 ile 90 arasında olmalı.')).toBeInTheDocument();
    await user.clear(lat);
    await user.type(lat, '41.05');
    await user.type(within(dialog).getByRole('textbox', { name: 'Yarıçap (m)' }), '800');
    await user.click(within(dialog).getByRole('button', { name: 'Kaydet' }));
    await screen.findByRole('region', { name: 'Operasyon haritası' });
    expect(mockStore.operations.get(MOCK_IDS.op2)?.area).toEqual({ lat: 41.05, lon: 28.97, radiusM: 800 });
  });
});
