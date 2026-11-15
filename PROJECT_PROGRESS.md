# GKS — Proje İlerleme Durumu

> Son güncelleme: 15.11.2026. Bir sonraki oturum bu dosyadan başlamalı; ayrıntılı kararlar `docs/architecture/design/team/TASKS.md` (LD-xx) ve `docs/architecture/decisions.md` (ADR-xx) dosyalarındadır.

## 1. Ortam ve çalışma kuralları

| Bileşen | Konum / komut | Not |
|---|---|---|
| Backend | `backend/` — `./gradlew bootRun` (Spring Boot 4, Java 21, Gradle 9.8.2) | `.env` kök dizinden okunur; Postgres + Mailpit `compose.yaml` ile (Docker) |
| Web | `web/` — `npm run dev` (Vite, 5173) | Testler: `npx vitest run --maxWorkers=1 --minWorkers=1`, `npx tsc -b`, `npx eslint src` |
| Android | `android/` — `JAVA_HOME=<JDK17> ./gradlew assembleDebug testDebugUnitTest` | Studio Hedgehog uyumlu: AGP 8.2.2, Gradle 8.5, Kotlin 1.9.22, SDK 34. `local.properties` → `gks.apiBaseUrl` |
| Veritabanı | Flyway, son sürüm **V11** | Veriler silinmez; demo gönüllü: `gokhan.demo@example.org` (parola demo betiğinde) |

**Bellek kuralı (zorunlu):** Backend tam test paketi makinede OOM'a yol açtı. Yalnızca değişen modülün testleri, `--max-workers=1 --tests "<paket>.*"`, tek Gradle çağrısı. Aynı anda tek bootRun. İş bitince Android Gradle daemon'u `./gradlew --stop`.

**Diğer kurallar:** Git işlemi yok, veriler silinmez, sahte ekran/veri yok, Türkçe arayüz, her yönetim işlemi backend'de rol + kapsam + oturum ile doğrulanır.

## 2. Tamamlanan modüller

| Modül | Backend | Web | Android | Not |
|---|---|---|---|---|
| Kimlik: giriş, MFA (TOTP), oturum, yenileme, çıkış | ✅ | ✅ | ✅ | Android belirteçleri Keystore AES-GCM ile şifreli |
| Davet (ID-12…18), davet koduyla kayıt | ✅ | ✅ (oluştur/listele/iptal) | ✅ (kodla kayıt, operasyon davetini kabul/ret) | Kayıt sonrası otomatik oturum kapatılır, giriş ekranına dönülür |
| Hesap: profil, telefon, parola değişikliği, zorunlu parola | ✅ | ✅ `/hesabim` | ✅ | ID-08 yeni belirteç çifti hemen depoya yazılır |
| Operasyon (OR-01…06) | ✅ | ✅ | ✅ Yönetim sekmesi | Liste/ayrıntı/oluştur/düzenle/durum, ADM koordinatör atama |
| Gönüllü yönetimi (OR-06, ID-12…14) | ✅ | ✅ | ✅ `ui/management/VolunteersScreen.kt` | Katılımcı listesi, operasyona davet, bekleyen davetler, iptal |
| Grup yönetimi (GR-01…08) | ✅ | ✅ | ✅ `ui/management/GroupManagementScreens.kt` | Oluştur, ad/açıklama, üye ekle/çıkar, lider değiştir, toplanma noktası. Üyelik yalnızca operasyonun KOR'u; ADM salt-okur (backend kuralı) |
| Grup (GR-01…08) | ✅ | ✅ | Gönüllü/lider ekranları ✅ | Lider: açıklama + toplanma noktası (Android) |
| Görev (TK-01…12) | ✅ | ✅ | Gönüllü: liste, ayrıntı, kabul/ret ✅; KOR yönetimi ✅ `ui/management/TaskManagementScreens.kt` | Oluştur/düzenle (version), yayımla (Z), durum, atama ekle/kaldır, geçmiş. Yönetim yalnızca `task.manage` (KOR); ADM salt-okur |
| Bildirim (V10, NT-01…08) | ✅ | ✅ gelen kutusu, duyuru ekranı | ✅ gelen kutusu, rozet, 30 sn yoklama | FCM yok: `gks.notifications.push.provider=none` (LD-60) |
| Android yönetim oturumu (ADR-014, V11) | ✅ | – | ✅ MFA ekranı, ekran kilidi şartı | ADM/KOR mobilde TOTP + 12 sa mutlak oturum; TEK/UYM web'de (LD-61) |

