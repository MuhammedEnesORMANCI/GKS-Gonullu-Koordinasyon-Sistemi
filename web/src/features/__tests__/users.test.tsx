import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MOCK_IDS } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

async function openUser(name: string) {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: `${name} ayrıntısı` }));
  return { user, dialog: await screen.findByRole('dialog', { name: name }) };
}

/** Kullanıcı yönetimi (ID-19 – ID-25): izdüşüm, arama, işlemler ve kendi hesabı kuralı. */
describe('Kullanıcılar (ID-19 – ID-25)', () => {
  it('ADM tam izdüşüm görür ve adla arar', async () => {
    renderApp({ route: '/kullanicilar', persona: 'ADM' });
    const table = await screen.findByRole('table', { name: 'Kullanıcı listesi' });
    expect(within(table).getByText('E-posta')).toBeInTheDocument();
    expect(within(table).getByText('Zeynep Arslan')).toBeInTheDocument();
    await userEvent.setup().type(screen.getByLabelText('Ara'), 'zeynep');
    await waitFor(() => expect(within(table).queryByText('Ali Kaya')).not.toBeInTheDocument());
    expect(within(table).getByText('Zeynep A.')).toBeInTheDocument();
  });

  it('TEK yalnızca ad ve durum görür; gerekçeyle devre dışı bırakır', async () => {
    const { services } = renderApp({ route: '/kullanicilar', persona: 'TEK' });
    const spy = vi.spyOn(services.users, 'disable');
    expect(await screen.findByText(/Teknik destek görünümü/)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Kullanıcı listesi' });
    expect(within(table).queryByText('E-posta')).not.toBeInTheDocument();
    expect(within(table).queryByText('Zeynep Arslan')).not.toBeInTheDocument();

    const { user, dialog } = await openUser('Zeynep A.');
    expect(within(dialog).queryByRole('button', { name: 'Parolayı sıfırla' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Devre dışı bırak' }));
    const confirm = await screen.findByRole('dialog', { name: 'Hesap devre dışı bırakılsın mı?' });
    await user.type(within(confirm).getByLabelText(/Gerekçe/), 'Kayıp telefon');
    await user.click(within(confirm).getByRole('button', { name: 'Devre dışı bırak' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(MOCK_IDS.volunteer(2), 'Kayıp telefon'));
    expect(await within(dialog).findByText('Hesap devre dışı bırakıldı.')).toBeInTheDocument();
  });

  it('TEK kurum yöneticisinin hesabını kapatamaz: Türkçe 403 iletisi', async () => {
    renderApp({ route: '/kullanicilar', persona: 'TEK' });
    const { user, dialog } = await openUser('Yönetici (örnek)');
    await user.click(within(dialog).getByRole('button', { name: 'Devre dışı bırak' }));
    const confirm = await screen.findByRole('dialog', { name: 'Hesap devre dışı bırakılsın mı?' });
    await user.click(within(confirm).getByRole('button', { name: 'Devre dışı bırak' }));
    expect(await within(confirm).findByRole('alert')).toHaveTextContent('Bu hesapta bu işlemi yapamazsınız');
  });

  it('ADM kendi hesabında işlem düğmesi görmez', async () => {
    renderApp({ route: '/kullanicilar', persona: 'ADM' });
    const { dialog } = await openUser('Yönetici (örnek)');
    expect(await within(dialog).findByText(/Kendi hesabınızda yönetim işlemi yapılamaz/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Devre dışı bırak' })).not.toBeInTheDocument();
  });

  it('ADM: MFA sıfırlama ve parola sıfırlama onaylıdır', async () => {
    const { services } = renderApp({ route: '/kullanicilar', persona: 'ADM' });
    const reset = vi.spyOn(services.users, 'resetPassword');
    const { user, dialog } = await openUser('Koordinatör (örnek)');
    expect(await within(dialog).findByText('Kurulu')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: "MFA'yı sıfırla" }));
    await user.click(within(await screen.findByRole('dialog', { name: /İki adımlı doğrulama/ })).getByRole('button', { name: "MFA'yı sıfırla" }));
    expect(await within(dialog).findByText('İki adımlı doğrulama sıfırlandı.')).toBeInTheDocument();
    // Onay penceresinin kapanış geçişi bitene kadar ayrıntı penceresi erişilebilirlik ağacında gizlidir.
    await user.click(await within(dialog).findByRole('button', { name: 'Parolayı sıfırla' }));
    await user.click(within(await screen.findByRole('dialog', { name: 'Parola sıfırlansın mı?' })).getByRole('button', { name: 'Parolayı sıfırla' }));
    await waitFor(() => expect(reset).toHaveBeenCalledWith(MOCK_IDS.kor));
  });

  it('KOR kullanıcı listesine erişemez ve menüde görmez', async () => {
    renderApp({ route: '/kullanicilar', persona: 'KOR' });
    expect(await screen.findByText(/Kullanıcı listesi yalnızca/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).queryByText('Kullanıcılar')).not.toBeInTheDocument();
  });
});
