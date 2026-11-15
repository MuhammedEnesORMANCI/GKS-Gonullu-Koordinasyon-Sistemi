import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../api/problem';
import type { AccountService } from '../../services/contracts';
import type { MeProfile } from '../../services/types';
import { renderApp } from '../../test/renderApp';
import { profileUpdate } from '../account/ProfilePage';

const profile: MeProfile = {
  id: 'u1',
  email: 'kor@ornek.invalid',
  fullName: 'Ayşe Kaya',
  displayName: 'Ayşe K.',
  status: 'ACTIVE',
  phone: '+905551112233',
  mfaEnrolled: true,
  createdAt: '2026-11-15T10:00:00Z',
};

function accountWith(update: AccountService['updateProfile']): AccountService {
  return { getProfile: vi.fn(async () => profile), updateProfile: vi.fn(update) };
}

describe('ID-10 kısmi güncelleme', () => {
  it('yalnızca değişen alanlar gider; boş telefon null (silme)', () => {
    expect(profileUpdate(profile, { fullName: 'Ayşe Kaya', displayName: 'Ayşe K.', phone: '+905551112233' })).toEqual({});
    expect(profileUpdate(profile, { fullName: ' Ayşe Yılmaz ', displayName: 'Ayşe K.', phone: '' })).toEqual({
      fullName: 'Ayşe Yılmaz',
      phone: null,
    });
  });
});

describe('Hesabım sayfası', () => {
  it('profili gösterir, değişikliği kaydeder', async () => {
    const account = accountWith(async (u) => ({ ...profile, ...u, phone: u.phone ?? undefined }));
    renderApp({ route: '/hesabim', overrides: { account } });
    const user = userEvent.setup();
    const name = await screen.findByLabelText(/Görünen ad/);
    expect(screen.getByLabelText(/E-posta/)).toBeDisabled();
    await user.clear(name);
    await user.type(name, 'Ayşe Y.');
    await user.click(screen.getByRole('button', { name: 'Profili kaydet' }));
    await waitFor(() => expect(account.updateProfile).toHaveBeenCalledWith({ displayName: 'Ayşe Y.' }));
    expect(await screen.findByText('Profil güncellendi.')).toBeInTheDocument();
  });

  it('sunucunun alan hatasını alanın altında gösterir', async () => {
    const error = new ApiError({
      type: 't', title: 'VALIDATION_FAILED', status: 400, code: 'VALIDATION_FAILED', traceId: 'tr',
      errors: [{ field: 'phone', code: 'INVALID_FORMAT' }], extensions: {},
    });
    const account = accountWith(async () => Promise.reject(error));
    renderApp({ route: '/hesabim', overrides: { account } });
    const user = userEvent.setup();
    const phone = await screen.findByLabelText(/Telefon/);
    await user.clear(phone);
    await user.type(phone, '0555');
    await user.click(screen.getByRole('button', { name: 'Profili kaydet' }));
    expect(await screen.findByLabelText(/Telefon/)).toHaveAttribute('aria-invalid', 'true');
  });

  it('parola formu profil sayfasında da var', async () => {
    renderApp({ route: '/hesabim', overrides: { account: accountWith(async () => profile) } });
    expect(await screen.findByRole('heading', { name: 'Parola değiştir' })).toBeInTheDocument();
    expect(screen.getByLabelText(/Mevcut parola/)).toBeInTheDocument();
  });
});
