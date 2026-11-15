import { describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../api/problem';
import { sessionEvents } from '../../api/sessionEvents';
import { CONTRACT_PRESET } from '../../config/services';
import { createServices } from '../../services/registry';
import { MOCK_IDS } from '../../services/mock/store';
import { mockTaskService } from '../../services/mock/services';
import { renderApp } from '../../test/renderApp';

describe('uygulama kabuğu ve rol tabanlı menü', () => {
  it('KOR: operasyon menüsü ve örnek veri rozeti', async () => {
    renderApp({ route: '/', persona: 'KOR' });
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).getByText('Operasyonlar')).toBeInTheDocument();
    expect(screen.getAllByTestId('mock-badge').length).toBeGreaterThan(0);
  });

  it('TEK: operasyon menüsü yok', async () => {
    renderApp({ route: '/', persona: 'TEK' });
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).getByText('Panel')).toBeInTheDocument();
    expect(within(nav).queryByText('Operasyonlar')).not.toBeInTheDocument();
  });

  it('oturum sonu olayı önbelleği temizler ve girişe yönlendirir', async () => {
    const { queryClient } = renderApp({ route: '/operasyonlar', persona: 'KOR' });
    await screen.findByRole('heading', { name: 'Operasyonlar' });
    act(() => sessionEvents.endSession('AUTH_SESSION_REVOKED'));
    await screen.findByRole('heading', { name: 'Yönetici girişi' });
    expect(screen.getByRole('alert')).toHaveTextContent('Oturumunuz sonlandırıldı. Lütfen yeniden giriş yapın.');
    // Önceki kullanıcının verisi önbellekte kalmaz (bağlı gözlemciler boş sorgu kaydı tutabilir).
    expect(queryClient.getQueryCache().getAll().filter((q) => q.state.data !== undefined)).toHaveLength(0);
  });

  it('me/context 503 → oturum korunur, yeniden deneme bandı', async () => {
    const meContext = { getContext: vi.fn(async () => Promise.reject(new ApiError({ type: 't', title: '', status: 503, code: 'SERVICE_UNAVAILABLE', extensions: {} }))) };
    renderApp({ route: '/', overrides: { meContext } });
    expect(
      await screen.findByText('Sunucuya şu an ulaşılamıyor. Biraz sonra yeniden denenecek.', undefined, { timeout: 6000 }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Yönetici girişi' })).not.toBeInTheDocument();
  });

  it('contract ön ayarı: kaynak seçimi tek yapılandırmadan', () => {
    const s = createServices(CONTRACT_PRESET);
    expect(Object.keys(s).sort()).toEqual(['account', 'announcements', 'auth', 'groups', 'invitations', 'meContext', 'notifications', 'operationList', 'operations', 'participants', 'tasks']);
    // Tüm kaynakların backend karşılığı var: varsayılan ön ayarda örnek veri kalmaz.
    expect(Object.values(CONTRACT_PRESET).every((mode) => mode === 'http')).toBe(true);
  });
});

describe('WEB-03 operasyon detayı', () => {
  it('kapatma onayı: uyarı metni, odak Vazgeç, onayla → Tamamlandı', async () => {
    const user = userEvent.setup();
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}`, persona: 'KOR' });
    await user.click(await screen.findByRole('button', { name: 'Kapat (Tamamlandı)' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('tüm konum paylaşımları durur');
    expect(within(dialog).getByRole('button', { name: 'Vazgeç' })).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: 'Kapat (Tamamlandı)' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Operasyon kapandı; kayıtlar salt-okur gösteriliyor.')).toBeInTheDocument();
  });

  it('düzenleme: ad uzunluğu doğrulanır, PATCH yalnızca değişen alanı gönderir', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/operasyonlar/${MOCK_IDS.op2}`, persona: 'KOR' });
    const spy = vi.spyOn(services.operations, 'update');
    await user.click(await screen.findByRole('button', { name: 'Düzenle' }));
    const name = screen.getByLabelText(/^Ad/);
    await user.clear(name);
    await user.type(name, 'ab');
    expect(screen.getByText('Ad 3–120 karakter olmalı.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Kaydet' })).toBeDisabled();
    await user.type(name, 'c');
    await user.click(screen.getByRole('button', { name: 'Kaydet' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(MOCK_IDS.op2, { name: 'abc' }));
  });

  it('LD-52: açıklama ve planlanan bitiş boşaltılınca null gönderilir', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/operasyonlar/${MOCK_IDS.op2}`, persona: 'KOR' });
    const spy = vi.spyOn(services.operations, 'update');
    await user.click(await screen.findByRole('button', { name: 'Düzenle' }));
    await user.clear(screen.getByLabelText(/Açıklama/));
    await user.clear(screen.getByLabelText(/Planlanan bitiş/));
    await user.click(screen.getByRole('button', { name: 'Kaydet' }));
    await waitFor(() => expect(spy).toHaveBeenCalledWith(MOCK_IDS.op2, { description: null, plannedEndAt: null }));
  });

  it('INVALID_STATE_TRANSITION → hedef durumu adlandıran mesaj', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/operasyonlar/${MOCK_IDS.op2}`, persona: 'KOR' });
    vi.spyOn(services.operations, 'changeState').mockRejectedValue(
      new ApiError({ type: 't', title: '', status: 409, code: 'INVALID_STATE_TRANSITION', extensions: { currentState: 'COMPLETED', requestedState: 'ACTIVE' } }),
    );
    await user.click(await screen.findByRole('button', { name: 'Aktif et' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Aktif et' }));
    expect(await screen.findByText("Operasyon bu durumdan 'Aktif' durumuna geçemez.")).toBeInTheDocument();
  });
});

