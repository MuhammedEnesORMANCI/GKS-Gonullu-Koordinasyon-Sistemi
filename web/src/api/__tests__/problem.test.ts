import { describe, expect, it } from 'vitest';
import { fieldErrorMap, messageFor } from '../errorMessages';
import { ApiError, networkError, parseRetryAfter, toProblem } from '../problem';

const err = (status: number, code: string, extra: Record<string, unknown> = {}, retryAfter?: number) =>
  new ApiError(toProblem(status, { type: 't', title: 'x', status, code, traceId: 'tr-9', ...extra }), retryAfter);

describe('toProblem', () => {
  it('bilinen alanları ayırır, kalanları uzantı yapar', () => {
    const p = toProblem(409, {
      type: '/problems/already-exists',
      title: 'Zaten var',
      status: 409,
      code: 'ALREADY_EXISTS',
      traceId: 'abc',
      conflict: 'ALREADY_MEMBER',
      errors: [{ field: 'name', code: 'TOO_LONG' }],
    });
    expect(p.code).toBe('ALREADY_EXISTS');
    expect(p.traceId).toBe('abc');
    expect(p.extensions).toEqual({ conflict: 'ALREADY_MEMBER' });
    expect(p.errors).toEqual([{ field: 'name', code: 'TOO_LONG' }]);
  });

  it('iç içe extensions nesnesini de kabul eder', () => {
    const p = toProblem(409, { code: 'VERSION_CONFLICT', status: 409, extensions: { currentVersion: 4 } });
    expect(p.extensions).toEqual({ currentVersion: 4 });
  });

  it('gövdesiz 503 SERVICE_UNAVAILABLE sayılır, diğerleri bilinmeyen', () => {
    expect(toProblem(503, undefined).code).toBe('SERVICE_UNAVAILABLE');
    expect(toProblem(500, 'html').code).toBe('UNKNOWN_ERROR');
  });
});

describe('parseRetryAfter', () => {
  it('saniye ve HTTP tarihi', () => {
    expect(parseRetryAfter('30')).toBe(30);
    expect(parseRetryAfter(null)).toBeUndefined();
    const now = Date.UTC(2026, 0, 1, 0, 0, 0);
    expect(parseRetryAfter(new Date(now + 10_000).toUTCString(), now)).toBe(10);
  });
});

describe('messageFor (mapping §0.4)', () => {
  it('genel katalog', () => {
    expect(messageFor(err(401, 'AUTH_INVALID_CREDENTIALS'))).toBe('E-posta veya parola hatalı.');
    expect(messageFor(err(403, 'FORBIDDEN'))).toBe('Bu işlem için yetkiniz yok.');
    expect(messageFor(err(409, 'LEADER_REQUIRED'))).toBe('Grup lidersiz bırakılamaz. Önce yeni bir lider seçin.');
    expect(messageFor(err(503, 'SERVICE_UNAVAILABLE'))).toBe('Sunucuya şu an ulaşılamıyor. Biraz sonra yeniden denenecek.');
    expect(messageFor(networkError())).toBe('Sunucuya ulaşılamıyor. Bağlantınızı kontrol edin.');
  });

  it('mesajsız kodlar null döner', () => {
    expect(messageFor(err(401, 'AUTH_TOKEN_EXPIRED'))).toBeNull();
    expect(messageFor(err(401, 'AUTH_UNAUTHENTICATED'))).toBeNull();
  });

  it('ALREADY_EXISTS conflict alanına göre', () => {
    expect(messageFor(err(409, 'ALREADY_EXISTS', { conflict: 'ALREADY_MEMBER' }))).toBe('Kişi zaten bu grubun üyesi.');
    expect(messageFor(err(409, 'ALREADY_EXISTS', { conflict: 'ASSIGNMENT_ACTIVE' }))).toBe('Kişinin bu görevde etkin ataması var.');
    expect(messageFor(err(409, 'ALREADY_EXISTS', { conflict: 'YENI' }))).toBe('Bu kayıt zaten var.');
  });

  it('CLIENT_NOT_ALLOWED giriş adımında farklı mesaj (LD-03)', () => {
    expect(messageFor(err(403, 'CLIENT_NOT_ALLOWED'), 'login')).toBe('Bu hesap web paneline erişemez; mobil uygulamayı kullanın.');
    expect(messageFor(err(403, 'CLIENT_NOT_ALLOWED'))).toBe('Bu işlem iki adımlı doğrulamalı web oturumu gerektirir.');
  });

  it('RATE_LIMITED geri sayım saniyesini yazar', () => {
    expect(messageFor(err(429, 'RATE_LIMITED', {}, 12))).toBe('Çok fazla deneme yapıldı. 12 sn sonra tekrar deneyin.');
  });

  it('operasyon durum geçişi hedef durumu adlandırır', () => {
    expect(messageFor(err(409, 'INVALID_STATE_TRANSITION', { requestedState: 'ACTIVE' }), 'operationState')).toBe(
      "Operasyon bu durumdan 'Aktif' durumuna geçemez.",
    );
  });

  it('bilinmeyen kod iz numarasını gösterir', () => {
    expect(messageFor(err(500, 'SOMETHING_NEW'))).toBe('Beklenmeyen bir hata oluştu. (İz no: tr-9)');
  });

  it('alan hataları', () => {
    const e = err(400, 'VALIDATION_FAILED', { errors: [{ field: 'name', code: 'DUPLICATE' }, { field: 'name', code: 'TOO_LONG' }, { field: 'currentPassword', code: 'INVALID' }] });
    expect(fieldErrorMap(e)).toEqual({ name: 'Bu ad bu operasyonda zaten kullanılıyor.', currentPassword: 'Mevcut parola hatalı.' });
    expect(messageFor(err(400, 'VALIDATION_FAILED', { errors: [{ field: 'code', code: 'INVALID_CODE' }] }))).toBe(
      'Doğrulama kodu hatalı veya süresi geçmiş.',
    );
  });
});