## 3. Eksik modüller (önem sırasına göre)

1. **Android duyuru yönetimi** — sıradaki iş. NT-01…03 hazır (web ekranı `web/src/features/notifications/AnnouncementsPage.tsx` var), Android ekranı yok. Görev yönetimi tamamlandı.
2. **Denetim kaydı (audit)** — backend modülü yok (`audit` paketi boş). Alarm ve konum buna bağlı; `ANNOUNCEMENT_SENT`, operasyon/rol işlemleri henüz kayda yazılmıyor.
3. **Acil alarm (alerts, US-20…25)** — backend modülü yok; tasarım `docs/architecture/design/emergency-alert-design.md`, DB §6.3–6.4. ADR-014 sonrası MFA'lı Android oturumundan da gönderilebilir.
4. **Konum ve harita (location, US-17…19)** — backend modülü yok; K-15 (başkalarının konumunu yalnızca KOR görür), K-16 (yalnızca son konum), LD-05/LD-22 (görüntüleme denetimi).
5. **Kullanıcı yönetimi uçları (ID-19…25)** — backend'de yok; ADM kullanıcı listesi/arama, devre dışı bırakma, oturum iptali, parola/MFA sıfırlama. Koordinatör seçimi şu an katılımcılar + diğer KOR'larla sınırlı.
6. **FCM push** — Firebase projesi gerekli (kullanıcıda yok). `PushGateway` arayüzü ve teslim işçisi hazır; cihaz kaydı (ID-26) FCM ile gelecek.
7. **Gerçek zamanlı (SSE)** — yok; web ve Android 30 sn yoklama kullanıyor.

## 4. Önemli kararlar (yeni oturum için)

- **ADR-014 / LD-61:** `Grants.corporate` — web kuralı değişmedi; Android'de kurumsal izin yalnızca ADM/KOR + `mfaVerified`. Yönetim uçlarında kapı `Subject.requireManagementSession()`. DB kısıtı `ck_refresh_tokens_mfa_absolute` (MFA'lı zincir ≤ 12 sa).
- **LD-60:** Gelen kutusu W/A; ek uçlar `GET /me/notifications/{id}`, `GET /me/notifications/unread-count`. Bildirimler üretici modülün işleminde `Notifier` (MANDATORY) ile yazılır; tekrar `uq_notifications_idempotency_key` ile engellenir.
- **Android Yönetim sekmesi** yalnızca `MeContext.permissions` içinde `operation.manage`/`operation.create` varken (MFA'lı oturum) görünür; yöneticide alt çubuk: Ana sayfa, Görevler, Yönetim, Bildirimler, Hesap.

## 5. Test durumu (son çalıştırmalar)

| Paket | Sonuç |
|---|---|
| Backend identity + access + notifications + Modularity + Security | 192/192 |
| Backend organization + tasks + groups | 231/231 |
| Backend `MobileManagementSessionTests` | 8/8 |
| Web | 106/106 (tsc, eslint temiz) |
| Android | 58/58 (görev yönetimi modülünde backend değişmedi, backend testleri yeniden çalıştırılmadı) |

Denenmeyenler: Android uygulaması emülatörde açılmadı; gerçek ADM/KOR hesabıyla mobil MFA girişi denenmedi (demo yönetici parolası bilinmiyor).

## 6. Sonraki oturumda başlangıç adımları

1. `docker ps` ile Postgres/Mailpit, `curl localhost:8080/actuator/health` ile backend kontrolü (tek bootRun).
2. Bu dosyanın §3 sırasıyla devam et; Android kodu `android/app/src/main/java/org/gks/android/` altında:
   - Veri: `data/*Repository.kt`, `data/remote/GksApi.kt`, `Dtos.kt`
   - Yönetim ekranları: `ui/management/`; kabuk ve sekmeler: `ui/MainShell.kt`
   - Ortak bileşenler: `ui/common/` (LoadViewModel, LoadContent, FormField, DateInput, Messages)
3. Yeni yönetim ekranı yazarken: izin görünürlüğü `domain/ManagementAccess.kt`, hata eşleme `toUiText(...)`, test kalıbı `app/src/test/.../data/ManagementFlowTest.kt` (FakeBackend).
