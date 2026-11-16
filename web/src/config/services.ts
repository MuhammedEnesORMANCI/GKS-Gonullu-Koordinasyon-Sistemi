/**
 * Servis uygulaması seçimi: TEK yapılandırma dosyası.
 *
 * Her kaynak için `http` (gerçek backend, backend-api-design.md'ye birebir) veya `mock` (bellek içi örnek veri)
 * seçilir. `mock` kullanan ekranlar arayüzde "Örnek veri" rozetiyle işaretlenir.
 *
 * Varsayılan (`contract`) ön ayar: tüm kaynaklar `http`. Backend'de karşılıkları var:
 *   - auth          ID-01 – ID-04, ID-06, ID-08 (giriş, TOTP kurulum/doğrulama, parola değişikliği)
 *   - meContext     ID-11 GET /me/context
 *   (account: ID-09 GET /me, ID-10 PATCH /me; ayrı anahtarı yok, `auth` kaynağının modunu izler)
 *   - operations    OR-05 GET, OR-02 PATCH, OR-03 POST …/state
 *   - operationList OR-04 GET /operations
 *   - participants  OR-06 GET /operations/{id}/participants
 *   - groups        GR-01 … GR-08
 *   - tasks         TK-01 … TK-09
 *   - invitations   ID-12 – ID-14
 *   - notifications NT-04, NT-05, NT-07, NT-08 (gelen kutusu, LD-60)
 *   - announcements NT-01, NT-02, NT-03, NT-06
 *   - roleAssignments AC-01 – AC-03 (rol atama ekranı, yalnızca ADM)
 *   - users         ID-19 – ID-25 (kullanıcı ve hesap yönetimi)
 *   - audit         AU-01 (denetim kayıtları, salt-okur)
 *   - alerts        AL-01 – AL-16 (acil alarm yönetimi; KOR ve ADM)
 *   - locations     LC-05 (son konumlar; yalnızca KOR)
 *   - realtime      RT-01 (SSE: gelen kutusu ve duyuruların canlı yenilenmesi)
 *   - publicAnnouncements  kamu duyuruları (ADR-023; halka açık site, ADM yönetimi)
 *   - messaging     mesaj kanalları (ADR-024)
 *   - map           operasyon haritası (ADR-025)
 *
 * `npm run dev:mock` (Vite `--mode mock`) veya `VITE_SERVICE_PRESET=mock` ile tüm kaynaklar örnek veriye geçer
 * (backend olmadan tutarlı tanıtım).
 */

export type ServiceMode = 'http' | 'mock';

export type ServiceResource =
  | 'auth'
  | 'meContext'
  | 'operations'
  | 'operationList'
  | 'participants'
  | 'groups'
  | 'tasks'
  | 'invitations'
  | 'notifications'
  | 'announcements'
  | 'roleAssignments'
  | 'users'
  | 'audit'
  | 'alerts'
  | 'locations'
  | 'realtime'
  | 'publicAnnouncements'
  | 'messaging'
  | 'map';

export type ServiceConfig = Record<ServiceResource, ServiceMode>;

export const CONTRACT_PRESET: ServiceConfig = {
  auth: 'http',
  meContext: 'http',
  operations: 'http',
  operationList: 'http',
  participants: 'http',
  groups: 'http',
  tasks: 'http',
  invitations: 'http',
  notifications: 'http',
  announcements: 'http',
  roleAssignments: 'http',
  users: 'http',
  audit: 'http',
  alerts: 'http',
  locations: 'http',
  realtime: 'http',
  publicAnnouncements: 'http',
  messaging: 'http',
  map: 'http',
};

export const MOCK_PRESET: ServiceConfig = {
  auth: 'mock',
  meContext: 'mock',
  operations: 'mock',
  operationList: 'mock',
  participants: 'mock',
  groups: 'mock',
  tasks: 'mock',
  invitations: 'mock',
  notifications: 'mock',
  announcements: 'mock',
  roleAssignments: 'mock',
  users: 'mock',
  audit: 'mock',
  alerts: 'mock',
  locations: 'mock',
  realtime: 'mock',
  publicAnnouncements: 'mock',
  messaging: 'mock',
  map: 'mock',
};

function selectPreset(): ServiceConfig {
  const preset = import.meta.env.VITE_SERVICE_PRESET as string | undefined;
  return preset === 'mock' || import.meta.env.MODE === 'mock' ? MOCK_PRESET : CONTRACT_PRESET;
}

export const serviceConfig: ServiceConfig = selectPreset();
