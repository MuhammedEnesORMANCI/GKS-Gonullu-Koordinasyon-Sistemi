import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { messageFor } from '../../api/errorMessages';
import { mockParticipantService } from '../../services/mock/services';
import { MOCK_IDS, mockStore } from '../../services/mock/store';
import { renderApp } from '../../test/renderApp';

describe('Operasyonlar: oluşturma, boş durum, katılımcılar', () => {
  it('ADM: operasyon oluşturur (kendisi koordinatör) ve detaya gider', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: '/operasyonlar', persona: 'ADM' });
    const create = vi.spyOn(services.operations, 'create');
    await user.click(await screen.findByRole('button', { name: 'Operasyon oluştur' }));
    const dialog = await screen.findByRole('dialog', { name: 'Operasyon oluştur' });
    expect(dialog).toHaveTextContent('Yönetici (örnek) (siz)');
    await user.type(within(dialog).getByLabelText(/^Ad/), 'Tatbikat Operasyonu');
    await user.type(within(dialog).getByLabelText(/Açıklama/), 'Gösterim için');
    await user.click(within(dialog).getByRole('button', { name: 'Oluştur' }));
    expect(await screen.findByRole('heading', { name: 'Tatbikat Operasyonu' })).toBeInTheDocument();
    expect(create).toHaveBeenCalledWith(
      { name: 'Tatbikat Operasyonu', description: 'Gösterim için', coordinatorIds: [MOCK_IDS.adm] },
      expect.any(String),
    );
    expect(screen.getByText('Planlandı')).toBeInTheDocument();
  });

  it('ad zorunlu ve en az 3 karakter: istek gönderilmez', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: '/operasyonlar', persona: 'ADM' });
    const create = vi.spyOn(services.operations, 'create');
    await user.click(await screen.findByRole('button', { name: 'Operasyon oluştur' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Oluştur' }));
    expect(within(dialog).getByText('Bu alan zorunludur.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/^Ad/), 'ab');
    expect(within(dialog).getByText('En az 3 karakter.')).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('KOR: oluşturma düğmesi yok', async () => {
    renderApp({ route: '/operasyonlar', persona: 'KOR' });
    expect(await screen.findByRole('table', { name: 'Operasyon listesi' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Operasyon oluştur' })).not.toBeInTheDocument();
  });

  it('boş liste: ADM için açıklama ve oluşturma düğmesi', async () => {
    mockStore.operations.clear();
    renderApp({ route: '/operasyonlar', persona: 'ADM' });
    expect(await screen.findByText(/Henüz operasyon yok\. İlk operasyonu oluşturun/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Operasyon oluştur' })).toHaveLength(2);
  });

  it('detay: katılımcılar listelenir ve davet kısayolu görünür', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}`, persona: 'KOR' });
    const table = await screen.findByRole('table', { name: 'Katılımcı listesi' });
    expect(within(table).getByText('Ali Kaya')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Davet gönder' })).toHaveAttribute('href', `/panel/davetler?kapsam=${MOCK_IDS.op1}`);
  });

  it('KOR kendi operasyonuna kayıtlı kullanıcıyı davetsiz ekler; liste ve bildirim mesajı (RAPOR_7 madde 4)', async () => {
    const user = userEvent.setup();
    mockStore.participants.set(MOCK_IDS.op2, []);
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op2}`, persona: 'KOR' });
    await user.click(await screen.findByRole('button', { name: 'Katılımcı ekle' }));
    const dialog = await screen.findByRole('dialog', { name: 'Katılımcı ekle' });
    await user.type(within(dialog).getByRole('combobox', { name: 'Kişiler' }), 'Teknik');
    await user.click(await screen.findByRole('option', { name: 'Teknik (örnek)' }));
    await user.click(within(dialog).getByRole('button', { name: '1 kişiyi ekle' }));
    expect(await screen.findByText(/1 kişi katılımcı olarak eklendi: Teknik \(örnek\)/)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Katılımcı listesi' });
    expect(within(table).getByText('Teknik (örnek)')).toBeInTheDocument();
    expect(mockStore.participants.get(MOCK_IDS.op2)?.map((p) => p.userId)).toEqual([MOCK_IDS.tek]);
  });

  it('kapalı operasyonda ekleme düğmesi yok; zaten katılımcı ve devre dışı hesap net mesajla reddedilir', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op3}`, persona: 'ADM' });
    await screen.findByRole('heading', { name: /Katılımcılar/ });
    expect(screen.queryByRole('button', { name: 'Katılımcı ekle' })).not.toBeInTheDocument();

    mockStore.persona = 'ADM';
    const service = mockParticipantService();
    const existing = mockStore.participants.get(MOCK_IDS.op1)![0]!.userId;
    const again = await service.add(MOCK_IDS.op1, [existing]).catch((e: unknown) => e);
    expect(messageFor(again)).toMatch(/^Zaten operasyon katılımcısı: /);
    mockStore.users.set('0192f000-2000-7000-8000-0000000000dd', { id: '0192f000-2000-7000-8000-0000000000dd', displayName: 'Pasif Kişi', status: 'DISABLED' });
    const disabled = await service.add(MOCK_IDS.op1, ['0192f000-2000-7000-8000-0000000000dd']).catch((e: unknown) => e);
    expect(messageFor(disabled)).toBe('Pasif Kişi: hesap devre dışı, katılımcı eklenemez');
    const candidates = await service.candidates(MOCK_IDS.op1, '');
    expect(candidates.items.some((c) => c.id === existing || c.id === '0192f000-2000-7000-8000-0000000000dd')).toBe(false);
    mockStore.persona = 'KOR';
    await expect(service.add(MOCK_IDS.op3, [MOCK_IDS.tek])).rejects.toMatchObject({ code: 'FORBIDDEN' });   // başka operasyon
  });

  it('gruplar: katılımcı yoksa açıklama ve davet kısayolu, boş listede oluşturma düğmesi', async () => {
    mockStore.participants.set(MOCK_IDS.op2, []);
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op2}/gruplar`, persona: 'KOR' });
    expect(await screen.findByText(/Bu operasyonda henüz katılımcı yok. Grup lideri/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Davet gönder' })).toHaveAttribute('href', `/panel/davetler?kapsam=${MOCK_IDS.op2}`);
    expect(await screen.findByText(/Henüz grup yok./)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Grup oluştur' })).toHaveLength(2);
  });

  it('görevler: katılımcı yoksa açıklama; boş listede oluşturma düğmesi', async () => {
    mockStore.participants.set(MOCK_IDS.op2, []);
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op2}/gorevler`, persona: 'KOR' });
    expect(await screen.findByText(/Görevi kişilere veya gruplara atamak için/)).toBeInTheDocument();
    expect(await screen.findByText(/Henüz görev yok./)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Görev oluştur' })).toHaveLength(2);
  });

  it('katılımcı varsa uyarı gösterilmez', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gruplar`, persona: 'KOR' });
    expect(await screen.findByRole('table', { name: 'Grup listesi' })).toBeInTheDocument();
    expect(screen.queryByText(/henüz katılımcı yok/)).not.toBeInTheDocument();
  });
});
