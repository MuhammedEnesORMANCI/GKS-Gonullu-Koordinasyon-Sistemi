# GKS — Yapay Zekâ Devir ve Devam Belgesi

> Hazırlanma: 15.11.2026. Kaynaklar: proje dosyaları (klasör yapısı, migration listesi, controller/test dizinleri, yapılandırma), `PROJECT_PROGRESS.md`, `docs/architecture/design/team/TASKS.md` (LD-xx kararları), `docs/architecture/decisions.md` (ADR-xx) ve bu projede yürütülen geliştirme oturumlarının kayıtları.
> **Kural:** Bu belgede parola, belirteç, TOTP anahtarı veya `.env` içeriği yoktur ve eklenmemelidir.
> Kesinlik işaretleri: **[doğrulandı]** = kodda/testte görüldü; **[doğrulanmadı]** = yalnızca tasarım belgesinde veya hiç denenmedi.

---

## 1. Projenin amacı

**GKS (Gönüllü Koordinasyon Sistemi)**, afet ve acil durum operasyonlarında gönüllülerin davet edilmesini, operasyonlara ve gruplara katılmasını, görevlendirilmesini, bilgilendirilmesini ve (planlanan) acil alarm/konum takibini sağlayan bir sistemdir. Gereksinim kaynağı: `Gonullu_Koordinasyon_Sistemi_Gereksinim_Analizi.docx`, kapsam: `docs/analysis/mvp-scope.md`.

**Roller** (`backend/src/main/java/org/gks/backend/access/Role.java`):

| Rol | Kapsam | Açıklama |
|---|---|---|
| ADM | Kurum | Kurum yöneticisi: operasyon oluşturma, rol atama, kurum geneli duyuru |
| KOR | Operasyon | Koordinatör: kendi operasyonunda görev, grup, davet, duyuru yönetimi |
| GON | Operasyon | Gönüllü (operasyon katılımcısı) |
| TEK | Kurum | Teknik destek (hesap durumu, oturum iptali) — uçları henüz yok |
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
| Rol atama (AC-01…03) | ✅ | ❌ ekran yok | ✅ yalnızca koordinatör ekle/kaldır |
| Grup (GR-01…08) | ✅ | ✅ | ✅ gönüllü/lider ekranları + KOR grup yönetimi |
| Görev (TK-01…12) | ✅ | ✅ | ✅ gönüllü kabul/ret + KOR görev yönetimi |
| Bildirim gelen kutusu (NT-04/05/07/08) | ✅ | ✅ zil rozeti, `/bildirimler` | ✅ sekme, rozet, 30 sn yoklama |
| Duyuru (NT-01/02/03/06) | ✅ | ✅ `/duyurular` | ❌ **ilk yapılacak iş** |

### 3.2 Planlanan / yok

Denetim kaydı (audit), acil alarm (alerts), konum ve harita (location), kullanıcı yönetimi uçları (ID-19…25), cihaz kaydı (ID-26), FCM push, gerçek zamanlı akış (SSE), parola sıfırlama e-postası (ID-27/28), onay metni (ID-29). Ayrıntı ve öncelik: §11.

### 3.3 Gerçek cihazda/kullanıcıyla doğrulanmamış

- Android uygulaması emülatörde açılmadı; ekranlar yalnızca derleme + birim testleriyle doğrulandı. Kullanıcı gerçek telefonda test etmeye başladı (USB, `adb reverse`); sonuç bu belgeye yansımadı.
- ADM/KOR için Android MFA girişi gerçek hesapla denenmedi (backend entegrasyon testi gerçek TOTP ile geçiyor).
- Gerçek SMTP yok; davet e-postası yalnızca Mailpit'e gider.

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

**Yetki modeli (ADR-002):** Her karar `can()` ile access modülünde, roller her istekte veritabanından (LD-55). Önemli kurallar [doğrulandı, testlerle]:
- Operasyon yönetimi: ADM tümü, KOR yalnızca kendi operasyonu. Operasyon oluşturma ve rol atama yalnızca ADM.
- Grup kurma/üyelik/lider değişikliği ve görev yönetimi: **yalnızca operasyonun KOR'u**; KOR olmayan ADM salt-okur.
- Grup açıklaması ve toplanma noktası: KOR veya grubun lideri.
- Duyuru: operasyon duyurusu KOR (operasyon `ACTIVE` olmalı, N-19), kurum geneli duyuru ADM.

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

