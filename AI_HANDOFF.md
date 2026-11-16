# GKS — Yapay Zekâ Devir ve Devam Belgesi

> Hazırlanma: 15.11.2026; **son güncelleme: 16.11.2026 23.30 (RAPOR_6: mesajlaşma ADR-024, harita ADR-025, Genel Acil Alarm ADR-026, demo verisi aracı)**; önceki güncelleme: 16.11.2026 gece oturumu (ADR-015, Android duyuru ve tarih seçici, web rol atama ve kullanıcı yönetimi, sayfalama birleştirme). Kaynaklar: proje dosyaları (klasör yapısı, migration listesi, controller/test dizinleri, yapılandırma), `PROJECT_PROGRESS.md`, `docs/architecture/design/team/TASKS.md` (LD-xx kararları), `docs/architecture/decisions.md` (ADR-xx) ve bu projede yürütülen geliştirme oturumlarının kayıtları.
> **Kural:** Bu belgede parola, belirteç, TOTP anahtarı veya `.env` içeriği yoktur ve eklenmemelidir.
> Kesinlik işaretleri: **[doğrulandı]** = kodda/testte görüldü; **[doğrulanmadı]** = yalnızca tasarım belgesinde veya hiç denenmedi.

---

## 1. Projenin amacı

**GKS (Gönüllü Koordinasyon Sistemi)**, afet ve acil durum operasyonlarında gönüllülerin davet edilmesini, operasyonlara ve gruplara katılmasını, görevlendirilmesini, bilgilendirilmesini ve (planlanan) acil alarm/konum takibini sağlayan bir sistemdir. Gereksinim kaynağı: `Gonullu_Koordinasyon_Sistemi_Gereksinim_Analizi.docx`, kapsam: `docs/analysis/mvp-scope.md`.

**Roller** (`backend/src/main/java/org/gks/backend/access/Role.java`):

| Rol | Kapsam | Açıklama |
|---|---|---|
| ADM | Kurum | Kurum yöneticisi: operasyon oluşturma, rol atama, kurum geneli duyuru, kullanıcı yönetimi; **ADR-015: KOR ataması olmadan tüm operasyonlarda KOR'un operasyon yönetimi yetkileri** (konum ve alarm hariç) |
| KOR | Operasyon | Koordinatör: kendi operasyonunda görev, grup, davet, duyuru yönetimi |
| GON | Operasyon | Gönüllü (operasyon katılımcısı) |
| TEK | Kurum | Teknik destek: kullanıcı listesi (yalnızca ad + durum), hesabı devre dışı bırakma (ADM hesabı hariç), oturum iptali — ID-19/21/23 (16.11), yalnızca web |
| UYM | Kurum | Uyum/denetim (denetim kayıtları) — modülü henüz yok |
| Grup lideri | Grup üyeliği (rol değil) | `groups.group_memberships.role = LEADER`; açıklama ve toplanma noktası |

**İstemciler:**
- **Web** (`web/`): ADM/KOR/TEK/UYM yönetim paneli. GON/lider web'e giremez (LD-03).
- **Android** (`android/`): gönüllü ve grup lideri uygulaması; ADR-014'ten sonra ADM/KOR için mobil yönetim (Yönetim sekmesi).

---

## 2. Teknoloji ve mimari

| Katman | Teknoloji | Konum |
|---|---|---|
| Backend | Spring Boot 4.1.2, Java 21 toolchain, Spring Modulith (modüler monolit, ADR-001), Spring Security, JdbcClient (JPA yalnızca doğrulama), Flyway | `backend/` |
| Veritabanı | PostgreSQL 16 (Docker), modül başına şema | `compose.yaml`, `backend/src/main/resources/db/migration/` |
| E-posta (geliştirme) | Mailpit (Docker; SMTP 1025, arayüz 8025) | `compose.yaml` |
| Web | React 18.3, TypeScript, Vite, MUI 5, TanStack Query 5, Vitest | `web/` |
| Android | Kotlin 1.9.22, Jetpack Compose (BOM 2024.02), Material 3, Retrofit 2.9 + OkHttp 4.12, kotlinx.serialization, Coroutines; AGP 8.2.2, Gradle 8.5, compile/target SDK 34, min SDK 26 | `android/` |

**Backend modülleri** (`backend/src/main/java/org/gks/backend/`): `identity` (kimlik, MFA, oturum, davet, profil), `access` (roller, yetki kararları), `organization` (operasyonlar), `groups`, `tasks`, `notifications` (gelen kutusu, duyuru, teslim kuyruğu). **Boş (yalnızca `package-info.java`) [doğrulandı]:** `alerts`, `location`, `audit`, `realtime`.

Modül sınırları `backend/src/test/java/org/gks/backend/ModularityTests.java` ile denetlenir (döngüsel bağımlılık yasak). Diğer modüllerin ihtiyacı olan veriler için arayüz–uygulama tersine çevrilir: ör. `access/AccessDirectory.java` (identity/organization uygular), `notifications/NotificationDirectories.java` (identity ve groups uygular).

**Ortak altyapı (kök paket):** `Idempotency.java` (§3.6), `ApiException.java` + `ApiProblems.java` (RFC 9457 hata gövdesi, `code`), `PageCursor.java` (imleçli sayfa), `SecurityConfiguration.java` (CSRF yalnızca çerezli isteklerde).

**Tasarım belgeleri:** `docs/architecture/design/team/backend-api-design.md` (uç tabloları, şemalar), `database-physical-design.md`, `docs/architecture/authorization.md`, `docs/architecture/communication.md` (bildirim), `docs/architecture/design/emergency-alert-design.md`.

---

