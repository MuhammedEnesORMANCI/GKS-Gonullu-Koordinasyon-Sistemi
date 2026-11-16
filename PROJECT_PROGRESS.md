# GKS — Proje İlerleme Durumu

> Son güncelleme: 16.11.2026 (gece: tasarım sistemi, halka açık site, kamu duyuruları, panel ve Android yeniden tasarımı — ADR-023). Bir sonraki oturum bu dosyadan başlamalı; ayrıntılı kararlar `docs/architecture/design/team/TASKS.md` (LD-xx) ve `docs/architecture/decisions.md` (ADR-xx) dosyalarındadır.

## 1. Ortam ve çalışma kuralları

| Bileşen | Konum / komut | Not |
|---|---|---|
| Backend | `backend/` — `./gradlew bootRun` (Spring Boot 4, Java 21, Gradle 9.8.2) | `.env` kök dizinden okunur; Postgres + Mailpit `compose.yaml` ile (Docker) |
| Web | `web/` — `npm run dev` (Vite, 5173). **`/` halka açık site, `/panel` yönetim paneli, `/giris` giriş** (ADR-023) | Testler: `npx vitest run --maxWorkers=1 --minWorkers=1`, `npx tsc -b`, `npx eslint src` |
| Android | `android/` — `JAVA_HOME=<JDK17> ./gradlew assembleDebug testDebugUnitTest` | Studio Hedgehog uyumlu: AGP 8.2.2, Gradle 8.5, Kotlin 1.9.22, SDK 34, Compose BOM 2024.02.00 (material3 1.2.0). `local.properties` → `gks.apiBaseUrl` |
| Veritabanı | Flyway, son sürüm **V16** (kamu duyuruları, ADR-023); önceki: **V15** (ayrı uygulama kullanıcısı `gks_app`, ADR-020) | Veriler silinmez; yeni şema değişikliği V16'dan başlar. `bootRun` `local` profiliyle `gks_app` ile bağlanır, Flyway sahibi `POSTGRES_USER` |
| Firebase | `android/app/google-services.json` (git dışı); hizmet hesabı anahtarı `.env` → `GKS_FIREBASE_CREDENTIALS_FILE`, `GKS_PUSH_PROVIDER=fcm` | Anahtar dosyası açılmaz/okunmaz; içerik günlüğe yazılmaz |

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
| **ADM tüm operasyonlarda yönetim (ADR-015, 16.11)** | ✅ | ✅ | ✅ | `Grants.coordinates` = KOR(op) veya ADM; alarm izinleri dahil (ADR-018), konum hariç (ADR-019) |
| Kullanıcı yönetimi (ID-19…25, 16.11) | ✅ | ✅ `/kullanicilar` | – | Liste/ayrıntı (ADM tam, TEK ad+durum, KOR kendi operasyonu), devre dışı/etkin, oturum iptali, parola ve MFA sıfırlama |
| Gönüllü yönetimi (OR-06, ID-12…14) | ✅ | ✅ | ✅ `VolunteersScreen.kt` | Katılımcı listesi, operasyona davet, bekleyen davetler, iptal |
| Grup (GR-01…08) | ✅ | ✅ | ✅ gönüllü/lider ekranları + yönetim `GroupManagementScreens.kt` | Oluştur, ad/açıklama, üye ekle/çıkar, lider, toplanma noktası. Yönetim: operasyonun KOR'u ve (ADR-015) ADM |
| Görev (TK-01…12) | ✅ | ✅ | ✅ gönüllü kabul/ret + yönetim `TaskManagementScreens.kt` | Oluştur/düzenle (version, tarih-saat seçici), yayımla, durum, atama ekle/kaldır, geçmiş |
| Bildirim gelen kutusu (V10, NT-04/05/07/08) | ✅ | ✅ | ✅ | LD-60; push sağlayıcısı `gks.notifications.push.provider` (`none`/`fcm`) |
| **FCM push + cihaz kaydı (ID-26, ADR-022, 16.11 akşam)** | ✅ `PUT /devices/{installationId}`, `FcmPushGateway` | – | ✅ `alarm/` paketi | Yalnızca data mesajı; geçersiz belirteç kapatılır; gönderim hatası geri almaz. Android: tekilleştirme (`notificationId`), ısrarlı alarm sesi + hatırlatma, bildirimden yanıt, iptal/kapanışta güncelleme, kilit ekranında gizli içerik, Android 14+ izin açıklaması |
| **Android SSE + yerel bildirim (ADR-021)** | – | – | ✅ `data/realtime/` | Uygulama açıkken; FCM ile aynı girişe gider |
| Duyuru (NT-01/02/03/06) | ✅ | ✅ `/duyurular` | ✅ (16.11) `AnnouncementScreens.kt` | Android: operasyonun tamamı / gruplar / kişiler, önizleme, >50 onay, geçmiş, ayrıntı; ADM'ye "Kurum duyurusu" |
| Android yönetim oturumu (ADR-014, V11) | ✅ | – | ✅ MFA ekranı, ekran kilidi şartı | ADM/KOR mobilde TOTP + 12 sa mutlak oturum; TEK/UYM web'de (LD-61) |
| Android tarih-saat seçici (16.11) | – | – | ✅ `ui/common/DateTimeField.kt` | Yerel saatle seçim, UTC ISO gönderim; görev ve operasyon formları |
| Sayfalama yardımcısı (16.11) | ✅ `PageCursor` | – | – | tasks/groups/rol atamaları birleştirildi; imleç biçimleri `CursorFormat` ile korundu |
| **Denetim kaydı (V12, ADR-016, 16.11)** | ✅ `audit/` | ✅ `/denetim` | – | Aynı işlemde eşzamanlı yazım (yazılamazsa işlem geri alınır), `actor_basis` (KOR/ADM/…), tetikleyiciyle yalnızca ekleme; AU-01 süzgeçli okuma (ADM, UYM) |
| **Gerçek zamanlı SSE (RT-01, ADR-017, 16.11)** | ✅ `realtime/` | ✅ gelen kutusu, duyuru, alarm, konum canlı | ✅ (ADR-021) | Bağlantı başına yetki, işlem sonrası yayın, `Last-Event-ID`/`resync`, rol kaldırmada `subscription.revoked`, oturum iptalinde `session.revoked`; tek örnek |
| **Acil alarm (V13, ADR-018, 16.11)** | ✅ `alerts/` AL-01, 03–16 | ✅ WEB-09/10, `/alarmlar` | ✅ gönüllü: alarm bandı, yanıt ekranı | Taslak → alıcı listeli onay → gönderim; iptal/düzeltme/kapatma/yeniden gönderim; hatırlatma sayacı; operasyon kapanışında kapanır. **İletim gelen kutusu + SSE; FCM yok** |
| **Konum (V14, ADR-019, 16.11)** | ✅ `location/` LC-01…05 | ✅ `/operasyonlar/:id/konum` tablo | ✅ izin akışı + ön plan servisi | Yalnızca aktif operasyonda açık başlatma; yalnızca KOR görür; 5 dk pencerede tek denetim kaydı; kapanıştan 30 gün sonra silinir; **harita yok** (sağlayıcı arayüzü hazır) |
| **Mesajlaşma (V17, ADR-024, 16.11 gece)** | ✅ `messaging/` | ✅ `/panel/mesajlar` | ✅ Mesajlar sekmesi, çevrimdışı kuyruk | Operasyon/koordinasyon/grup kanalları, her istekte erişim, yalnızca yöneticiler modu, silme/gizleme, SSE, içeriksiz FCM, 90 gün saklama |
| **Harita (V18, ADR-025)** | ✅ `/map/operations`, operasyon bölgesi | ✅ MapLibre `/panel/harita`, Özet kartı, operasyon/konum haritaları | ✅ MapLibre 11.13.5 | OSM karosu yalnızca geliştirme; WebGL yoksa metin yedeği; Android "Yol tarifi" (`geo:`) |
| **Genel Acil Alarm (V19, ADR-026)** | ✅ `/general-alerts` | ✅ üst çubuk düğmesi | ✅ yönetim: genel alarm, açık alarmlar, takip | Birleştirilebilir hedef, tekil alıcı, `confirmed` zorunlu, oran sınırı, denetim, kanallara sistem mesajı, olay yeri |
| **Demo verisi aracı (Görev 25)** | ✅ `devseed/` | – | – | `scripts/dev/demo-seed.ps1`; yalnızca ekler, idempotent, üretimde çalışmaz |
| **Tasarım sistemi (ADR-023, 16.11 gece)** | – | ✅ `app/theme.ts`, `config/brand.ts` | ✅ `ui/theme/` , `res/values/brand.xml` | Lacivert + turuncu, WCAG AA testli, sistem fontları; logo/hero dosyası proje sahibince (yoksa yer tutucu/gradyan) |
| **Halka açık site (ADR-023)** | – | ✅ `features/public/`, içerik `src/content/*.json` | – | `/`, `/afet/:tur`, `/rehber`, `/ne-yapmali`, `/duyurular`, `/gonulluluk`, `/iletisim`; iç veri yok (testli); eski panel adresleri `/panel/...`e yönlenir |
| **Kamu duyuruları (V16, ADR-023)** | ✅ `PublicAnnouncement*` | ✅ `/panel/kamu-duyurulari` + site | ✅ Duyurular sekmesi | ADM yönetir (taslak/yayında, denetimli); `GET /api/v1/public/announcements` kimliksiz, sayfalı, IP başına 60/dk |
| **Panel yeniden tasarımı + Özet (WEB-02)** | – | ✅ `AppShell`, `dashboard/HomePage` | – | Daraltılabilir yan menü (izinlere göre), üst çubuk, Özet kartları (SSE), ortak durum rozeti |
| **Android tasarım (ADR-023)** | – | – | ✅ `ui/guide/`, `ui/home/HomeLanding`, `ui/announcements/` | Alt gezinme Ana Sayfa/Rehberler/Duyurular/(Yönetim)/Hesap; internetsiz rehber (web içeriği gömülü), girişten önce de |

