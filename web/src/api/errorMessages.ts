import { ApiError, NETWORK_ERROR, isApiError, type FieldError } from './problem';

/**
 * Hata kodu → Türkçe kullanıcı mesajı (web-screen-api-mapping.md §0.4).
 * `null` = mesaj gösterilmez (istemci eylemi yeterli).
 */
const CATALOG: Record<string, string | null> = {
  VALIDATION_FAILED: 'Formda düzeltilmesi gereken alanlar var.',
  AUTH_UNAUTHENTICATED: null,
  CSRF_TOKEN_INVALID: 'Oturum doğrulanamadı. Sayfayı yenileyin.',
  AUTH_INVALID_CREDENTIALS: 'E-posta veya parola hatalı.',
  AUTH_MFA_REQUIRED: null,
  AUTH_TOKEN_EXPIRED: null,
  AUTH_SESSION_REVOKED: 'Oturumunuz sonlandırıldı. Lütfen yeniden giriş yapın.',
  AUTH_PASSWORD_CHANGE_REQUIRED: 'Devam etmek için yeni bir parola belirleyin.',
  ACCOUNT_LOCKED: 'Çok sayıda hatalı deneme yapıldı. Bir süre sonra tekrar deneyin.',
  ACCOUNT_DISABLED: 'Hesabınız devre dışı. Kurum yöneticinizle iletişime geçin.',
  FORBIDDEN: 'Bu işlem için yetkiniz yok.',
  CLIENT_NOT_ALLOWED: 'Bu işlem iki adımlı doğrulamalı web oturumu gerektirir.',
  NOT_FOUND: 'Kayıt bulunamadı veya kaldırılmış.',
  INVITATION_INVALID: 'Davet artık geçerli değil (süresi dolmuş, kullanılmış veya iptal edilmiş).',
  OPERATION_NOT_ACTIVE: 'Bu işlem yalnızca aktif operasyonda yapılabilir.',
  OPERATION_CLOSED: 'Operasyon kapandı; bu kayıt artık değiştirilemez.',
  GROUP_FULL: 'Grup 50 üye sınırına ulaştı.',
  NOT_A_PARTICIPANT: 'Seçilen kişi bu operasyona katılmamış. Önce operasyona davet edin.',
  LEADER_REQUIRED: 'Grup lidersiz bırakılamaz. Önce yeni bir lider seçin.',
  CAPACITY_FULL: 'Görevin kapasitesi dolu.',
  CAPACITY_BELOW_ACCEPTED: 'Kapasite, görevi kabul etmiş kişi sayısının altına indirilemez.',
  ASSIGNMENT_NOT_PENDING: 'Bu atama artık yanıt bekleme durumunda değil.',
  INVALID_STATE_TRANSITION: 'Bu durum değişikliği yapılamaz. Güncel durum yüklendi.',
  AUDIENCE_CHANGED:
    'Önizlemeden sonra alıcı listesi değişti. Yeni listeyi kontrol edip yeniden onaylayın.',
  ALERT_NOT_OPEN: 'Bu alarm iptal edilmiş veya kapatılmış.',
  LOCATION_SHARING_STOPPED: null,
  VERSION_CONFLICT:
    'Bu kayıt siz düzenlerken değişti. Güncel hâli yüklendi; değişikliklerinizi kontrol edin.',
  IDEMPOTENCY_KEY_MISMATCH: 'İstek tutarsız. Sayfayı yenileyip yeniden deneyin.',
  AUDIENCE_TOO_LARGE: 'Alıcı sayısı 500 sınırını aşıyor. Hedefi daraltın.',
  SERVICE_UNAVAILABLE: 'Sunucuya şu an ulaşılamıyor. Biraz sonra yeniden denenecek.',
  [NETWORK_ERROR]: 'Sunucuya ulaşılamıyor. Bağlantınızı kontrol edin.',
};

const CONFLICT_MESSAGES: Record<string, string> = {
  INVITATION_PENDING: 'Bu kişiye bu kapsamda bekleyen bir davet zaten var.',
  ALREADY_PARTICIPANT: 'Bu kişi zaten operasyon katılımcısı.',
  EMAIL_REGISTERED: 'Bu e-posta kurumda zaten kayıtlı. Kişiyi operasyona davet edin.',
  ALREADY_MEMBER: 'Kişi zaten bu grubun üyesi.',
  ROLE_ACTIVE: 'Bu rol ataması zaten etkin.',
  ASSIGNMENT_ACTIVE: 'Kişinin bu görevde etkin ataması var.',
  ALERT_ALREADY_CORRECTED: 'Bu alarm için düzeltme zaten gönderilmiş.',
};

/** Ekrana özel bağlam; genel katalogdan farklı mesaj gerektiren yerler (mapping §2). */
export type ErrorContext = 'default' | 'login' | 'mfa' | 'operationState' | 'invitation' | 'roleRevoke' | 'userAdmin';

/** Kodu bilinmeyen veya 5xx hatası için iz numaralı mesaj. */
function unexpected(traceId: string | undefined): string {
  return traceId
    ? `Beklenmeyen bir hata oluştu. (İz no: ${traceId})`
    : 'Beklenmeyen bir hata oluştu.';
}

