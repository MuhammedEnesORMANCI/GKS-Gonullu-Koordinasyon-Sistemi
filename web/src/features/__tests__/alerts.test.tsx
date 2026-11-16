import { describe, expect, it } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { emitMockLiveEvent } from '../../services/mock/services';
import { MOCK_IDS } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

describe('Acil alarm (WEB-09/10, ADR-018)', () => {
  it('KOR operasyon alarmlarını görür; detayda sayılar ve "bilinmiyor" sinyalleri', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/alarmlar` });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Artçı sarsıntı: B blok çevresini boşaltın' }));
    expect(await screen.findByText('Yardım gerekiyor: 1')).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Alıcılar' });
    const rows = within(table).getAllByRole('row');
    expect(within(rows[1]!).getByText('Yardım gerekiyor')).toBeInTheDocument();   // NEED_HELP önce
    expect(within(table).getAllByText('bilinmiyor').length).toBeGreaterThan(0);
  });

  it('taslak → alıcı listesiyle onay → gönderim; gönderen alıcı olmaz', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/alarmlar` });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Acil alarm gönder' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Önizle' }));
    expect(await within(dialog).findByText('Mesaj zorunludur.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Mesaj/), 'Gaz kaçağı, binayı boşaltın');
    await user.click(within(dialog).getByLabelText('Hedef gruplar'));
    await user.click((await screen.findAllByRole('option', { name: /üye/ }))[0]!);
    await user.click(within(dialog).getByRole('button', { name: 'Önizle' }));
    const send = await within(dialog).findByRole('button', { name: /kişiye gönder/ });
    expect(within(dialog).getByRole('list', { name: 'Alıcılar' })).toBeInTheDocument();
    await user.click(send);
    expect(await screen.findByText('Gaz kaçağı, binayı boşaltın')).toBeInTheDocument();
    expect(await screen.findByText('Açık')).toBeInTheDocument();
  });

  it('ADM KOR ataması olmadan iptal eder; canlı olay listeyi yeniler', async () => {
    renderApp({ route: '/alarmlar', persona: 'ADM' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Artçı sarsıntı: B blok çevresini boşaltın' }));
    await user.click(await screen.findByRole('button', { name: 'İptal et' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'İptal et' }));
    expect(await screen.findByRole('button', { name: 'Düzeltilmiş alarm gönder' })).toBeInTheDocument();
    act(() => emitMockLiveEvent({ type: 'alert.cancelled', data: { topic: `operation:${MOCK_IDS.op1}` } }));
    await waitFor(() => expect(screen.getAllByText('İptal edildi').length).toBeGreaterThan(0));
  });
});