## 3. Mevcut durum

### 3.1 Çalışan (kodda var, testlerle doğrulandı)

| Modül | Backend | Web | Android |
|---|---|---|---|
| Giriş, TOTP MFA, oturum yenileme, çıkış | ✅ | ✅ | ✅ (gönüllü + ADM/KOR MFA) |
| Davet (ID-12…18), davet koduyla kayıt | ✅ | ✅ oluştur/listele/iptal | ✅ kodla kayıt; operasyon davetini kabul/ret; yönetimde davet gönder/iptal |
| Hesap: profil, telefon, parola, zorunlu parola | ✅ | ✅ `/hesabim` | ✅ |
| Operasyon (OR-01…06) | ✅ | ✅ | ✅ Yönetim sekmesi (liste, ayrıntı, oluştur, düzenle, durum, ADM koordinatör atama) |
| Rol atama (AC-01…03) | ✅ | ✅ `/roller` | ✅ yalnızca koordinatör ekle/kaldır |
| Kullanıcı yönetimi (ID-19…25) | ✅ | ✅ `/kullanicilar` | – |
| ADM tüm operasyonlarda yönetim (ADR-015) | ✅ | ✅ | ✅ (izinlerden kendiliğinden) |
| Grup (GR-01…08) | ✅ | ✅ | ✅ gönüllü/lider ekranları + KOR grup yönetimi |
| Görev (TK-01…12) | ✅ | ✅ | ✅ gönüllü kabul/ret + KOR görev yönetimi |
| Bildirim gelen kutusu (NT-04/05/07/08) | ✅ | ✅ zil rozeti, `/bildirimler` | ✅ sekme, rozet, SSE + 30 sn yoklama yedeği |
| Tasarım sistemi + halka açık site (ADR-023) | – | ✅ `app/theme.ts`, `config/brand.ts`, `features/public/`, içerik `src/content/*.json` | ✅ `ui/theme/`, `res/values/brand.xml` |
| Kamu duyuruları (V16, ADR-023) | ✅ `notifications/PublicAnnouncement*`, `PublicRateLimiter` | ✅ `/panel/kamu-duyurulari`, site `/duyurular` | ✅ Duyurular sekmesi |
| Panel yeniden tasarımı + Özet (WEB-02) | – | ✅ `app/AppShell.tsx` (`usePanelNav`), `features/dashboard/HomePage.tsx`, `components/panel/` | – |
| Android yeniden tasarım + internetsiz rehber | – | – | ✅ `ui/guide/`, `ui/home/HomeLanding.kt`, `ui/announcements/`, `data/guide/GuideContent.kt` |
| FCM push + cihaz kaydı (ID-26, ADR-022) | ✅ `identity/DeviceController`, `notifications/FcmPushGateway` | – | ✅ `alarm/` (`Alarms`, `AlarmPolicy`, `GksMessagingService`, `DeviceRegistrar`) |
| Duyuru (NT-01/02/03/06) | ✅ | ✅ `/duyurular` | ✅ operasyon/grup/kişi hedefi, önizleme, >50 onay, geçmiş, kurum duyurusu |
| Denetim kaydı (AU-01, ADR-016) | ✅ `audit/` (V12) | ✅ `/denetim` salt-okur | – |
| Gerçek zamanlı akış (RT-01, ADR-017) | ✅ `realtime/` | ✅ `app/live.ts` | ✅ `data/realtime/` (ADR-021) |
| Acil alarm (AL-01, 03–16, ADR-018) | ✅ `alerts/` (V13) | ✅ `/operasyonlar/:id/alarmlar`, `/alarmlar/:id`, `/alarmlar` | ✅ gönüllü: alarm bandı + yanıt ekranı (`ui/alerts/`) |
| Konum (LC-01…05, ADR-019) | ✅ `location/` (V14) | ✅ `/operasyonlar/:id/konum` tablo + harita | ✅ izin akışı + ön plan servisi (`location/`, `ui/location/`) |
| Mesajlaşma (ADR-024) | ✅ `messaging/` (V17) | ✅ `/panel/mesajlar` | ✅ Mesajlar sekmesi, çevrimdışı kuyruk (`data/messaging/`, `ui/messaging/`) |
| Harita (ADR-025) | ✅ operasyon bölgesi (V18), `/map/operations` (`alerts/OperationMap*`) | ✅ MapLibre `components/map/`, `features/map/` | ✅ MapLibre `ui/map/` |
| Genel Acil Alarm (ADR-026) | ✅ `alerts.scope=GENERAL` (V19), `/general-alerts` | ✅ üst çubuk, `GeneralAlertDialog` | ✅ `ui/alerts/AlertManagementScreens.kt` (genel alarm, açık alarmlar, takip) |
| Demo verisi aracı (Görev 25) | ✅ `devseed/` (`demo-seed` profili) | – | – |

### 3.2 Planlanan / yok

Görev 23 kalanı ve Görev 24 (a–e) (RAPOR_6 §7), alarm taslak düzenleme (AL-02), kendi kendine parola sıfırlama e-postası (ID-27/28), onay metni (ID-29), hesap kapatma (ID-31). Ayrıntı ve öncelik: §11.

### 3.3 Gerçek cihazda/kullanıcıyla doğrulanmamış