describe('WEB-05 grup yönetimi', () => {
  it('lider seçilmeden grup oluşturulamaz', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gruplar`, persona: 'KOR' });
    const spy = vi.spyOn(services.groups, 'create');
    await user.click(await screen.findByRole('button', { name: 'Grup oluştur' }));
    await user.type(screen.getByLabelText(/Grup adı/), 'Yeni ekip');
    await user.click(screen.getByRole('button', { name: 'Oluştur' }));
    expect(screen.getByText('Lider seçimi zorunludur.')).toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('lider seçilince Idempotency-Key ile oluşturulur ve detaya gidilir', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gruplar`, persona: 'KOR' });
    const spy = vi.spyOn(services.groups, 'create');
    await user.click(await screen.findByRole('button', { name: 'Grup oluştur' }));
    await user.type(screen.getByLabelText(/Grup adı/), 'Yeni ekip');
    await user.click(screen.getByLabelText(/Lider/));
    await user.click(await screen.findByRole('option', { name: /Can Ö\./ }));
    await user.click(screen.getByRole('button', { name: 'Oluştur' }));
    await waitFor(() => expect(spy).toHaveBeenCalled());
    const [opId, body, key] = spy.mock.calls[0]!;
    expect(opId).toBe(MOCK_IDS.op1);
    expect(body).toEqual({ name: 'Yeni ekip', leaderUserId: MOCK_IDS.volunteer(3), memberUserIds: [] });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByRole('heading', { name: 'Yeni ekip' })).toBeInTheDocument();
  });

  it('grup detayı: liderin Çıkar düğmesi kapalı, üye çıkarma onaylı', async () => {
    const user = userEvent.setup();
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gruplar/${MOCK_IDS.group1}`, persona: 'KOR' });
    expect(await screen.findByRole('button', { name: 'Ali K. kişisini çıkar' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Zeynep A. kişisini çıkar' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Çıkar' }));
    await waitFor(() => expect(screen.queryByText('Zeynep Arslan')).not.toBeInTheDocument());
  });

  it('ADM salt-okur: bilgi bandı, işlem düğmesi yok', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gruplar/${MOCK_IDS.group1}`, persona: 'ADM' });
    expect(await screen.findByText(/Salt-okur görünüm/)).toBeInTheDocument();
    await screen.findByText('Ali Kaya');
    expect(screen.queryByRole('button', { name: 'Üye ekle' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /kişisini çıkar/ })).not.toBeInTheDocument();
  });

  it('LEADER_REQUIRED sunucu hatası Türkçe mesajla gösterilir', async () => {
    const user = userEvent.setup();
    const { services } = renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gruplar/${MOCK_IDS.group1}`, persona: 'KOR' });
    vi.spyOn(services.groups, 'removeMember').mockRejectedValue(
      new ApiError({ type: 't', title: '', status: 409, code: 'LEADER_REQUIRED', extensions: {} }),
    );
    await user.click(await screen.findByRole('button', { name: 'Zeynep A. kişisini çıkar' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Çıkar' }));
    expect(await screen.findByText('Grup lidersiz bırakılamaz. Önce yeni bir lider seçin.')).toBeInTheDocument();
  });
});

describe('WEB-06 görev yönetimi (mock)', () => {
  it('liste kapasite göstergesi ve süresi geçen işareti', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler`, persona: 'KOR' });
    expect(await screen.findByText('1/3')).toBeInTheDocument();
    expect(screen.getByText('1 süresi geçti')).toBeInTheDocument();
    expect(screen.getAllByTestId('mock-badge').length).toBeGreaterThan(0);
  });

  it('taslak yayınlama: onay penceresi ve Idempotency-Key', async () => {
    const user = userEvent.setup();
    const tasks = mockTaskService();
    const publish = vi.spyOn(tasks, 'publish');
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler/${MOCK_IDS.task2}`, persona: 'KOR', overrides: { tasks } });
    await user.click(await screen.findByRole('button', { name: 'Yayınla' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('Yayın anındaki aktif üyelere atama oluşur');
    await user.click(within(dialog).getByRole('button', { name: 'Yayınla' }));
    await waitFor(() => expect(publish).toHaveBeenCalled());
    expect(publish.mock.calls[0]![1]).toBe(1);
    expect(publish.mock.calls[0]![2]).toMatch(/^[0-9a-f-]{36}$/);
    expect((await screen.findAllByText('Yayında')).length).toBeGreaterThan(0);
  });

  it('atama tablosu sekmeleri ve süresi geçen satır metinle işaretli', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler/${MOCK_IDS.task1}`, persona: 'KOR' });
    expect(await screen.findByRole('tab', { name: 'Bekliyor' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('Yanıt süresi geçti')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Kapasite doldu' })).toBeInTheDocument();
  });

  it('VERSION_CONFLICT: form korunur, güncel değer işaretlenir', async () => {
    const user = userEvent.setup();
    const tasks = mockTaskService();
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler/${MOCK_IDS.task1}`, persona: 'KOR', overrides: { tasks } });
    await user.click(await screen.findByRole('button', { name: 'Düzenle' }));
    // Başka biri bu arada değiştirdi
    await tasks.update(MOCK_IDS.task1, { title: 'Başkası değiştirdi', version: 2 });
    const title = screen.getByLabelText(/Başlık/);
    await user.clear(title);
    await user.type(title, 'Benim başlığım');
    await user.click(screen.getByRole('button', { name: 'Kaydet' }));
    expect(await screen.findByText(/Bu kayıt siz düzenlerken değişti/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Başlık/)).toHaveValue('Benim başlığım');
    expect(screen.getByText('Sunucudaki güncel değer: Başkası değiştirdi')).toBeInTheDocument();
  });

  it('LD-52: kapasite boşaltılınca capacity: null (sınırsız) gönderilir', async () => {
    const user = userEvent.setup();
    const tasks = mockTaskService();
    const update = vi.spyOn(tasks, 'update');
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler/${MOCK_IDS.task1}`, persona: 'KOR', overrides: { tasks } });
    await user.click(await screen.findByRole('button', { name: 'Düzenle' }));
    await user.clear(screen.getByLabelText(/Kapasite/));
    await user.clear(screen.getByLabelText(/Yer \/ buluşma noktası/));
    await user.click(screen.getByRole('button', { name: 'Kaydet' }));
    await waitFor(() =>
      expect(update).toHaveBeenCalledWith(MOCK_IDS.task1, { capacity: null, meetingPoint: null, version: 2 }),
    );
    expect(await screen.findByText(/Kapasite: 1 \/ Sınırsız/)).toBeInTheDocument();
  });

  it('bitiş girilip başlangıç boşsa istemci aynı hatayı gösterir, istek gitmez', async () => {
    const user = userEvent.setup();
    const tasks = mockTaskService();
    const create = vi.spyOn(tasks, 'create');
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler`, persona: 'KOR', overrides: { tasks } });
    await user.click(await screen.findByRole('button', { name: 'Görev oluştur' }));
    await user.type(screen.getByLabelText(/Başlık/), 'Zamanlı görev');
    await user.type(screen.getByLabelText(/Bitiş/), '2026-11-16T10:00');
    await user.click(screen.getByRole('button', { name: 'Taslak olarak kaydet' }));
    expect(screen.getByText('Bitiş girildiyse başlangıç zorunludur.')).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('ADM görevlerde salt-okur', async () => {
    renderApp({ route: `/operasyonlar/${MOCK_IDS.op1}/gorevler`, persona: 'ADM' });
    expect(await screen.findByText(/Salt-okur görünüm/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Görev oluştur' })).not.toBeInTheDocument();
  });
});
