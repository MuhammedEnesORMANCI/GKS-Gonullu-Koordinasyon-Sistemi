import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../api/problem';
import { CONTRACT_PRESET, MOCK_PRESET } from '../../config/services';
import type { AuthService, MeContextService } from '../../services/contracts';
import { mockMeContextService } from '../../services/mock/services';
import { renderApp } from '../../test/renderApp';

function authWith(login: AuthService['login'], extra: Partial<AuthService> = {}): AuthService {
  return {
    fetchCsrf: vi.fn(async () => undefined),
    login: vi.fn(login),
    enrollMfa: vi.fn(async () => ({ otpauthUri: 'otpauth://totp/GKS:t?secret=ABCDEFGHIJKLMNOP', secret: 'ABCDEFGHIJKLMNOP' })),
    verifyMfa: vi.fn(async () => ({ mfaRequired: false, mustChangePassword: false })),
    changePassword: vi.fn(async () => undefined),
    logout: vi.fn(async () => undefined),
    ...extra,
  };
}

const validation = (field: string, code: string) =>
  new ApiError({ type: 't', title: 'VALIDATION_FAILED', status: 400, code: 'VALIDATION_FAILED', traceId: 'tr', errors: [{ field, code }], extensions: {} });

async function enterCode(code: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/Doğrulama kodu/), code);
  await user.click(screen.getByRole('button', { name: 'Doğrula' }));
  return user;
}

const apiError = (status: number, code: string, retryAfter?: number) =>
  new ApiError({ type: 't', title: code, status, code, traceId: 'tr', extensions: {} }, retryAfter);

async function fillAndSubmit(email = 'kor@ornek.invalid', password = 'parola') {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/E-posta/), email);
  await user.type(screen.getByLabelText(/Parola/), password);
  await user.click(screen.getByRole('button', { name: 'Giriş yap' }));
  return user;
}