- Android uygulaması emülatörde açılmadı; ekranlar (16.11'deki duyuru yönetimi ve tarih-saat seçici dahil) yalnızca derleme + birim testleriyle doğrulandı. Web'in yeni `/roller` ve `/kullanicilar` ekranları tarayıcıda gerçek backend'le denenmedi (mock + birim testleri). Kullanıcı gerçek telefonda test etmeye başladı (USB, `adb reverse`); sonuç bu belgeye yansımadı.
- ADM/KOR için Android MFA girişi gerçek hesapla denenmedi (backend entegrasyon testi gerçek TOTP ile geçiyor).
- Gerçek SMTP yok; davet e-postası yalnızca Mailpit'e gider.
- 16.11 sabahı eklenen alarm, konum, denetim ve SSE ekranları tarayıcıda/cihazda denenmedi; Android konum servisi gerçek GPS ile çalıştırılmadı. Backend bu modüllerde Testcontainers entegrasyon testleriyle doğrulandı.

---

## 4. Güvenlik

**Kimlik (ADR-012):** Erişim belirteci JWT HS256, 15 dk; JWT'de rol yok (`sub`, `sid`, `cli`, `mfa`). Yenileme belirteci opak, veritabanında yalnızca özetiyle; her kullanımda döndürülür, tekrar kullanımda zincir iptal edilir (tolerans 10 sn, LD-18). Her istekte oturum iptali veritabanından denetlenir (LD-47); veritabanı erişilemezse `503`, oturum silinmez (LD-48).

| Oturum | Taşıma | Ömür |
|---|---|---|
| Web | `__Host-access` / `__Secure-refresh` çerezleri + CSRF (`X-CSRF-Token`) | 12 sa mutlak (LD-19) |
| Android gönüllü (MFA'sız) | `Authorization: Bearer`, gövdede belirteçler, Keystore AES-GCM ile şifreli depo | 30 gün kayan |
| Android yönetim (ADM/KOR, MFA'lı) | Aynı | **12 sa mutlak** (ADR-014) |

**MFA (K-19, LD-57):** ADM ve KOR için TOTP zorunlu (web ve ADR-014 sonrası Android). Bilet 5 dk, tek kullanımlık, en fazla 5 hatalı kod; TOTP anahtarı AES-256-GCM ile şifreli; aynı 30 sn adımı ikinci kez kabul edilmez.

**ADR-014 (ADR-013'ün yerine geçti; LD-61):**
- Yetki kapısı: `access/Grants.java` → `corporate(...)`. Web: ADM/KOR MFA ister, TEK/UYM istemez. **Android: kurumsal izin yalnızca ADM/KOR + `mfaVerified`**; TEK/UYM izinleri mobile taşınmaz; MFA'sız gönüllü oturumu hiçbir kurumsal izin taşımaz.
- Uç kapısı: `access/Subject.java` → `requireManagementSession()` (web veya MFA'lı Android). `requireAndroid()` gönüllü uçlarında (görev yanıtı, davet yanıtı, `/me/assignments`).
- DB kısıtı `ck_refresh_tokens_mfa_absolute`: MFA'lı hiçbir zincir 12 saati aşamaz (V11).
- Android istemcisi: ekran kilidi olmayan cihazda yönetim (MFA) girişi yapılmaz; TOTP anahtarı panoya hassas işaretle kopyalanır.

**ADR-015 (16.11.2026, LD-62):** `access/Grants.coordinates(op)` = o operasyonun KOR'u **veya** ADM; `Grants.coordinator(...)` istemci/MFA kapısıyla birlikte. Görev (`RoleBasedTaskAccess`), grup/üyelik/toplanma noktası (`RoleBasedGroupAccess`), operasyon duyurusu (`RoleBasedAnnouncementAccess`) ve operasyon yönetimi (`RoleBasedOperationAccess`) bu tek yöntemi kullanır. `EffectivePermissions.ADM_OPERATION` (= KOR seti − `location.view` − `alert.*`) ADM'ye kurumdaki tüm operasyonlar için `/me/context`'te döner. **Konum ve alarm modülleri yazılırken `coordinates` kullanılmamalı** (K-15; ayrı karar). ADM'nin bu işlemleri audit gelince kaydedilmeli.

**Yetki modeli (ADR-002):** Her karar `can()` ile access modülünde, roller her istekte veritabanından (LD-55). Önemli kurallar [doğrulandı, testlerle]:
- Operasyon yönetimi: ADM tümü, KOR yalnızca kendi operasyonu. Operasyon oluşturma ve rol atama yalnızca ADM.
- Grup kurma/üyelik/lider değişikliği ve görev yönetimi: operasyonun KOR'u ve (ADR-015) ADM; KOR başka operasyonda `403`.
- Grup açıklaması ve toplanma noktası: KOR veya grubun lideri.
- Duyuru: operasyon duyurusu KOR ve (ADR-015) ADM (operasyon `ACTIVE` olmalı, N-19), kurum geneli duyuru ADM.
- Kullanıcı yönetimi (`access/UserAdminAccess`): liste/ayrıntı ADM tam, TEK ad+durum, KOR yalnızca `operationId` ile kendi operasyonu; disable ADM/TEK (TEK ADM hesabına dokunamaz), enable ADM, oturum iptali ADM/TEK, parola ve MFA sıfırlama ADM; kendi hesabında yönetim işlemi `403`.

**İstemci izin görünürlüğü:** `GET /me/context` → `permissions` sunucuda bu oturum için hesaplanır (`access/EffectivePermissions.java`); istemciler menüyü buna göre çizer, sunucu yine her istekte denetler.

---

## 5. Veritabanı

**Şemalar** (V1): `identity`, `access`, `organization`, `groups`, `tasks`, `location`, `alerts`, `notifications`, `audit`, `platform` (Flyway geçmişi, idempotency, Modulith olay kaydı).

**Flyway sürümleri [doğrulandı]:**

| Sürüm | İçerik |
|---|---|
| V1 | Modül şemaları |
| V2 | `organization.organizations`, `operations` |
| V3 | `identity.users`, `devices`, `refresh_tokens` |
| V4 | `groups.groups`, `group_memberships` (toplanma noktası grup alanı) |
| V5 | `platform.idempotency_keys` |
| V6 | `tasks.tasks`, `task_assignments`, `task_state_changes` |
| V7 | `access.role_assignments` |
| V8 | `identity.mfa_tickets`, TOTP alanları |
| V9 | `identity.invitations` |
| V10 | `notifications.notifications`, `deliveries` (IN_APP gelen kutusu + PUSH kuyruğu) |
| V11 | MFA bileti istemci/kurulum alanları; `ck_refresh_tokens_mfa_absolute` |
| V12 | `audit.audit_entries` (+ `actor_basis`); UPDATE/DELETE/TRUNCATE tetikleyiciyle reddedilir |
| V13 | `alerts.alerts`, `alerts.alert_recipients` |
| V14 | `location.location_sharings`, `location.last_locations` (`retain_until`), `audit.location_view_windows` |

**Önemli ilişkiler:** Katılım = `access.role_assignments` (GON/OPERATION). KOR ataması da aynı tablo. Grup üyeliği `groups.group_memberships` (LEADER/MEMBER). Görev atamaları `tasks.task_assignments` (PENDING/ACCEPTED/REJECTED/CAPACITY_FULL/REMOVED). Bildirim ↔ teslim: bir `notifications` satırı, alıcı başına `deliveries` satırları; tekrar önleme `uq_notifications_idempotency_key`.

**Veri koruma kuralları:**
- Mevcut geliştirme veritabanında gerçek test verisi var (operasyon, grup, gönüllü hesapları). **Silme, sıfırlama, `docker compose down -v` yasak.**
- Şema değişikliği yalnızca yeni Flyway migration ile (V15+); mevcut migration dosyaları değiştirilmez. `audit.audit_entries` yalnızca eklenebilir (tetikleyici); kayıtlar silinmez.
- Testler Testcontainers ile ayrı, geçici bir PostgreSQL kullanır; geliştirme veritabanına dokunmaz.

---

## 6. API

Temel yol `/api/v1`. Uç kimlikleri ve şemalar: `docs/architecture/design/team/backend-api-design.md`.

| Grup | Uçlar | Controller |
|---|---|---|
| Kimlik | `/auth/csrf`, `/auth/login`, `/auth/mfa/enroll`, `/auth/mfa`, `/auth/refresh`, `/auth/logout`, `/auth/logout-all` | `identity/AuthController.java` |
| Profil | `GET/PATCH /me`, `POST /me/password`, `GET /me/context` | `identity/MeController.java` |
| Davet | `POST/GET /invitations`, `/{id}/revoke`, `/lookup`, `/accept`, `/{id}/accept`, `/{id}/decline` | `identity/InvitationController.java` |
| Rol atama | `POST/GET /role-assignments`, `DELETE /role-assignments/{id}` | `access/RoleAssignmentController.java` |
| Operasyon | `POST/GET /operations`, `GET/PATCH /operations/{id}`, `POST /{id}/state`, `GET /{id}/participants` | `organization/OperationController.java` |
| Grup | `POST /operations/{id}/groups`, `GET /groups`, `GET/PATCH /groups/{id}`, `/members`, `/leader`, `/meeting-point` | `groups/GroupController.java` |
| Görev | `POST/GET /operations/{id}/tasks`, `GET/PATCH /tasks/{id}`, `/publish`, `/state`, `/assignments`, `GET /me/assignments`, `/assignments/{id}/accept|reject` | `tasks/TaskController.java` |
| Gelen kutusu | `GET /me/notifications`, `GET /me/notifications/{id}`, `POST /{id}/read`, `GET /unread-count` | `notifications/InboxController.java` |
| Duyuru | `POST /announcements/preview`, `POST /announcements`, `GET /announcements`, `GET /announcements/{id}` | `notifications/AnnouncementController.java` |
| Kullanıcılar (16.11) | `GET /users`, `GET /users/{id}`, `POST /users/{id}/disable`, `/enable`, `/revoke-sessions`, `/reset-password`, `/reset-mfa` | `identity/UserAdminController.java` |
| Denetim (16.11) | `GET /audit-entries` (süzgeçler, imleç; değiştirme/silme ucu yok) | `audit/AuditController.java` |
| Akış (16.11) | `GET /stream?topics=…&lastEventId=…` (`text/event-stream`) | `realtime/StreamController.java` |
| Alarm (16.11) | `POST/GET /operations/{id}/alerts`, `GET /alerts`, `GET/DELETE /alerts/{id}`, `/preview`, `/send`, `/cancel`, `/close`, `/resend`, `/recipients`, `GET /me/alerts`, `POST /alert-recipients/{id}/delivered|seen|responses` | `alerts/AlertController.java` |
| Konum (16.11) | `POST/DELETE /operations/{id}/location-sharing`, `POST /operations/{id}/locations`, `GET /operations/{id}/locations`, `GET /me/location-sharing` | `location/LocationController.java` |

**Kurallar:**
- **Idempotency (§3.6):** "Z" uçlarında `Idempotency-Key` zorunlu (ör. ID-12, ID-16, ID-17/18, TK-03, TK-11/12, NT-02); "d" uçlarında isteğe bağlı. Eksikse `400 REQUIRED`.
- **Sayfalama (16.11):** tüm listeler `org.gks.backend.PageCursor` (limit 1..100, varsayılan 50; base64url imleç). Modüllerin eski imleç kabul biçimi `PageCursor.CursorFormat` ile korunur (tasks `HEX`, groups/rol atamaları `LENIENT`, diğerleri `CANONICAL`).
- **Hata gövdesi:** `application/problem+json` + `code`; alan hataları `errors[{field, code, message}]` (Türkçe ileti).
- **Bilinmeyen alan reddedilir** (`fail-on-unknown-properties`): istemciler yalnızca sözleşme alanlarını gönderir.
- **Kısmi güncelleme (LD-52):** gönderilmeyen alan korunur, açık `null` temizler/varsayılana döndürür.
- **Denetim sırası (§3.3):** doğrulama 400 → kaynak 404 → yetki 403 → idempotency → durum 409 → anlamsal 422.

---

## 7. Android

**Giriş noktaları:** `android/app/src/main/java/org/gks/android/`
- `AppContainer.kt`: elle bağımlılık kurulumu, `GksJson`, `createApis(...)` (yetkili istemci + ayrı yenileme istemcisi).
- `data/remote/GksApi.kt`, `Dtos.kt`: tüm uçlar ve DTO'lar.
- `data/session/`: `KeystoreSessionStore`, `SessionManager`, `TokenRefresher` (tek uçuşlu yenileme), `AuthInterceptors.kt` (Bearer + 401 işleme).
- `data/ApiCall.kt`: `ApiResult`, `ApiError`, `ApiCaller.call/allPages`, `IdempotencyKeys` (ağ hatasında aynı anahtar).
- Depolar: `AuthRepository`, `InvitationRepository`, `VolunteerRepository`, `NotificationRepository`, `ManagementRepository` (operasyon, koordinatör, gönüllü/davet, grup), `TaskManagementRepository`, `AnnouncementRepository` (16.11).
- `alarm/` (ADR-022): `Alarms` (tek giriş `onIncoming`: FCM ve SSE; `notificationId` ile tekilleştirme; kanal `alerts-critical-v2`; ısrarlı ses/hatırlatma `AlarmManager`; yanıt düğmeleri; `reconcile`), `AlarmPolicy` (saf durum makinesi, birim testli), `Incoming`/`RecentIds`, `AlarmActionReceiver`, `GksMessagingService`, `DeviceRegistrar` (`PUT /devices/{installationId}`). İzin ekranı/durumu `ui/notify/AlarmPermissions.kt`. Bildirime dokunma → `MainActivity.handleOpen` → `AppContainer.pendingOpen` → `MainShell` gezinmesi.
- `domain/`: `HomeModel.kt`, `RolePanels.kt`, `ProfileChanges.kt`, `ManagementAccess.kt` (Yönetim sekmesi ve düğme görünürlüğü; `canAnnounce`, `canAnnounceOrganization`), `AnnouncementForm.kt` (hedef, kişi arama, doğrulama).

**Tasarım (ADR-023, 16.11 gece):** alt gezinme `bottomTabs()` = Ana Sayfa, Rehberler, Duyurular, (Yönetim — yalnızca MFA'lı yönetim oturumu), Hesap. Görevler (`tasks`), Grubum (`groups`), Bildirimler (`notifications`) rota olarak durur; ana sayfadaki Hızlı Erişim ve üst çubuktaki bildirim zilinden açılır. Rehber içeriği `web/src/content/*.json`'dan Gradle `copyGuideContent` göreviyle `assets/guide/`a kopyalanır (internetsiz; girişten önce `GuideHost`). Logo `res/drawable/logo` çalışma anında aranır (`BrandLogo`). Ortak durum rozeti `ui/common/StatusBadge.kt`.

**Navigasyon:** `ui/MainActivity.kt` (giriş/kayıt/MFA/bağlam yükleme durumları, `ui/AppViewModel.kt`) → `ui/MainShell.kt` (alt navigasyon + NavHost).
- Gönüllü sekmeleri: Ana sayfa, Görevler, Grubum, Bildirimler, Hesap.
- Yönetim oturumu sekmeleri: Ana sayfa, Görevler, **Yönetim**, Bildirimler, Hesap (Grubum, Ana sayfadaki grup kartlarından açılır).

**Ekran paketleri:** `ui/login`, `ui/register`, `ui/mfa`, `ui/home`, `ui/tasks`, `ui/groups`, `ui/notifications`, `ui/account`, `ui/management` (`ManagementScreens.kt`, `VolunteersScreen.kt`, `GroupManagementScreens.kt`, `TaskManagementScreens.kt`, `AnnouncementScreens.kt`).

**Ortak bileşenler (`ui/common/`):** `LoadViewModel` + `Load` (yükleniyor/hata/hazır), `LoadContent`, `StateViews.kt` (Empty/Error/MessageCard/Fact), `FormField`, `DateTimeField` + `DateTimes` (16.11: Material3 tarih + 24 saat seçici, yerel saat ↔ UTC ISO; görev ve operasyon formları), `DateInput` (eski metin girişi; yalnızca grup toplanma noktası saati), `Messages.kt` (`ApiError.toUiText`), `Labels.kt` (enum → Türkçe), `containerViewModel(...)`.

**Yönetim modülleri (tamamlandı):** operasyon listesi/ayrıntı/form/durum, koordinatör ekle/kaldır (ADM), gönüllüler ve davetler, gruplar (oluştur, üye ekle/çıkar, lider, ad/açıklama, toplanma noktası), görevler (oluştur/düzenle, yayımla, durum, atama ekle/kaldır, geçmiş), duyurular (operasyon ayrıntısı → "Duyurular"; Yönetim listesi → "Kurum duyurusu" ADM). Rotalar: `management/operation/{op}/announcements`, `management/announcements/organization`, `management/announcement/{id}`.

**Yapılandırma:** API kökü `gks.apiBaseUrl` — öncelik: `-P` > `android/local.properties` > `android/gradle.properties` (emülatör varsayılanı `http://10.0.2.2:8080/`). Debug derlemesi düz HTTP'ye izin verir (`src/debug/res/xml/network_security_config.xml`), sürüm derlemesi yalnızca HTTPS.

---

## 8. Web

Rotalar (`web/src/app/App.tsx`, ADR-023): **halka açık** `/`, `/afet/:tur`, `/rehber`, `/ne-yapmali`, `/duyurular` (kamu duyuruları), `/gonulluluk`, `/iletisim`; giriş `/giris`; **panel** `/panel` (Özet), `/panel/operasyonlar[/:opId[/gruplar|/gorevler|/alarmlar|/konum]]`, `/panel/davetler`, `/panel/hesabim`, `/panel/bildirimler`, `/panel/duyurular`, `/panel/kamu-duyurulari`, `/panel/roller`, `/panel/kullanicilar`, `/panel/denetim`, `/panel/alarmlar[/:id]`. Eski kök adresler `LEGACY_PANEL_PATHS` ile `/panel/...`'e yönlenir. Oturum sonu yalnızca panelde girişe götürür (`isPanelPath`).

- Tema `app/theme.ts` (tek yer; `contrastRatio` testli), marka `config/brand.ts`, halka açık içerik `src/content/*.json` (koddan ayrı; Android da kullanır). Halka açık sayfalar iç servis çağırmaz (`public.test.tsx` vekil servislerle doğrular).

- Servis katmanı: `web/src/services/contracts.ts` (arayüzler), `http.ts` (gerçek API), `mock/services.ts` (yalnızca `npm run dev:mock` tanıtımı; "Örnek veri" rozeti), `registry.tsx`, kaynak modu `web/src/config/services.ts` (varsayılan hepsi `http`).
- API istemcisi: `web/src/api/client.ts` (CSRF, tek sekmede yenileme, 401/503 kuralları), hata iletileri `web/src/api/errorMessages.ts`.
- Menü görünürlüğü `web/src/app/AppShell.tsx` → `usePanelNav` (bağlam izinlerinden; `panel.test.tsx`).

- Salt-okur bandı (`ReadOnlyBanner`) artık rol adından değil izinden: `usePermissions().isAdmReadOnly(op)` = ADM ve `task.manage` yok (ADR-015 sonrası normalde görünmez). Servis kaynakları `config/services.ts`: `roleAssignments`, `users` eklendi (http + mock).

**Eksik web yönetim ekranları:** konum haritası. Liste sayfaları ortak `StatusChip`/`EmptyState` kullanır; tek bir genel tablo bileşenine geçiş yapılmadı (RAPOR_5).

---

## 9. Çalıştırma

Geliştirme makinesi: Windows 11, 16 GB RAM. JDK'lar: Temurin 17 (Android derlemesi için), Temurin 21 (backend). Android Studio Hedgehog 2023.1.1.

| Bileşen | Komut / not |
|---|---|
| PostgreSQL + Mailpit | Backend `bootRun` Docker Compose desteğiyle başlatır (`spring.docker.compose.lifecycle-management: start-only`); elle: `docker compose up -d` (kökte). Kontrol: `docker ps` |
| Backend | `cd backend && ./gradlew bootRun` → `http://localhost:8080`, sağlık: `/actuator/health`. Sırlar kök `.env` dosyasından (içeriği belgeye yazılmaz; şablon `.env.example`). **Aynı anda tek bootRun.** |
| Web | `cd web && npm run dev` → `http://localhost:5173` (Vite proxy ile backend). Harita karosu: `VITE_MAP_TILE_URL` (varsayılan OSM yalnızca geliştirme) |
| Demo verisi | Backend kapalıyken `$env:GKS_DEMO_PASSWORD=...; .\scripts\dev\demo-seed.ps1` (yalnızca ekler, idempotent; `scripts/dev/README.md`) |
| Mailpit arayüzü | `http://localhost:8025` |
| Android derleme | `cd android` ; `JAVA_HOME=<Temurin 17>` ; `./gradlew assembleDebug` ; kurulum `./gradlew installDebug` |
| Gerçek telefon (USB) | `adb reverse tcp:8080 tcp:8080` ve `android/local.properties` içinde `gks.apiBaseUrl=http://127.0.0.1:8080/` (şu an bu değer ayarlı [doğrulandı]) |
| Aynı Wi-Fi | `gks.apiBaseUrl=http://<bilgisayar LAN IP>:8080/` + Windows güvenlik duvarında 8080 izni |

**Yapay zekâ ajanının komut çalıştırma düzeni (16.11):** Ajanın kabuğu Windows'ta değil, ayrı bir Linux sanal makinesindedir (JDK 17/21, Docker, Android SDK yok). Testler ve git komutları proje sahibinin açtığı bir PowerShell döngüsüyle çalışır: ajan `C:\AIProjects\.claude-runs\job.cmd` dosyasını yazar ve `job.ready` koyar; döngü `run.cmd`'yi çalıştırır, çıktı `job.log`'a **eklenir** (her iş komutlarıyla birlikte kaydedilir), sonuç kodu `job.done`'a yazılır. `.claude-runs/` git'e girmez. Döngü komutu (proje sahibi başlatır, ajan değiştirmez): `cd C:\AIProjects; while ($true) { Get-Date -Format o | Set-Content .claude-runs\watcher.alive; if (Test-Path .claude-runs\job.ready) { Remove-Item .claude-runs\job.ready; cmd /c .claude-runs\run.cmd }; Start-Sleep 3 }`. JDK yolları: Temurin 21 `C:\Program Files\Eclipse Adoptium\jdk-21.0.4.7-hotspot`, Temurin 17 `...\jdk-17.0.12.7-hotspot`. Gradle her zaman `--offline`.

Demo yardımcı betiği: `scripts/demo-davet-kabul.mjs` (Mailpit'teki yeni kullanıcı davetlerini kabul eder). Demo hesap bilgileri bu belgeye yazılmamıştır (`@demo.local` hesaplarının parolası yalnızca `.claude-runs/RAPOR_6.md`).

---

## 10. Testler

| Paket | Komut | Son sonuç |
|---|---|---|
| Backend (paket paket) | `cd backend && ./gradlew test --offline --no-daemon --max-workers=1 --tests "org.gks.backend.<paket>.*"` | 16.11 sabah: access 37, identity 128, notifications 19, organization 54, tasks 115, groups 62, audit 5, realtime 5, alerts 6, location 4, kök 46 → **481/481** |
| Web | `cd web && npx tsc -b && npx eslint src && npx vitest run --maxWorkers=1 --minWorkers=1` | **131/131** (tsc, eslint temiz) |
| Android | `cd android && ./gradlew assembleDebug testDebugUnitTest --offline` (JDK 17) | **80/80** |

- Backend testleri Testcontainers kullanır (Docker gerekir).
- **Backend tam test paketini tek seferde çalıştırmayın**: makinede iki kez JVM "insufficient memory" çökmesine yol açtı. Paket paket ve `--max-workers=1`.
- Android testleri JVM birim testleridir (MockWebServer `FakeBackend`, `app/src/test/.../data/TestSupport.kt`); cihaz/enstrümantasyon testi yok.
- Not: Görev yönetimi modülünde backend değişmediği için backend testleri o aşamada yeniden çalıştırılmadı.

---

## 11. Eksikler ve yol haritası (öncelik sırası)

Tamamlananlar (16.11): Android duyuru yönetimi, ADR-015, tarih-saat seçici, web rol atama, kullanıcı yönetimi, sayfalama birleştirme; sabah: **denetim kaydı (ADR-016), SSE (ADR-017), acil alarm (ADR-018), konum (ADR-019)**; öğleden sonra: ADR-020 (ADM konum, `gks_app` DB rolü, kimlik denetimi), Android SSE (ADR-021); akşam: **FCM + acil alarm bildirimi (ADR-022)**.

1. **FCM gerçek cihaz doğrulaması** — kod tamam (ADR-022, 16.11 akşam); MANUEL_TEST §11 sonuçlarına göre düzeltme. Kalan: `deliveryDelayed` push teslim durumundan hesaplanmıyor, alarm hatırlatmaları push'u yeniden göndermiyor, `REUSE_DETECTED` cihazı kapatmıyor.
2. ~~Harita~~ — tamamlandı (ADR-025, MapLibre). Üretim için kendi karo sunucusu gerekir.
3. ~~Android SSE~~ — tamamlandı (ADR-021).
4. Alarm kalanları: AL-02, `alert:{id}` konusu ve 1 sn birleştirme; Görev 24 (cihaz kaydını oturum iptali/bitişinde kapatma, Türkçe I, bildirim içerik kuralları, iletim gecikmesi ve FCM hatırlatması).
5. Konum kalanları: `SESSION_ENDED`/`ACCOUNT_DISABLED` durdurma; `OPERATION_SUSPENDED` (G).
6. Denetim kalanları: kimlik olayları (giriş/çıkış/MFA); ayrı yalnızca `SELECT/INSERT` DB rolü.
7. Android'de kullanıcı yönetimi ekranı; Android koordinatör seçimi (ID-19).
8. Genişletilmiş kapsam: operasyonun "Askıda" durumu, parola sıfırlama e-postası, onay metni, toplanma noktası geçmişi.

---

## 12. Bilinen sorunlar, teknik borç ve açık kararlar

- **Bellek:** 16 GB makinede backend tam test paketi ve VS Code Java dil sunucusu (2 GB) JVM çökmelerine yol açtı. JVM çökme kayıtları: kökte `hs_err_pid24196.log` (15.11 19:42; VS Code Java dil sunucusu); `android/` altında `hs_err_pid10924.log` ve `replay_pid10924.log` (aynı zaman, ~1,7 MB); `backend/` altında `hs_err_pid21340/28608/29364/9128.log` ve `replay_pid21340/9128.log` (~2,4 MB). Hepsi `.gitignore`'da; zararsız, silinmesi kullanıcı kararı.
- ~~Sayfalama yardımcısı tekrarı~~ — 16.11'de `PageCursor`'da birleştirildi (rol atamaları dahil). Açık konu: üç farklı imleç kabul biçimi korundu (`CursorFormat`); tek biçime indirmek davranış değişikliği olur.
- **Mobil yönetim riski (kabul edilen):** TOTP uygulaması aynı telefondaysa iki faktörün bağımsızlığı azalır (ADR-014 belgede yazılı).
- **Koordinatör aday listesi (Android)** hâlâ katılımcılar + diğer KOR'larla sınırlı (`ManagementRepository.coordinatorCandidates`); web `/roller` kurum dizinini (ID-19) kullanır.
- ~~Android tarih girişi metin alanı~~ — görev ve operasyon formlarında seçiciyle değişti (16.11); grup toplanma noktası saati hâlâ metin alanı.
- **Eski kod yorumları:** ADR-013'ü anlatan yorumlar (EffectivePermissions, AnnouncementController `x-client`, AnnouncementAccess, Subject, TaskAccess) 16.11'de düzeltildi.
- **Belge tutarlılığı:** `backend-api-design.md` §2.4 ve `authorization.md` §5.4'teki eski "yalnızca web" metinleri silinmedi, üstlerine ADR-014 notu eklendi; okurken not esas alınmalı. `web-screen-api-mapping.md` 16.11'de başa "Güncel durum (ADR-014/015)" bölümü eklenerek güncellendi; eski metinler o notla birlikte okunur.
- **PROJECT_PROGRESS.md ile çelişki:** yok, bu belgeyle uyumlu. Ancak oradaki test tablosu farklı tarihlerde çalıştırılan paketlerin son sonuçlarıdır; tam paket hiç tek seferde çalıştırılmadı.
- **Açık kararlar:** harita sağlayıcısı; FCM projesi; ADM'nin konum görmesi (şu an hayır, ADR-019); denetim için ayrı DB rolü; TEK/UYM'nin mobile alınıp alınmayacağı (şu an hayır); "Askıda" durumunun Çekirdek'e alınması.
- **SSE tek örnek:** olaylar bellek içi; çoklu backend örneğinde `LISTEN/NOTIFY` veya outbox gerekir (ADR-017). Sunucu yeniden başlarsa istemci `resync` alır.
- **Windows dosya sistemi:** kök pakette `realtime` paketiyle büyük/küçük harf farkıyla çakışan sınıf adı (ör. `Realtime`) Hibernate taramasını bozar; bu yüzden ad `RealtimeBus`.

---

## 13. Devralacak yapay zekâ için talimatlar

1. **Önce oku:** bu belge, `PROJECT_PROGRESS.md`, `TASKS.md` içinde LD-59…LD-61, `decisions.md` ADR-014…ADR-019, `.claude-runs/RAPOR_2.md`.
2. **Mevcut kodu koru:** Tamamlanan modülleri yeniden yazma. Ortak bileşenleri (`ui/common`, `ApiCaller`, `LoadViewModel`, `IdempotencyKeys`, `PageCursor`, `Notifier`, `AuditTrail`, `RealtimeBus`) yeniden kullan. Her yeni yönetim işlemi denetime yazılır (ADR-016).
3. **Yetki kurallarını bozma:** `Grants.corporate`, `Grants.coordinates` (ADR-015), `requireManagementSession`, `requireAndroid`, MFA ve 12 sa sınırı değiştirilmez. Yeni yönetim uçları `requireManagementSession()` + `can()` ile korunur. İstemcide görünürlük yalnızca menü içindir.
4. **Sözleşmeyi koru:** Uç yolları ve alan adları `backend-api-design.md` ile birebir. Sapma gerekiyorsa `TASKS.md`'ye yeni LD kaydı ve API tablosuna not.
5. **Veri:** Geliştirme veritabanını silme, sıfırlama. Şema değişikliği yalnızca yeni migration. Kullanıcı gerçek telefonda test ediyor olabilir: backend'i gereksiz yeniden başlatma; gerekirse önce kullanıcıya sor.
6. **Bellek:** Tek Gradle çağrısı, `--max-workers=1`, yalnızca değişen modülün testleri; aynı anda tek bootRun; iş bitince `./gradlew --stop` (Android). Bellek hatasında tekrar deneme, dur ve bildir.
7. **Test kalitesi:** Backend yetki testleri gerçek rol atamalarıyla (access taklit edilmeden) yazılır; Android'de yeni depo davranışı `FakeBackend` testiyle doğrulanır. Bir test eski kuralı sabitliyorsa ancak karar değiştiyse güncellenir ve gerekçe yazılır.
8. **Git kuralı (16.11.2026, proje sahibi):** yalnızca önemli ve kararlı aşamalarda commit (aşama bitmiş, ilgili testler geçiyor, derleme temiz); ara adım/"WIP" commit'i yok. **Push yok** (push'u proje sahibi yapar); `reset --hard`, `clean` ve force işlemleri yasak. Mesaj kısa ve açıklayıcı, ör. `feat(access): ADM tüm operasyonlarda tam yönetim yetkisi (ADR-015)`. `.env`, `local.properties`, `hs_err*`/`replay_pid*` kayıtları, derleme çıktıları ve `.claude-runs/` commit'e girmez (`.gitignore`). Sırları (parola, belirteç, TOTP anahtarı, `.env`) belgeye veya koda yazma.
9. **Dil:** Arayüz ve belgeler Türkçe.

---

## 14. Sıradaki görev: proje sahibinin kararları

16.11 sabahı tamamlananlar ve commit'ler: denetim `5fd8771`, SSE `2b800a1`, acil alarm `9a79e24`, konum `0f51d86` (ayrıntı `.claude-runs/RAPOR_2.md`).

Sonraki oturumlar: RAPOR_3 (ADR-020/021), RAPOR_4 (ADR-022, FCM), RAPOR_5 (ADR-023 tasarım, halka açık site, kamu duyuruları), RAPOR_6 (ADR-024 mesajlaşma `2655a1f`, ADR-025 harita `608761c`, ADR-026 genel alarm `3d5d424`, demo aracı `a478745`; Görev 23 kalanı ve 24 bekliyor). Firebase: `android/app/google-services.json` git dışıdır, asla commit edilmez; sunucu anahtarı yalnızca `.env`'deki `GKS_FIREBASE_CREDENTIALS_FILE` yolundan okunur, dosya açılmaz. Sıradaki iş proje sahibinin MANUEL_TEST §11 sonuçlarına ve harita sağlayıcısı kararına bağlıdır. Yeni bağımlılık/indirme yalnızca açık izinle.
