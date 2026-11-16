import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderApp } from '../../test/renderApp';

/** WEB-11 denetim kayıtları (AU-01): salt-okur liste, dayanak süzgeci, yetki. */
describe('Denetim kayıtları (AU-01)', () => {
  it('ADM listeyi görür; ADM yetkisiyle yapılan işlem ayırt edilir; düzenleme düğmesi yok', async () => {
    renderApp({ route: '/denetim', persona: 'ADM' });
    const table = await screen.findByRole('table', { name: 'Denetim kayıtları' });
    expect(within(table).getByText('Görev oluşturuldu')).toBeInTheDocument();
    expect(within(table).getAllByText('Kurum yöneticisi yetkisi').length).toBeGreaterThan(0);
    expect(within(table).getByText('Koordinatör ataması')).toBeInTheDocument();
    expect(within(table).queryByRole('button')).not.toBeInTheDocument();
  });

  it('dayanak süzgeci sunucuya gider', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: '/denetim', persona: 'ADM' });
    const spy = vi.spyOn(services.audit, 'list');
    await screen.findByRole('table', { name: 'Denetim kayıtları' });
    await user.click(screen.getByRole('combobox', { name: /Yetki dayanağı/ }));
    await user.click(await screen.findByRole('option', { name: 'Kurum yöneticisi yetkisi' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(expect.objectContaining({ actorBasis: 'ADM' })));
  });

  it('UYM görür, KOR göremez', async () => {
    renderApp({ route: '/denetim', persona: 'UYM' });
    expect(await screen.findByRole('table', { name: 'Denetim kayıtları' })).toBeInTheDocument();
  });

  it('KOR için bilgi bandı', async () => {
    renderApp({ route: '/denetim', persona: 'KOR' });
    expect(await screen.findByText(/yalnızca kurum yöneticisi ve uyum/)).toBeInTheDocument();
  });
});
