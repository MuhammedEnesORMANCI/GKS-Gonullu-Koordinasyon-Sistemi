import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MOCK_IDS, mockStore } from '../../services/mock/store';
import { mockInvitationService, mockMeContextService } from '../../services/mock/services';
import { renderApp } from '../../test/renderApp';

async function openCreateDialog() {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Davet gönder' }));
  return { user, dialog: await screen.findByRole('dialog', { name: 'Davet gönder' }) };
}

describe('Davetler (ID-12 – ID-14)', () => {
  it('KOR: bekleyen davetler listelenir; davet kodu hiçbir yerde görünmez', async () => {
    renderApp({ route: '/davetler', persona: 'KOR' });
    const table = await screen.findByRole('table', { name: 'Davet listesi' });
    expect(within(table).getByText('yeni.gonullu@ornek.invalid')).toBeInTheDocument();
    expect(within(table).getByText('Yeni kullanıcı')).toBeInTheDocument();
    expect(within(table).getByText('Bekliyor')).toBeInTheDocument();
    expect(screen.queryByText(/kod:/i)).not.toBeInTheDocument();
  });

  it('menüde Davetler yalnızca davet izni olan rolde görünür', async () => {
    const user = userEvent.setup();
    const { unmount } = renderApp({ route: '/', persona: 'KOR' });
    await user.click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const nav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(nav).getByRole('link', { name: 'Davetler' })).toBeInTheDocument();
    unmount();
    renderApp({ route: '/', persona: 'TEK' });
    await user.click(await screen.findByRole('button', { name: 'Menüyü aç' }));
    const tekNav = await screen.findByRole('navigation', { name: 'Ana menü' });
    expect(within(tekNav).queryByRole('link', { name: 'Davetler' })).not.toBeInTheDocument();
  });

  it('davet gönderme: Idempotency-Key ile, başarı mesajı ve listede yeni satır', async () => {
    const invitations = mockInvitationService();
    const create = vi.spyOn(invitations, 'create');
    renderApp({ route: `/davetler?kapsam=${MOCK_IDS.op1}`, persona: 'KOR', overrides: { invitations } });
    const { user, dialog } = await openCreateDialog();
    await user.type(within(dialog).getByLabelText(/E-posta/), 'yeni.kisi@ornek.invalid');
    await user.click(within(dialog).getByRole('button', { name: 'Gönder' }));
    expect(await screen.findByText(/yeni.kisi@ornek.invalid adresine davet gönderildi/)).toBeInTheDocument();
    expect(create).toHaveBeenCalledWith(
      { email: 'yeni.kisi@ornek.invalid', scopeType: 'OPERATION', scopeId: MOCK_IDS.op1 },
      expect.stringMatching(/^[0-9a-f-]{36}$/),
    );
    const table = await screen.findByRole('table', { name: 'Davet listesi' });
    await waitFor(() => expect(within(table).getByText('yeni.kisi@ornek.invalid')).toBeInTheDocument());
  });

  it('aynı kişiye bekleyen davet: ALREADY_EXISTS/INVITATION_PENDING mesajı', async () => {
    renderApp({ route: `/davetler?kapsam=${MOCK_IDS.op1}`, persona: 'KOR' });
    const { user, dialog } = await openCreateDialog();
    await user.type(within(dialog).getByLabelText(/E-posta/), 'yeni.gonullu@ornek.invalid');
    await user.click(within(dialog).getByRole('button', { name: 'Gönder' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Bu kişiye bu kapsamda bekleyen bir davet zaten var.');
  });

  it('zaten katılımcı olan kişi: ALREADY_PARTICIPANT mesajı', async () => {
    renderApp({ route: `/davetler?kapsam=${MOCK_IDS.op1}`, persona: 'KOR' });
    const { user, dialog } = await openCreateDialog();
    await user.type(within(dialog).getByLabelText(/E-posta/), 'gonullu1@ornek.invalid');
    await user.click(within(dialog).getByRole('button', { name: 'Gönder' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Bu kişi zaten operasyon katılımcısı.');
  });

  it('geçersiz e-posta istemcide engellenir', async () => {
    renderApp({ route: `/davetler?kapsam=${MOCK_IDS.op1}`, persona: 'KOR' });
    const { user, dialog } = await openCreateDialog();
    await user.type(within(dialog).getByLabelText(/E-posta/), 'gecersiz');
    expect(within(dialog).getByText('Geçerli bir e-posta adresi girin.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Gönder' })).toBeDisabled();
  });

  it('ADM: kurum kapsamı seçilebilir; kayıtlı e-postaya kurum daveti EMAIL_REGISTERED', async () => {
    renderApp({ route: '/davetler?kapsam=ORG', persona: 'ADM' });
    const { user, dialog } = await openCreateDialog();
    expect(within(dialog).getByRole('combobox', { name: /Kapsam/ })).toHaveTextContent('Kurum');
    await user.type(within(dialog).getByLabelText(/E-posta/), 'koordinator@ornek.invalid');
    await user.click(within(dialog).getByRole('button', { name: 'Gönder' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Bu e-posta kurumda zaten kayıtlı.');
  });

  it('iptal: onay penceresi, sonra durum "İptal edildi"', async () => {
    renderApp({ route: '/davetler?durum=', persona: 'KOR' });
    const user = userEvent.setup();
    const table = await screen.findByRole('table', { name: 'Davet listesi' });
    await user.click(within(table).getByRole('button', { name: 'İptal et' }));
    const dialog = await screen.findByRole('dialog', { name: 'Daveti iptal et' });
    expect(dialog).toHaveTextContent('Davet kodu ve bağlantısı geçersiz olur.');
    await user.click(within(dialog).getByRole('button', { name: 'İptal et' }));
    await waitFor(() => expect(within(table).getByText('İptal edildi')).toBeInTheDocument());
    expect(within(table).queryByRole('button', { name: 'İptal et' })).not.toBeInTheDocument();
  });

  it('gelen davetler bölümü: MeContext.pendingInvitations gösterilir', async () => {
    const base = mockMeContextService();
    const meContext = {
      getContext: async () => ({
        ...(await base.getContext()),
        pendingInvitations: [
          {
            id: '0192f000-7000-7000-8000-000000000999',
            kind: 'OPERATION' as const,
            status: 'PENDING' as const,
            scopeType: 'OPERATION' as const,
            scopeId: MOCK_IDS.op2,
            scopeName: 'Sel Bölgesi Lojistik',
            expiresAt: '2026-12-01T10:00:00Z',
            createdAt: '2026-11-15T10:00:00Z',
            createdBy: { id: MOCK_IDS.adm, displayName: 'Yönetici (örnek)' },
          },
        ],
      }),
    };
    renderApp({ route: '/davetler', persona: 'KOR', overrides: { meContext } });
    expect(await screen.findByRole('heading', { name: 'Bana gelen davetler' })).toBeInTheDocument();
    expect(screen.getByText('Sel Bölgesi Lojistik')).toBeInTheDocument();
    expect(screen.getByText(/mobil uygulamadan kabul edilir/)).toBeInTheDocument();
  });

  it('bekleyen davet yoksa açıklama ve gönder düğmesi', async () => {
    mockStore.invitations.clear();
    renderApp({ route: '/davetler', persona: 'KOR' });
    expect(await screen.findByText(/Bekleyen davet yok. Gönüllüleri e-postayla davet edin/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Davet gönder' })).toHaveLength(2);
    expect(screen.getByText('Size gelen bekleyen davet yok.')).toBeInTheDocument();
  });
});
