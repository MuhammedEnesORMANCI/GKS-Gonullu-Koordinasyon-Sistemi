# GKS — Proje İlerleme Durumu

> Son güncelleme: 16.11.2026 (gece oturumu). Bir sonraki oturum bu dosyadan başlamalı; ayrıntılı kararlar `docs/architecture/design/team/TASKS.md` (LD-xx) ve `docs/architecture/decisions.md` (ADR-xx) dosyalarındadır.

## 1. Ortam ve çalışma kuralları

| Bileşen | Konum / komut | Not |
|---|---|---|
| Backend | `backend/` — `./gradlew bootRun` (Spring Boot 4, Java 21, Gradle 9.8.2) | `.env` kök dizinden okunur; Postgres + Mailpit `compose.yaml` ile (Docker) |
| Web | `web/` — `npm run dev` (Vite, 5173) | Testler: `npx vitest run --maxWorkers=1 --minWorkers=1`, `npx tsc -b`, `npx eslint src` |
| Android | `android/` — `JAVA_HOME=<JDK17> ./gradlew assembleDebug testDebugUnitTest` | Studio Hedgehog uyumlu: AGP 8.2.2, Gradle 8.5, Kotlin 1.9.22, SDK 34, Compose BOM 2024.02.00 (material3 1.2.0). `local.properties` → `gks.apiBaseUrl` |
| Veritabanı | Flyway, son sürüm **V11** (16.11 gecesinde şema değişikliği yapılmadı) | Veriler silinmez; yeni şema değişikliği V12'den başlar |