## 3. Eksik modüller (önem sırasına göre)

1. **Görev 23 (kalan) ve Görev 24 (a–e) bu turda yapılmadı** (proje sahibi kararı, 16.11 22.44). Görev 23'ün olay yeri ve alarm/operasyon → mesaj kanalı bağlantısı parçaları plan değişmeden önce yazılmıştı (3d5d424). Görev 24 kalanları: REUSE_DETECTED ve oturum bitişinde cihaz kaydını kapatma, Türkçe I taraması, bildirim içerik kuralları + kamu duyurusunda "Telefonlara bildir", gerçek teslim durumundan "iletim gecikti" ve FCM ile hatırlatma. (Android alarm takip ekranı Görev 22 ile geldi.)
2. Alarm: AL-02 taslak düzenleme yok (sil-yeniden oluştur); `alert:{id}` konusu ve 1 sn birleştirme yok; `deliveryDelayed` FCM varken de hâlâ `false`; hatırlatmalar push'u yeniden tetiklemiyor. Android'de genel alarm hedefi olarak grup/kişi seçimi yok (web'de var).
3. FCM kalanları: `REUSE_DETECTED` ile iptal edilen oturum cihazı kapatmıyor; FCM gerçek cihazda henüz denenmedi (MANUEL_TEST §11).
4. Konum: `SESSION_ENDED`/`ACCOUNT_DISABLED` durdurma nedenleri üretilmiyor.
5. Küçük kalanlar: Android'de kullanıcı yönetimi ekranı yok (yalnızca web); grup toplanma noktası saati Android'de hâlâ metin alanı.

## 4. Önemli kararlar (yeni oturum için)

- **ADR-015 / LD-62 (16.11):** ADM, KOR ataması olmadan tüm operasyonlarda görev, grup/üyelik, toplanma noktası, operasyon duyurusu (ve zaten olan operasyon yönetimi, davet, koordinatör atama) yetkisine sahip. Tek kural `access/Grants.coordinates`. 16.11 sabahından itibaren `alert.*` dahil (ADR-018), yalnızca `location.view` hariç (`EffectivePermissions.ADM_OPERATION`, ADR-019). `/me/context` ADM'ye tüm operasyonlar için bu seti döndürür; istemciler buna göre açılır.
- **ADR-014 / LD-61:** `Grants.corporate` — Android'de kurumsal izin yalnızca ADM/KOR + `mfaVerified`. Yönetim uçlarında kapı `Subject.requireManagementSession()`. DB kısıtı `ck_refresh_tokens_mfa_absolute`.
- **Kullanıcı yönetimi (16.11):** kendi hesabında disable/parola/MFA sıfırlama `403`; TEK, ADM rolü olan hesabı kapatamaz; MFA sıfırlama tüm oturumları kapatır; geçici parola commit sonrası e-postayla (Mailpit).
- **LD-60:** Gelen kutusu W/A; bildirimler üretici modülün işleminde `Notifier` (MANDATORY) ile yazılır.
- **ADR-016 (denetim):** üretici `AuditTrail.record(...)` → `AuditEvent` → `audit/AuditWriter` aynı işlemde (`MANDATORY`). Yeni yönetim işlemi yazarken denetim satırı eklenmeli; içerik (başlık, metin, e-posta, adres, koordinat) yazılmaz. `actor_basis` access'teki `ActingBasis` ile.
- **ADR-017 (SSE):** üretici `RealtimeBus.publish(topic, type, operationId, data)`; olay commit sonrası gider, veri yalnızca kimlik/durum/zaman. Konular `user:`, `operation:`, `organization`, `location:`.
- **ADR-018 (alarm):** ADM alarm izinlerinin tümüne sahip; iletim gelen kutusu + SSE, alıcı `SENT` = gelen kutusuna yazıldı.
- **ADR-019 (konum):** yalnızca KOR görür (ADM görmez — proje sahibinin onayı bekleniyor); saklama `gks.location.retention-after-close` (varsayılan `P30D`).
- **`OperationClosed`** (organization): kapanış işleminde eşzamanlı yayımlanır; alerts (açık alarmları kapatır) ve location (paylaşımı durdurur, saklamayı başlatır) dinler.

## 5. Test durumu (16.11.2026 gece: tam backend s67, web s63, Android s62)

| Paket | Sonuç |
|---|---|
| Backend access | 37/37 |
| Backend identity | 131/131 |
| Backend notifications | 51/51 |
| Backend organization | 54/54 |
| Backend tasks | 115/115 |
| Backend groups | 62/62 |
| Backend audit | 6/6 |
| Backend realtime | 5/5 |
| Backend alerts | 14/14 |
| Backend messaging | 6/6 |
| Backend devseed | 3/3 |
| Backend location | 4/4 |
| Backend kök (Modularity, Security, Idempotency, Application) | 46/46 |
| **Backend toplam** | **534/534** |
| Web | tsc + eslint temiz (uyarısız), vitest **198/198** |
| Android | **105/105**, `assembleDebug` başarılı (telefon bağlı değildi, installDebug yok) |

Denenmeyenler (16.11 gece): yeni tasarım tarayıcıda/telefonda görülmedi (telefon çıkarılmıştı, `installDebug` yapılmadı); V16 dev DB'ye uygulanmadı (backend yeniden başlatılmadı). MANUEL_TEST §12–§15. FCM gerçek cihazda doğrulandı (proje sahibi). Proje sahibi 16.11'de doğruladı: web girişi, alarm (aktif operasyonda), duyurular; telefonda uygulama açıkken SSE ve yerel bildirim. Harita ve gerçek GPS ölçümü denenmedi.

## 6. Sonraki oturumda başlangıç adımları

1. `docker ps` ile Postgres/Mailpit, `curl localhost:8080/actuator/health` ile backend kontrolü (tek bootRun). Dev DB Flyway **V19**'da (16.11 23.15 demo aracı uyguladı), demo verisi ekli. FCM için `.env`: `GKS_PUSH_PROVIDER=fcm`, `GKS_FIREBASE_CREDENTIALS_FILE=<yol>`.
2. `.claude-runs/RAPOR_6.md` (Görev 20–22 ve 25, indirilen sürümler, yedek, demo hesapları, bekleyen kararlar) ve MANUEL_TEST §16–§19.
3. Kod yerleri: backend `backend/src/main/java/org/gks/backend/<modül>/`; Android `android/app/src/main/java/org/gks/android/` (`data/*Repository.kt`, `ui/alerts/`, `ui/location/`, `location/LocationSharingService.kt`); web `web/src/features/<ekran>/`, servisler `web/src/services/`, canlı akış `web/src/app/live.ts`.
4. Yeni yönetim işlemi: yetki `access/` arayüzü, denetim `AuditTrail.record`, canlı güncelleme `RealtimeBus.publish`; web'de yeni servis için http + mock + `config/services.ts` anahtarı ve `screens.test.tsx` anahtar listesi.