**Önemli ilişkiler:** Katılım = `access.role_assignments` (GON/OPERATION). KOR ataması da aynı tablo. Grup üyeliği `groups.group_memberships` (LEADER/MEMBER). Görev atamaları `tasks.task_assignments` (PENDING/ACCEPTED/REJECTED/CAPACITY_FULL/REMOVED). Bildirim ↔ teslim: bir `notifications` satırı, alıcı başına `deliveries` satırları; tekrar önleme `uq_notifications_idempotency_key`.

**Veri koruma kuralları:**
- Mevcut geliştirme veritabanında gerçek test verisi var (operasyon, grup, gönüllü hesapları). **Silme, sıfırlama, `docker compose down -v` yasak.**
- Şema değişikliği yalnızca yeni Flyway migration ile (V12+); mevcut migration dosyaları değiştirilmez.
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

**Kurallar:**
- **Idempotency (§3.6):** "Z" uçlarında `Idempotency-Key` zorunlu (ör. ID-12, ID-16, ID-17/18, TK-03, TK-11/12, NT-02); "d" uçlarında isteğe bağlı. Eksikse `400 REQUIRED`.
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
- Depolar: `AuthRepository`, `InvitationRepository`, `VolunteerRepository`, `NotificationRepository`, `ManagementRepository` (operasyon, koordinatör, gönüllü/davet, grup), `TaskManagementRepository`.
- `domain/`: `HomeModel.kt`, `RolePanels.kt`, `ProfileChanges.kt`, `ManagementAccess.kt` (Yönetim sekmesi ve düğme görünürlüğü).

**Navigasyon:** `ui/MainActivity.kt` (giriş/kayıt/MFA/bağlam yükleme durumları, `ui/AppViewModel.kt`) → `ui/MainShell.kt` (alt navigasyon + NavHost).
- Gönüllü sekmeleri: Ana sayfa, Görevler, Grubum, Bildirimler, Hesap.
- Yönetim oturumu sekmeleri: Ana sayfa, Görevler, **Yönetim**, Bildirimler, Hesap (Grubum, Ana sayfadaki grup kartlarından açılır).

**Ekran paketleri:** `ui/login`, `ui/register`, `ui/mfa`, `ui/home`, `ui/tasks`, `ui/groups`, `ui/notifications`, `ui/account`, `ui/management` (`ManagementScreens.kt`, `VolunteersScreen.kt`, `GroupManagementScreens.kt`, `TaskManagementScreens.kt`).

**Ortak bileşenler (`ui/common/`):** `LoadViewModel` + `Load` (yükleniyor/hata/hazır), `LoadContent`, `StateViews.kt` (Empty/Error/MessageCard/Fact), `FormField`, `DateInput` (`gg.aa.yyyy ss:dd` ↔ ISO), `Messages.kt` (`ApiError.toUiText`), `Labels.kt` (enum → Türkçe), `containerViewModel(...)`.

**Yönetim modülleri (tamamlandı):** operasyon listesi/ayrıntı/form/durum, koordinatör ekle/kaldır (ADM), gönüllüler ve davetler, gruplar (oluştur, üye ekle/çıkar, lider, ad/açıklama, toplanma noktası), görevler (oluştur/düzenle, yayımla, durum, atama ekle/kaldır, geçmiş).

**Yapılandırma:** API kökü `gks.apiBaseUrl` — öncelik: `-P` > `android/local.properties` > `android/gradle.properties` (emülatör varsayılanı `http://10.0.2.2:8080/`). Debug derlemesi düz HTTP'ye izin verir (`src/debug/res/xml/network_security_config.xml`), sürüm derlemesi yalnızca HTTPS.

---

## 8. Web

Rotalar (`web/src/app/App.tsx`): `/giris`, `/` (panel), `/operasyonlar`, `/operasyonlar/:opId`, `.../gruplar`, `.../gruplar/:groupId`, `.../gorevler`, `.../gorevler/:taskId`, `/davetler`, `/hesabim`, `/bildirimler`, `/duyurular`.

- Servis katmanı: `web/src/services/contracts.ts` (arayüzler), `http.ts` (gerçek API), `mock/services.ts` (yalnızca `npm run dev:mock` tanıtımı; "Örnek veri" rozeti), `registry.tsx`, kaynak modu `web/src/config/services.ts` (varsayılan hepsi `http`).
- API istemcisi: `web/src/api/client.ts` (CSRF, tek sekmede yenileme, 401/503 kuralları), hata iletileri `web/src/api/errorMessages.ts`.
- Menü görünürlüğü `web/src/app/AppShell.tsx` (bağlam izinlerinden).