**Bellek kuralı (zorunlu):** Backend testleri paket paket, `--max-workers=1 --tests "<paket>.*"`. Aynı anda tek bootRun. Çalışan bootRun varken backend testleri `--no-daemon` ile çalıştırılır (`gradlew --stop` aynı Gradle sürümündeki bootRun daemon'ını da kapatır). Android Gradle daemon'u iş bitince `./gradlew --stop` (farklı Gradle sürümü, bootRun'a dokunmaz).

**Git kuralı (16.11.2026):** Yalnızca kararlı aşamalarda commit (testler geçmiş, derleme temiz); push yok (proje sahibi yapar); `reset --hard`, `clean`, force yasak. `.env`, `local.properties`, `hs_err*`/`replay_pid*`, derleme çıktıları ve `.claude-runs/` commit'e girmez.

**Diğer kurallar:** Veriler silinmez, sahte ekran/veri yok, Türkçe arayüz, her yönetim işlemi backend'de rol + kapsam + oturum ile doğrulanır.

## 2. Tamamlanan modüller

| Modül | Backend | Web | Android | Not |
|---|---|---|---|---|
| Kimlik: giriş, MFA (TOTP), oturum, yenileme, çıkış | ✅ | ✅ | ✅ | Android belirteçleri Keystore AES-GCM ile şifreli |
| Davet (ID-12…18), davet koduyla kayıt | ✅ | ✅ `/davetler` | ✅ kodla kayıt, operasyon davetini kabul/ret | Kayıt sonrası oturum kapatılır, giriş ekranına dönülür |
| Hesap: profil, telefon, parola değişikliği, zorunlu parola | ✅ | ✅ `/hesabim` | ✅ | ID-08 yeni belirteç çifti hemen depoya yazılır |
| Operasyon (OR-01…06) | ✅ | ✅ | ✅ Yönetim sekmesi | Liste/ayrıntı/oluştur/düzenle/durum, ADM koordinatör atama; tarih alanları seçiciyle |
| Rol atama (AC-01…03) | ✅ | ✅ `/roller` (16.11) | ✅ koordinatör ekle/kaldır | Web: rol/operasyon süzgeci, KOR ve kurum rolü atama, onaylı kaldırma |
| **ADM tüm operasyonlarda yönetim (ADR-015, 16.11)** | ✅ | ✅ | ✅ | `Grants.coordinates` = KOR(op) veya ADM; konum ve alarm izinleri hariç |
| Kullanıcı yönetimi (ID-19…25, 16.11) | ✅ | ✅ `/kullanicilar` | – | Liste/ayrıntı (ADM tam, TEK ad+durum, KOR kendi operasyonu), devre dışı/etkin, oturum iptali, parola ve MFA sıfırlama |
| Gönüllü yönetimi (OR-06, ID-12…14) | ✅ | ✅ | ✅ `VolunteersScreen.kt` | Katılımcı listesi, operasyona davet, bekleyen davetler, iptal |
| Grup (GR-01…08) | ✅ | ✅ | ✅ gönüllü/lider ekranları + yönetim `GroupManagementScreens.kt` | Oluştur, ad/açıklama, üye ekle/çıkar, lider, toplanma noktası. Yönetim: operasyonun KOR'u ve (ADR-015) ADM |
| Görev (TK-01…12) | ✅ | ✅ | ✅ gönüllü kabul/ret + yönetim `TaskManagementScreens.kt` | Oluştur/düzenle (version, tarih-saat seçici), yayımla, durum, atama ekle/kaldır, geçmiş |
| Bildirim gelen kutusu (V10, NT-04/05/07/08) | ✅ | ✅ | ✅ | FCM yok: `gks.notifications.push.provider=none` (LD-60) |
| Duyuru (NT-01/02/03/06) | ✅ | ✅ `/duyurular` | ✅ (16.11) `AnnouncementScreens.kt` | Android: operasyonun tamamı / gruplar / kişiler, önizleme, >50 onay, geçmiş, ayrıntı; ADM'ye "Kurum duyurusu" |
| Android yönetim oturumu (ADR-014, V11) | ✅ | – | ✅ MFA ekranı, ekran kilidi şartı | ADM/KOR mobilde TOTP + 12 sa mutlak oturum; TEK/UYM web'de (LD-61) |
| Android tarih-saat seçici (16.11) | – | – | ✅ `ui/common/DateTimeField.kt` | Yerel saatle seçim, UTC ISO gönderim; görev ve operasyon formları |
| Sayfalama yardımcısı (16.11) | ✅ `PageCursor` | – | – | tasks/groups/rol atamaları birleştirildi; imleç biçimleri `CursorFormat` ile korundu |

## 3. Eksik modüller (önem sırasına göre)

1. **Denetim kaydı (audit)** — sıradaki iş, **proje sahibinin tasarım onayı bekleniyor** (öneri: `.claude-runs/SABAH_RAPORU.md`). ADR-015 ile ADM'nin KOR ataması olmadan yaptığı operasyon işlemleri ve kullanıcı yönetimi işlemleri (ID-21…25) mutlaka kaydedilmeli; şu an hiçbiri yazılmıyor.
2. **Acil alarm (alerts, US-20…25)** — backend modülü yok; ADM'nin alarm yetkisi ADR-015'te kapsam dışı bırakıldı (karar gerekiyor).
3. **Konum ve harita (location, US-17…19)** — backend yok; harita sağlayıcısı kararı bekleniyor; K-15 (yalnızca KOR) ADR-015 ile değişmedi.
4. **FCM push + cihaz kaydı (ID-26)** — Firebase projesi gerekli.
5. **Gerçek zamanlı (SSE)** — yok; web ve Android 30 sn yoklama.
6. Küçük kalanlar: Android'de kullanıcı yönetimi ekranı yok (yalnızca web); grup toplanma noktası saati Android'de hâlâ metin alanı; devre dışı hesaba bildirim satırı yazılmasını engelleyen kontrol yok (oturumu iptal olduğu için göremez).

## 4. Önemli kararlar (yeni oturum için)

- **ADR-015 / LD-62 (16.11):** ADM, KOR ataması olmadan tüm operasyonlarda görev, grup/üyelik, toplanma noktası, operasyon duyurusu (ve zaten olan operasyon yönetimi, davet, koordinatör atama) yetkisine sahip. Tek kural `access/Grants.coordinates`. `location.view` ve `alert.*` hariç (`EffectivePermissions.ADM_OPERATION`). `/me/context` ADM'ye tüm operasyonlar için bu seti döndürür; istemciler buna göre açılır.
- **ADR-014 / LD-61:** `Grants.corporate` — Android'de kurumsal izin yalnızca ADM/KOR + `mfaVerified`. Yönetim uçlarında kapı `Subject.requireManagementSession()`. DB kısıtı `ck_refresh_tokens_mfa_absolute`.
- **Kullanıcı yönetimi (16.11):** kendi hesabında disable/parola/MFA sıfırlama `403`; TEK, ADM rolü olan hesabı kapatamaz; MFA sıfırlama tüm oturumları kapatır; geçici parola commit sonrası e-postayla (Mailpit).
- **LD-60:** Gelen kutusu W/A; bildirimler üretici modülün işleminde `Notifier` (MANDATORY) ile yazılır.

## 5. Test durumu (16.11.2026 gecesi, son çalıştırmalar)

| Paket | Sonuç |
|---|---|
| Backend access | 37/37 |
| Backend identity | 128/128 |
| Backend notifications | 19/19 |
| Backend organization | 54/54 |
| Backend tasks | 115/115 |
| Backend groups | 62/62 |
| Backend kök (Modularity, Security, Idempotency, Application) | 46/46 |
| **Backend toplam** | **461/461** (gece başı 452) |
| Web | tsc + eslint temiz, vitest **118/118** (gece başı 106) |
| Android | **73/73** (gece başı 58) |

Denenmeyenler: Android uygulaması cihazda/emülatörde açılmadı (yeni duyuru ve tarih seçici ekranları dahil); web yeni ekranları (`/roller`, `/kullanicilar`) gerçek backend'le tarayıcıda denenmedi (mock + birim testleri); gerçek SMTP yok (Mailpit).

## 6. Sonraki oturumda başlangıç adımları

1. `docker ps` ile Postgres/Mailpit, `curl localhost:8080/actuator/health` ile backend kontrolü (tek bootRun).
2. Proje sahibinin audit tasarım kararını oku (`.claude-runs/SABAH_RAPORU.md` §Audit); onay yoksa audit kodlamasına başlama.
3. Kod yerleri: backend `backend/src/main/java/org/gks/backend/<modül>/`; Android `android/app/src/main/java/org/gks/android/` (`data/*Repository.kt`, `ui/management/`, `ui/common/`); web `web/src/features/<ekran>/`, servisler `web/src/services/`.
4. Yeni yönetim ekranı yazarken: Android görünürlük `domain/ManagementAccess.kt`, hata eşleme `toUiText(...)`, test kalıbı `app/src/test/.../data/*Test.kt` (FakeBackend); web görünürlük `usePermissions().can(...)`, yeni servis için http + mock + `config/services.ts` anahtarı.
