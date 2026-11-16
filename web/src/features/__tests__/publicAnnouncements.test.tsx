import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockStore } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

describe('Kamu duyuruları yönetimi (ADR-023)', () => {
  it('ADM: menüde görünür; taslak oluşturur, onayla yayımlar; halka açık sitede görünür, sonra yayından kaldırır', async () => {
    const user = userEvent.setup();
    const { unmount } = renderApp({ route: '/panel/kamu-duyurulari', persona: 'ADM' });
    expect(await screen.findByRole('heading', { name: 'Kamu duyuruları' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Kamu duyuruları' });
    expect(within(table).getByText('Taslak kamu duyurusu')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Yeni duyuru' }));
    await user.type(screen.getByLabelText(/Başlık/), 'Tatbikat duyurusu');
    await user.type(screen.getByLabelText(/Metin/), 'Hafta sonu tatbikat yapılacaktır.');
    await user.click(screen.getByRole('button', { name: 'Taslak olarak kaydet' }));
    expect(await screen.findByText('Taslak oluşturuldu.')).toBeInTheDocument();

    const row = (await screen.findByText('Tatbikat duyurusu')).closest('tr')!;
    await user.click(within(row).getByRole('button', { name: 'Yayımla' }));
    const dialog = await screen.findByRole('dialog', { name: 'Duyuru yayımlansın mı?' });
    await user.click(within(dialog).getByRole('button', { name: 'Yayımla' }));
    expect(await screen.findByText('Duyuru yayımlandı.')).toBeInTheDocument();
    expect(mockStore.publicAnnouncements.find((a) => a.title === 'Tatbikat duyurusu')?.state).toBe('PUBLISHED');
    unmount();

    const pub = renderApp({ route: '/duyurular', loggedIn: false });
    expect(await screen.findByText('Tatbikat duyurusu')).toBeInTheDocument();
    pub.unmount();

    renderApp({ route: '/panel/kamu-duyurulari', persona: 'ADM' });
    const row2 = (await screen.findByText('Tatbikat duyurusu')).closest('tr')!;
    await user.click(within(row2).getByRole('button', { name: 'Yayından kaldır' }));
    const dialog2 = await screen.findByRole('dialog', { name: 'Duyuru yayından kaldırılsın mı?' });
    await user.click(within(dialog2).getByRole('button', { name: 'Yayından kaldır' }));
    await waitFor(() => expect(mockStore.publicAnnouncements.find((a) => a.title === 'Tatbikat duyurusu')?.state).toBe('DRAFT'));
  });

  it('doğrulama hataları alanda gösterilir', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel/kamu-duyurulari', persona: 'ADM' });
    await user.click(await screen.findByRole('button', { name: 'Yeni duyuru' }));
    await user.type(screen.getByLabelText(/Başlık/), 'ab');
    await user.type(screen.getByLabelText(/Metin/), 'x');
    await user.click(screen.getByRole('button', { name: 'Taslak olarak kaydet' }));
    expect(await screen.findByText('Çok kısa.')).toBeInTheDocument();
  });

  it('KOR: sayfa yetkisiz uyarısı', async () => {
    renderApp({ route: '/panel/kamu-duyurulari', persona: 'KOR' });
    expect(await screen.findByText('Bu sayfa yalnızca kurum yöneticileri (ADM) içindir.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Yeni duyuru' })).not.toBeInTheDocument();
  });
});