**Eksik web yönetim ekranları:** rol atama / koordinatör yönetimi (backend var, web'de ekran yok — `role-assignments` web kodunda geçmiyor [doğrulandı]), kullanıcı yönetimi (backend yok), denetim kayıtları, acil alarm, konum haritası.

---

## 9. Çalıştırma

Geliştirme makinesi: Windows 11, 16 GB RAM. JDK'lar: Temurin 17 (Android derlemesi için), Temurin 21 (backend). Android Studio Hedgehog 2023.1.1.

| Bileşen | Komut / not |
|---|---|
| PostgreSQL + Mailpit | Backend `bootRun` Docker Compose desteğiyle başlatır (`spring.docker.compose.lifecycle-management: start-only`); elle: `docker compose up -d` (kökte). Kontrol: `docker ps` |
| Backend | `cd backend && ./gradlew bootRun` → `http://localhost:8080`, sağlık: `/actuator/health`. Sırlar kök `.env` dosyasından (içeriği belgeye yazılmaz; şablon `.env.example`). **Aynı anda tek bootRun.** |
| Web | `cd web && npm run dev` → `http://localhost:5173` (Vite proxy ile backend) |
| Mailpit arayüzü | `http://localhost:8025` |
| Android derleme | `cd android` ; `JAVA_HOME=<Temurin 17>` ; `./gradlew assembleDebug` ; kurulum `./gradlew installDebug` |
| Gerçek telefon (USB) | `adb reverse tcp:8080 tcp:8080` ve `android/local.properties` içinde `gks.apiBaseUrl=http://127.0.0.1:8080/` (şu an bu değer ayarlı [doğrulandı]) |
| Aynı Wi-Fi | `gks.apiBaseUrl=http://<bilgisayar LAN IP>:8080/` + Windows güvenlik duvarında 8080 izni |

Demo yardımcı betiği: `scripts/demo-davet-kabul.mjs` (Mailpit'teki yeni kullanıcı davetlerini kabul eder). Demo hesap bilgileri bu belgeye yazılmamıştır.

---

## 10. Testler

| Paket | Komut | Son sonuç |
|---|---|---|
| Backend (hedefli) | `cd backend && ./gradlew test --max-workers=1 --tests "org.gks.backend.<paket>.*"` | identity+access+notifications+Modularity+Security 192/192; organization+tasks+groups 231/231; `identity.MobileManagementSessionTests` 8/8 |
| Web | `cd web && npx tsc -b && npx eslint src && npx vitest run --maxWorkers=1 --minWorkers=1` | 106/106 |
| Android | `cd android && ./gradlew testDebugUnitTest` (JDK 17) | 58/58 |

- Backend testleri Testcontainers kullanır (Docker gerekir).
- **Backend tam test paketini tek seferde çalıştırmayın**: makinede iki kez JVM "insufficient memory" çökmesine yol açtı. Paket paket ve `--max-workers=1`.
- Android testleri JVM birim testleridir (MockWebServer `FakeBackend`, `app/src/test/.../data/TestSupport.kt`); cihaz/enstrümantasyon testi yok.
- Not: Görev yönetimi modülünde backend değişmediği için backend testleri o aşamada yeniden çalıştırılmadı.

---

## 11. Eksikler ve yol haritası (öncelik sırası)

1. **Android duyuru yönetimi** — backend ve web hazır; Android ekranı yok (§14).
2. **Denetim kaydı (audit)** — `audit` modülü boş. K-26 (yalnızca ADM/UYM görür), LD-05/LD-22/LD-44 (konum görüntüleme penceresi), `x-audit` olayları (`ANNOUNCEMENT_SENT`, rol/operasyon işlemleri) henüz yazılmıyor.
3. **Acil alarm (US-20…25)** — `alerts` boş. Tasarım: `emergency-alert-design.md`, DB §6.3–6.4, API §11.1. Bildirim altyapısı (`Notifier`, CRITICAL kuyruk) hazır. ADR-014 ile MFA'lı Android oturumundan da gönderilebilir (DR-12 güncellendi).
4. **Konum ve harita (US-17…19)** — `location` boş. K-14 (kullanıcı başlatır), K-15 (başkalarının konumunu yalnızca KOR görür), K-16 (yalnızca son konum), ADR-011 (ön plan servisi). Harita kütüphanesi kararı verilmedi (Google Maps API anahtarı vs. OSM).
5. **Kullanıcı ve kurum yönetimi (ID-19…25)** — backend'de yok: kullanıcı listesi/arama, devre dışı bırakma, oturum iptali, parola/MFA sıfırlama. Android koordinatör seçimi bu yüzden katılımcılar + diğer KOR'larla sınırlı.
6. **Web rol atama ekranı** — backend hazır.
7. **FCM push + cihaz kaydı (ID-26)** — kullanıcının Firebase projesi yok. `notifications/PushGateway.java`, `DeliveryWorker.java` hazır; `gks.notifications.push.provider` şu an `none`.
8. **SSE gerçek zamanlı** (ADR-009) — yok; istemciler 30 sn yoklama.
9. Genişletilmiş kapsam: operasyonun "Askıda" durumu, parola sıfırlama e-postası, onay metni, toplanma noktası geçmişi.

---

## 12. Bilinen sorunlar, teknik borç ve açık kararlar

- **Bellek:** 16 GB makinede backend tam test paketi ve VS Code Java dil sunucusu (2 GB) JVM çökmelerine yol açtı. Kökte bir `hs_err_pid24196.log` var (15.11 19:42; VS Code Java dil sunucusu). `backend/` altında eski `hs_err_pid*.log` dosyaları da var; zararsız, silinmesi kullanıcı kararı.
- **Sayfalama yardımcısı tekrarı:** `tasks/TaskValidation.page`, `groups/GroupValidation.page` ve yeni `PageCursor.java` aynı işi yapıyor; ileride birleştirilebilir.
- **Mobil yönetim riski (kabul edilen):** TOTP uygulaması aynı telefondaysa iki faktörün bağımsızlığı azalır (ADR-014 belgede yazılı).
- **Koordinatör aday listesi** ID-19 olmadığı için sınırlı (geçici çözüm, `ManagementRepository.coordinatorCandidates`).
- **Android tarih girişi** metin alanı (`gg.aa.yyyy ss:dd`); tarih/saat seçici yok.
- **Belge tutarlılığı:** `backend-api-design.md` §2.4 ve `authorization.md` §5.4'teki eski "yalnızca web" metinleri silinmedi, üstlerine ADR-014 notu eklendi; okurken not esas alınmalı. `web-screen-api-mapping.md` ADR-014 sonrası güncellenmedi [doğrulanmadı].
- **PROJECT_PROGRESS.md ile çelişki:** yok, bu belgeyle uyumlu. Ancak oradaki test tablosu farklı tarihlerde çalıştırılan paketlerin son sonuçlarıdır; tam paket hiç tek seferde çalıştırılmadı.
- **Açık kararlar:** harita sağlayıcısı; FCM projesi; TEK/UYM'nin mobile alınıp alınmayacağı (şu an hayır); "Askıda" durumunun Çekirdek'e alınması.

---

## 13. Devralacak yapay zekâ için talimatlar

1. **Önce oku:** bu belge, `PROJECT_PROGRESS.md`, `TASKS.md` içinde LD-59…LD-61, `decisions.md` ADR-014.
2. **Mevcut kodu koru:** Tamamlanan modülleri yeniden yazma. Ortak bileşenleri (`ui/common`, `ApiCaller`, `LoadViewModel`, `IdempotencyKeys`, `PageCursor`, `Notifier`) yeniden kullan.
3. **Yetki kurallarını bozma:** `Grants.corporate`, `requireManagementSession`, `requireAndroid`, MFA ve 12 sa sınırı değiştirilmez. Yeni yönetim uçları `requireManagementSession()` + `can()` ile korunur. İstemcide görünürlük yalnızca menü içindir.
4. **Sözleşmeyi koru:** Uç yolları ve alan adları `backend-api-design.md` ile birebir. Sapma gerekiyorsa `TASKS.md`'ye yeni LD kaydı ve API tablosuna not.
5. **Veri:** Geliştirme veritabanını silme, sıfırlama. Şema değişikliği yalnızca yeni migration. Kullanıcı gerçek telefonda test ediyor olabilir: backend'i gereksiz yeniden başlatma; gerekirse önce kullanıcıya sor.
6. **Bellek:** Tek Gradle çağrısı, `--max-workers=1`, yalnızca değişen modülün testleri; aynı anda tek bootRun; iş bitince `./gradlew --stop` (Android). Bellek hatasında tekrar deneme, dur ve bildir.
7. **Test kalitesi:** Backend yetki testleri gerçek rol atamalarıyla (access taklit edilmeden) yazılır; Android'de yeni depo davranışı `FakeBackend` testiyle doğrulanır. Bir test eski kuralı sabitliyorsa ancak karar değiştiyse güncellenir ve gerekçe yazılır.
8. **Git işlemi yapma**, sırları (parola, belirteç, TOTP anahtarı, `.env`) belgeye veya koda yazma.
9. **Dil:** Arayüz ve belgeler Türkçe.

---

## 14. İlk yapılacak görev: Android duyuru yönetimi

**Amaç:** ADM/KOR'un Android Yönetim sekmesinden duyuru önizleyip göndermesi ve gönderilen duyuruları görmesi. Backend değişikliği beklenmez.

**Backend (hazır, değiştirme):** `backend/src/main/java/org/gks/backend/notifications/AnnouncementController.java`, `AnnouncementService.java`.
- `POST /api/v1/announcements/preview` gövde `{operationId?, userIds?, groupIds?, organization?}` → `{recipientCount, recipients[{id, displayName}], confirmationRequired}`
- `POST /api/v1/announcements` (**Idempotency-Key zorunlu**) gövde = hedef + `title` (3–120), `body` (1–2000), `confirm` (>50 alıcıda `true` gerekir, aksi `422 CONFIRMATION_REQUIRED`) → `202 AnnouncementView`
- `GET /api/v1/announcements?operationId=&limit=&cursor=` → `{items[AnnouncementView], nextCursor}` (listede `body` yok)
- `GET /api/v1/announcements/{id}` → `AnnouncementView` (`body` dahil)
- Kurallar: KOR yalnızca kendi `ACTIVE` operasyonu (`409 OPERATION_NOT_ACTIVE`); kapsam dışı grup/kişi `403 FORBIDDEN`; kurum geneli yalnızca ADM; kurum geneli + başka hedef `400`; alıcı yoksa `422 EMPTY_AUDIENCE`.
- İzinler `MeContext.permissions`: `announcement.send.group` (operasyon), `announcement.send.organization` (kurum).

**Referans uygulama:** web `web/src/features/notifications/AnnouncementsPage.tsx` (önizleme → onay → gönder akışı), web test `web/src/features/__tests__/notifications.test.tsx`; backend test `backend/src/test/java/org/gks/backend/notifications/NotificationApiTests.java` (`Announcements` iç sınıfı).

**Android'de dokunulacak yerler:**
- `data/remote/Dtos.kt`: `AnnouncementTarget/CreateRequest`, `AnnouncementPreview`, `AnnouncementView`, `AnnouncementDelivery`.
- `data/remote/GksApi.kt`: 4 uç (`@Header(IDEMPOTENCY_HEADER)` gönderimde).
- Yeni `data/AnnouncementRepository.kt` (`ApiCaller`, `IdempotencyKeys.run`) ve `AppContainer.kt`'ye kayıt.
- `domain/ManagementAccess.kt`: `canAnnounce(operationId)` ve `canAnnounceOrganization` (izinlerden).
- Yeni `ui/management/AnnouncementScreens.kt`: hedef seçimi (operasyon katılımcıları / gruplar — `ManagementRepository.groups`), başlık/metin, önizleme, >50 onay kutusu, gönder; geçmiş listesi.
- `ui/management/ManagementScreens.kt`: operasyon ayrıntısına "Duyurular" düğmesi (diğer düğmelerin yanına) ve gerekiyorsa Yönetim listesine "Kurum duyurusu" (ADM).
- `ui/MainShell.kt`: rotalar (`management/operation/{op}/announcements` vb.), `strings.xml`: Türkçe metinler.
- Test: `app/src/test/java/org/gks/android/data/AnnouncementManagementTest.kt` (gövde, anahtar, tekrarda aynı anahtar, `CONFIRMATION_REQUIRED`/`OPERATION_NOT_ACTIVE` hata eşleme, görünürlük). Kalıp: `TaskManagementTest.kt`, `VolunteerManagementTest.kt`.

**Bitince:** `./gradlew assembleDebug testDebugUnitTest` (JDK 17), `PROJECT_PROGRESS.md` güncelle, telefona kurulum kullanıcıya bırakılır (`./gradlew installDebug`).
