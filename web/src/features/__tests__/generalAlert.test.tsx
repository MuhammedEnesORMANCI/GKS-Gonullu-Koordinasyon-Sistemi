import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockAlertService } from '../../services/mock/services';
import { MOCK_IDS, mockStore } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';
import { parsePoint } from '../map/PointPicker';

describe('Genel Acil Alarm (ADR-026)', () => {
  it('ADM: üst çubuktan; tüm kurum hedefi; tekilleştirilmiş sayı; onay kutusu olmadan GÖNDER pasif; gönderince takip ekranı', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel', persona: 'ADM' });
    await user.click(await screen.findByRole('button', { name: 'Genel Acil Alarm' }));
    const dialog = await screen.findByRole('dialog', { name: 'Genel Acil Alarm' });
    await user.type(within(dialog).getByRole('textbox', { name: 'Mesaj' }), 'Tatbikat: binayı boşaltın');
    await user.click(within(dialog).getByRole('button', { name: 'Önizle' }));
    expect(await within(dialog).findByText('En az bir hedef seçin.')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('checkbox', { name: 'Tüm kurum (tüm etkin hesaplar)' }));
    await user.click(within(dialog).getByRole('button', { name: 'Önizle' }));
    const confirm = await screen.findByRole('dialog', { name: 'Genel acil alarmı onaylayın' });
    const expected = [...mockStore.users.keys()].filter((id) => id !== MOCK_IDS.adm).length;   // gönderen hariç, tekil
    expect(within(confirm).getByText(String(expected))).toBeInTheDocument();
    expect(within(confirm).getAllByRole('listitem')).toHaveLength(expected);
    const send = within(confirm).getByRole('button', { name: 'GÖNDER' });
    expect(send).toBeDisabled();
    await user.click(within(confirm).getByRole('checkbox', { name: /kontrol ettim/ }));
    expect(send).toBeEnabled();
    await user.click(send);
    expect(await screen.findByText('Genel Acil Alarm', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByText('Tatbikat: binayı boşaltın')).toBeInTheDocument();
  });

  it('KOR: tüm kurum seçeneği yok; kendi operasyonunu hedefler, katılımcı + koordinatör tekil sayılır', async () => {
    const user = userEvent.setup();
    renderApp({ route: '/panel', persona: 'KOR' });
    await user.click(await screen.findByRole('button', { name: 'Genel Acil Alarm' }));
    const dialog = await screen.findByRole('dialog', { name: 'Genel Acil Alarm' });
    expect(within(dialog).queryByRole('checkbox', { name: /Tüm kurum/ })).not.toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox', { name: 'Mesaj' }), 'Toplanın');
    await user.click(within(dialog).getByRole('combobox', { name: /Operasyonlar/ }));
    await user.click(await screen.findByRole('option', { name: 'Deprem Arama Kurtarma — Merkez' }));
    await user.click(within(dialog).getByRole('button', { name: 'Önizle' }));
    const confirm = await screen.findByRole('dialog', { name: 'Genel acil alarmı onaylayın' });
    // op1: 6 katılımcı (KOR gönderen olduğundan sayılmaz).
    expect(within(confirm).getByText('6')).toBeInTheDocument();
  });

  it('TEK üst çubukta genel alarm düğmesini görmez', async () => {
    renderApp({ route: '/panel', persona: 'TEK' });
    await screen.findByRole('button', { name: 'Kullanıcı menüsü' });
    expect(screen.queryByRole('button', { name: 'Genel Acil Alarm' })).not.toBeInTheDocument();
  });

  it('onaysız gönderim reddedilir; KOR başka kişinin operasyonunu ve kurum genelini seçemez', async () => {
    mockStore.persona = 'ADM';
    const service = mockAlertService();
    const preview = await service.createGeneral({ message: 'x', targets: { organization: false, operationIds: [MOCK_IDS.op1], groupIds: [], userIds: [] } });
    await expect(service.send(preview.alertId, preview.version, preview.audienceHash, 'k1')).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
    await expect(service.send(preview.alertId, preview.version, preview.audienceHash, 'k2', true)).resolves.toMatchObject({ state: 'OPEN' });
    mockStore.persona = 'KOR';
    await expect(service.createGeneral({ message: 'x', targets: { organization: true, operationIds: [], groupIds: [], userIds: [] } }))
      .rejects.toMatchObject({ status: 403 });
  });

  it('olay yeri doğrulaması', () => {
    expect(parsePoint({ lat: '', lon: '' })).toEqual({ point: null });
    expect(parsePoint({ lat: '40,5', lon: '29' })).toEqual({ point: { lat: 40.5, lon: 29 } });
    expect(parsePoint({ lat: '95', lon: '29' }).error).toMatch(/Enlem/);
    expect(parsePoint({ lat: '40', lon: '' }).error).toMatch(/birlikte/);
  });
});
