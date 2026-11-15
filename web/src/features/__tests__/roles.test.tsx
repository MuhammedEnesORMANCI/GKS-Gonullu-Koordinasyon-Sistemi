import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MOCK_IDS } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

/** WEB rol atama (AC-01 – AC-03): yalnızca ADM; atama gövdesi, onaylı kaldırma, kendi ADM rolü korunur. */
describe('Roller (AC-01 – AC-03)', () => {
  it('ADM menüde Roller görür; aktif atamalar listelenir', async () => {
    renderApp({ route: '/roller', persona: 'ADM' });
    const table = await screen.findByRole('table', { name: 'Rol atamaları' });
    expect(within(table).getAllByText('Koordinatör (örnek)').length).toBeGreaterThan(0);
    expect(within(table).getByText('Teknik destek (TEK)')).toBeInTheDocument();
    // Menü açılınca (modal çekmece) ana içerik erişilebilirlik ağacından gizlenir; bu yüzden en son açılır.
    await userEvent.setup().click(screen.getByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).getByText('Roller')).toBeInTheDocument();
  });

  it('KOR menüde Roller görmez; sayfa bilgi bandı gösterir', async () => {
    renderApp({ route: '/roller', persona: 'KOR' });
    expect(await screen.findByText(/Rol atama yalnızca/)).toBeInTheDocument();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).queryByText('Roller')).not.toBeInTheDocument();
  });

  it('koordinatör ataması: operasyon kapsamlı gövde', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/roller?operasyon=${MOCK_IDS.op1}`, persona: 'ADM' });
    const spy = vi.spyOn(services.roleAssignments, 'grant');
    await user.click(await screen.findByRole('button', { name: 'Rol ata' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByLabelText(/Kişi/));
    await user.click(await screen.findByRole('option', { name: 'Zeynep A.' }));
    await user.click(within(dialog).getByRole('button', { name: 'Ata' }));
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(spy.mock.calls[0]![0]).toEqual({ userId: MOCK_IDS.volunteer(2), role: 'KOR', scopeType: 'OPERATION', scopeId: MOCK_IDS.op1 });
    expect(await screen.findByText(/Zeynep A\. kişisine Koordinatör \(KOR\) rolü atandı/)).toBeInTheDocument();
  });

  it('kurum rolü ataması: kurum kapsamı oturum bağlamından', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: '/roller', persona: 'ADM' });
    const grant = vi.fn(async () => {
      throw new Error('çağrı yeterli');
    });
    services.roleAssignments.grant = grant;
    await user.click(await screen.findByRole('button', { name: 'Rol ata' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('combobox', { name: /Rol/ }));
    await user.click(await screen.findByRole('option', { name: 'Teknik destek (TEK)' }));
    expect(within(dialog).queryByRole('combobox', { name: /Operasyon/ })).not.toBeInTheDocument();
    await user.click(within(dialog).getByLabelText(/Kişi/));
    await user.click(await screen.findByRole('option', { name: 'Koordinatör (örnek)' }));
    await user.click(within(dialog).getByRole('button', { name: 'Ata' }));
    await waitFor(() => expect(grant).toHaveBeenCalled());
    expect(grant).toHaveBeenCalledWith({ userId: MOCK_IDS.kor, role: 'TEK', scopeType: 'ORGANIZATION', scopeId: MOCK_IDS.org });
  });

  it('kendi ADM rolü kaldırılamaz: Türkçe 403 iletisi', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/roller?rol=ADM', persona: 'ADM' });
    await user.click(await screen.findByRole('button', { name: 'Yönetici (örnek) ADM rolünü kaldır' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Kaldır' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Kendi kurum yöneticisi rolünüzü');
  });

  it('koordinatör kaldırma onaylıdır ve listeden düşer', async () => {
    const user = userEvent.setup();
    renderApp({ route: `/roller?rol=KOR&operasyon=${MOCK_IDS.op2}`, persona: 'ADM' });
    await user.click(await screen.findByRole('button', { name: 'Koordinatör (örnek) KOR rolünü kaldır' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/bu operasyonu artık yönetemez/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Kaldır' }));
    expect(await screen.findByText('Bu ölçütlere uyan rol ataması yok.')).toBeInTheDocument();
  });
});