const STATE_LABELS: Record<string, string> = {
  PLANNED: 'Planlandı',
  ACTIVE: 'Aktif',
  SUSPENDED: 'Askıda',
  COMPLETED: 'Tamamlandı',
  CANCELLED: 'İptal',
};

/**
 * Kullanıcıya gösterilecek mesajı döndürür; gösterilmeyecekse `null`.
 */
export function messageFor(error: unknown, context: ErrorContext = 'default'): string | null {
  if (!isApiError(error)) return unexpected(undefined);
  const { code } = error;

  if (context === 'login' && code === 'CLIENT_NOT_ALLOWED') {
    return 'Bu hesap web paneline erişemez; mobil uygulamayı kullanın.';
  }
  if (context === 'mfa' && code === 'AUTH_INVALID_CREDENTIALS') {
    return 'Doğrulama kodu hatalı veya süresi geçmiş.';
  }
  if (context === 'userAdmin' && code === 'FORBIDDEN') {
    return 'Bu hesapta bu işlemi yapamazsınız (kendi hesabınız, kurum yöneticisi hesabı veya yetkiniz dışında).';
  }
  if (context === 'userAdmin' && code === 'INVALID_STATE_TRANSITION') {
    return 'Hesabın durumu bu işlem için uygun değil (ör. zaten devre dışı veya doğrulama kurulu değil). Güncel durum yüklendi.';
  }
  if (context === 'roleRevoke' && code === 'FORBIDDEN') {
    return 'Kendi kurum yöneticisi rolünüzü veya kurumun son kurum yöneticisi rolünü kaldıramazsınız.';
  }
  if (context === 'roleRevoke' && code === 'INVALID_STATE_TRANSITION') {
    return 'Bu rol zaten kaldırılmış.';
  }
  if (context === 'invitation' && code === 'INVALID_STATE_TRANSITION') {
    return 'Davet artık bekleme durumunda değil.';
  }
  if (context === 'operationState' && code === 'INVALID_STATE_TRANSITION') {
    const target = error.extension<string>('requestedState');
    const label = target ? (STATE_LABELS[target] ?? target) : undefined;
    return label
      ? `Operasyon bu durumdan '${label}' durumuna geçemez.`
      : CATALOG.INVALID_STATE_TRANSITION ?? null;
  }

  if (code === 'ALREADY_EXISTS') {
    const conflict = error.extension<string>('conflict');
    return (conflict && CONFLICT_MESSAGES[conflict]) || 'Bu kayıt zaten var.';
  }
  if (code === 'RATE_LIMITED') {
    const n = error.retryAfterSeconds;
    return n !== undefined
      ? `Çok fazla deneme yapıldı. ${n} sn sonra tekrar deneyin.`
      : 'Çok fazla deneme yapıldı. Biraz sonra tekrar deneyin.';
  }
  if (code === 'ACTIVE_OPERATION_LIMIT') {
    const limit = error.extension<number>('limit');
    return `Aynı anda en fazla ${limit ?? '?'} aktif operasyon olabilir.`;
  }
  if (code === 'VALIDATION_FAILED') {
    const invalidCode = error.errors.find((e) => e.field === 'code' && e.code === 'INVALID_CODE');
    if (invalidCode) return 'Doğrulama kodu hatalı veya süresi geçmiş.';
    const emptyAudience = error.errors.find((e) => e.code === 'EMPTY_AUDIENCE');
    if (emptyAudience) return 'Seçilen hedefte alıcı yok.';
  }

  if (Object.prototype.hasOwnProperty.call(CATALOG, code)) {
    return CATALOG[code] ?? null;
  }
  return unexpected(error.traceId);
}

/** Alan hata kodu → Türkçe kısa mesaj (form alanlarının altına). */
export function fieldMessage(error: FieldError): string {
  switch (error.code) {
    case 'REQUIRED':
      return 'Bu alan zorunludur.';
    case 'TOO_LONG':
      return 'Çok uzun.';
    case 'TOO_SHORT':
      return 'Çok kısa.';
    case 'OUT_OF_RANGE':
      return 'İzin verilen aralığın dışında.';
    case 'INVALID_FORMAT':
      return 'Biçim geçersiz.';
    case 'READ_ONLY':
      return 'Bu alan değiştirilemez.';
    case 'UNKNOWN_FIELD':
      return 'Tanınmayan alan.';
    case 'NOT_ALLOWED_VALUE':
      return 'Bu değere izin verilmiyor.';
    case 'DUPLICATE':
      return 'Bu ad bu operasyonda zaten kullanılıyor.';
    case 'INVALID_CODE':
      return 'Doğrulama kodu hatalı veya süresi geçmiş.';
    case 'INVALID':
      return error.field === 'currentPassword' ? 'Mevcut parola hatalı.' : 'Geçersiz değer.';
    case 'EMPTY_AUDIENCE':
      return 'Seçilen hedefte alıcı yok.';
    default:
      return 'Geçersiz değer.';
  }
}

/** `errors[]` → { alan: mesaj } (yalnızca ilk hata). */
export function fieldErrorMap(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError) || error.code !== 'VALIDATION_FAILED') return {};
  const map: Record<string, string> = {};
  for (const fe of error.errors) {
    if (!(fe.field in map)) map[fe.field] = fieldMessage(fe);
  }
  return map;
}