describe('WEB-01 giriş', () => {
  it('oturum yokken korunan rota girişe yönlenir ve CSRF çağrılır', async () => {
    const auth = authWith(async () => ({ mfaRequired: false, mustChangePassword: false }));
    renderApp({ route: '/operasyonlar', loggedIn: false, overrides: { auth } });
    expect(await screen.findByRole('heading', { name: 'Yönetici girişi' })).toBeInTheDocument();
    expect(auth.fetchCsrf).toHaveBeenCalled();
  });

  it('başarılı giriş → dönüş adresine gider, menü role göre çizilir', async () => {
    const auth = authWith(async () => ({ mfaRequired: false, mustChangePassword: false }));
    renderApp({ route: '/operasyonlar', loggedIn: false, persona: 'KOR', overrides: { auth } });
    await fillAndSubmit();
    expect(await screen.findByRole('heading', { name: 'Operasyonlar' })).toBeInTheDocument();
    expect(auth.login).toHaveBeenCalledWith({ email: 'kor@ornek.invalid', password: 'parola' });
  });

  it('AUTH_INVALID_CREDENTIALS: mesaj, parola temizlenir, e-posta korunur', async () => {
    renderApp({ loggedIn: false, overrides: { auth: authWith(async () => Promise.reject(apiError(401, 'AUTH_INVALID_CREDENTIALS'))) } });
    await fillAndSubmit('a@b.c', 'yanlis');
    expect(await screen.findByRole('alert')).toHaveTextContent('E-posta veya parola hatalı.');
    expect(screen.getByLabelText(/Parola/)).toHaveValue('');
    expect(screen.getByLabelText(/E-posta/)).toHaveValue('a@b.c');
  });

  it('ACCOUNT_LOCKED + Retry-After: geri sayımla düğme kapalı', async () => {
    renderApp({ loggedIn: false, overrides: { auth: authWith(async () => Promise.reject(apiError(423, 'ACCOUNT_LOCKED', 30))) } });
    const user = await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Çok sayıda hatalı deneme yapıldı.');
    await user.type(screen.getByLabelText(/Parola/), 'x');
    expect(screen.getByRole('button', { name: /Tekrar deneme: \d+ sn/ })).toBeDisabled();
  });

  it('CLIENT_NOT_ALLOWED girişte mobil uygulama mesajı (LD-03)', async () => {
    renderApp({ loggedIn: false, overrides: { auth: authWith(async () => Promise.reject(apiError(403, 'CLIENT_NOT_ALLOWED'))) } });
    await fillAndSubmit();
    expect(await screen.findByText('Bu hesap web paneline erişemez; mobil uygulamayı kullanın.')).toBeInTheDocument();
    expect(screen.queryByTestId('mfa-step')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Operasyonlar' })).not.toBeInTheDocument();
  });

  it('ACCOUNT_DISABLED mesajı', async () => {
    renderApp({ loggedIn: false, overrides: { auth: authWith(async () => Promise.reject(apiError(403, 'ACCOUNT_DISABLED'))) } });
    await fillAndSubmit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Hesabınız devre dışı.');
  });

  it('ilk giriş: TOTP kurulumu (ID-03) anahtarı gösterir, doğru kod (ID-04) oturumu açar', async () => {
    const auth = authWith(async () => ({ mfaRequired: true, mfaEnrollmentRequired: true, mfaTicket: 'bilet', mustChangePassword: false }));
    renderApp({ route: '/operasyonlar', loggedIn: false, persona: 'KOR', overrides: { auth } });
    await fillAndSubmit();
    expect(await screen.findByTestId('mfa-secret')).toHaveTextContent('ABCD EFGH IJKL MNOP');
    expect(auth.enrollMfa).toHaveBeenCalledWith('bilet');
    // Gerçek oturumu taklit et: mock bağlam ancak doğrulamadan sonra açılır.
    (auth.verifyMfa as ReturnType<typeof vi.fn>).mockImplementation(async () => {
      const { markMockLoggedIn } = await import('../../services/mock/services');
      markMockLoggedIn(true);
      return { mfaRequired: false, mustChangePassword: false };
    });
    await enterCode('123456');
    expect(await screen.findByRole('heading', { name: 'Operasyonlar' })).toBeInTheDocument();
    expect(auth.verifyMfa).toHaveBeenCalledWith('bilet', '123456');
  });

  it('kurulu TOTP: kurulum çağrılmaz; INVALID_CODE mesajı, kod temizlenir', async () => {
    const auth = authWith(async () => ({ mfaRequired: true, mfaEnrollmentRequired: false, mfaTicket: 'bilet', mustChangePassword: false }), {
      verifyMfa: vi.fn(async () => Promise.reject(validation('code', 'INVALID_CODE'))),
    });
    renderApp({ loggedIn: false, overrides: { auth } });
    await fillAndSubmit();
    await enterCode('111111');
    expect(await screen.findByRole('alert')).toHaveTextContent('Doğrulama kodu hatalı veya süresi geçmiş.');
    expect(screen.getByLabelText(/Doğrulama kodu/)).toHaveValue('');
    expect(auth.enrollMfa).not.toHaveBeenCalled();
    expect(screen.queryByRole('heading', { name: 'Operasyonlar' })).not.toBeInTheDocument();
  });

  it('AUTH_MFA_REQUIRED (bilet süresi doldu) → giriş adımına dönülür', async () => {
    const auth = authWith(async () => ({ mfaRequired: true, mfaTicket: 'bilet', mustChangePassword: false }), {
      verifyMfa: vi.fn(async () => Promise.reject(apiError(401, 'AUTH_MFA_REQUIRED'))),
    });
    renderApp({ loggedIn: false, overrides: { auth } });
    await fillAndSubmit();
    await enterCode('123456');
    expect(await screen.findByRole('alert')).toHaveTextContent('Doğrulama süresi doldu, yeniden giriş yapın.');
    expect(screen.getByRole('button', { name: 'Giriş yap' })).toBeInTheDocument();
  });

  it('mock giriş backend gibi davranır: KOR için önce TOTP adımı, kodla oturum açılır', async () => {
    renderApp({ route: '/operasyonlar', loggedIn: false, persona: 'KOR' });
    await fillAndSubmit();
    expect(await screen.findByTestId('mfa-step')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Operasyonlar' })).not.toBeInTheDocument();
    await enterCode('123456');
    expect(await screen.findByRole('heading', { name: 'Operasyonlar' })).toBeInTheDocument();
  });

  it('mustChangePassword: kabuk yerine parola ekranı (ID-08), değişince panel açılır', async () => {
    let mustChange = true;
    const base = mockMeContextService();
    const meContext: MeContextService = {
      getContext: async () => {
        const ctx = await base.getContext();
        return { ...ctx, session: { ...ctx.session, mustChangePassword: mustChange } };
      },
    };
    const auth = authWith(async () => ({ mfaRequired: false, mustChangePassword: true }), {
      changePassword: vi.fn(async () => {
        mustChange = false;
      }),
    });
    renderApp({ route: '/operasyonlar', loggedIn: true, persona: 'TEK', overrides: { auth, meContext } });
    expect(await screen.findByRole('heading', { name: 'Yeni parola belirleyin' })).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Mevcut parola/), 'gecici-parola');
    await user.type(screen.getByLabelText(/^Yeni parola \*/), 'yeni-guclu-parola');
    await user.type(screen.getByLabelText(/Yeni parola \(tekrar\)/), 'yeni-guclu-parola');
    await user.click(screen.getByRole('button', { name: 'Parolayı değiştir' }));
    expect(auth.changePassword).toHaveBeenCalledWith({ currentPassword: 'gecici-parola', newPassword: 'yeni-guclu-parola' });
    expect(await screen.findByRole('heading', { name: 'Operasyonlar' })).toBeInTheDocument();
  });

  it('parola ekranı: eşleşmeyen tekrar gönderimi engeller; mevcut parola hatası alanda gösterilir', async () => {
    const meContext: MeContextService = {
      getContext: async () => {
        const ctx = await mockMeContextService().getContext();
        return { ...ctx, session: { ...ctx.session, mustChangePassword: true } };
      },
    };
    const auth = authWith(async () => ({ mfaRequired: false, mustChangePassword: true }), {
      changePassword: vi.fn(async () => Promise.reject(validation('currentPassword', 'INVALID'))),
    });
    renderApp({ loggedIn: true, persona: 'TEK', overrides: { auth, meContext } });
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/Mevcut parola/), 'yanlis-parola');
    await user.type(screen.getByLabelText(/^Yeni parola \*/), 'yeni-guclu-parola');
    await user.type(screen.getByLabelText(/Yeni parola \(tekrar\)/), 'baska-parola-xx');
    expect(screen.getByText('Parolalar eşleşmiyor.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Parolayı değiştir' })).toBeDisabled();
    await user.clear(screen.getByLabelText(/Yeni parola \(tekrar\)/));
    await user.type(screen.getByLabelText(/Yeni parola \(tekrar\)/), 'yeni-guclu-parola');
    await user.click(screen.getByRole('button', { name: 'Parolayı değiştir' }));
    expect(await screen.findByText('Mevcut parola hatalı.')).toBeInTheDocument();
  });

  it('mock tanıtımda TOTP atlaması yalnızca açık seçimle', async () => {
    renderApp({ route: '/operasyonlar', loggedIn: false, persona: 'KOR' });
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText(/TOTP adımını atla/));
    await fillAndSubmit();
    expect(await screen.findByRole('heading', { name: 'Operasyonlar' })).toBeInTheDocument();
  });

  it('contract ön ayarında TOTP atlama kutusu hiçbir rolde görünmez', async () => {
    for (const persona of ['KOR', 'ADM'] as const) {
      const auth = authWith(async () => Promise.reject(apiError(403, 'CLIENT_NOT_ALLOWED')));
      const { unmount } = renderApp({ route: '/giris', loggedIn: false, persona, config: CONTRACT_PRESET, overrides: { auth } });
      expect(await screen.findByRole('heading', { name: 'Yönetici girişi' })).toBeInTheDocument();
      // Örnek veri rol seçicisi (meContext mock) görünebilir; atlama kutusu asla.
      expect(screen.queryByLabelText(/TOTP adımını atla/)).not.toBeInTheDocument();
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
      unmount();
    }
  });

  it('dev:mock ön ayarında atlama kutusu açık etiketle görünür', async () => {
    renderApp({ loggedIn: false, persona: 'KOR', config: MOCK_PRESET });
    expect(
      await screen.findByLabelText('Örnek veri: TOTP adımını atla (yalnızca tanıtım; gerçek sunucu bu oturumu açmaz)'),
    ).not.toBeChecked();
  });

  it('503 girişte oturum sonu sayılmaz, mesaj gösterilir', async () => {
    renderApp({ loggedIn: false, overrides: { auth: authWith(async () => Promise.reject(apiError(503, 'SERVICE_UNAVAILABLE'))) } });
    await fillAndSubmit();
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Sunucuya şu an ulaşılamıyor.'));
  });
});
