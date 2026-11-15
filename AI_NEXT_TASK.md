# Sonraki Yapay Zekâ İçin Başlangıç Promptu

> **16.11.2026 notu:** Bu dosyadaki görev (Android duyuru yönetimi) tamamlandı (commit `094ff9d`). Güncel durum ve sıradaki iş için `AI_HANDOFF.md` §11 ve §14 ile `PROJECT_PROGRESS.md` esastır; Git kuralı da değişti (AI_HANDOFF §13 madde 8).

Aşağıdaki metni olduğu gibi yeni ajana verin.

---

GKS (Gönüllü Koordinasyon Sistemi) projesini devralıyorsun. Proje kökü `C:\AIProjects`. Backend Spring Boot 4 (Java 21) + PostgreSQL (Docker), web React/TypeScript, Android Kotlin/Jetpack Compose.

**Önce şunları oku (kaynak kodun tamamını okuma):**
1. `AI_HANDOFF.md` — mimari, güvenlik, API, çalıştırma, test ve kurallar.
2. `PROJECT_PROGRESS.md` — tamamlanan ve eksik modüller.
3. `docs/architecture/decisions.md` içinde ADR-014 ve `docs/architecture/design/team/TASKS.md` içinde LD-59, LD-60, LD-61.

**Görevin: yalnızca Android ADM/KOR Duyuru Yönetimi modülünü geliştirmek.** Ayrıntılı dosya ve API referansları `AI_HANDOFF.md` §14'te.

- Mevcut backend uçlarını kullan: `POST /api/v1/announcements/preview`, `POST /api/v1/announcements` (Idempotency-Key zorunlu), `GET /api/v1/announcements`, `GET /api/v1/announcements/{id}`. Backend değişikliği beklenmiyor; gerekirse önce gerekçesini yaz ve kullanıcıya bildir.
- Akış web'deki `web/src/features/notifications/AnnouncementsPage.tsx` ile aynı olsun: hedef seç (operasyonun tamamı veya gruplar; ADM için kurum geneli) → başlık/metin → alıcı önizlemesi → 50'den fazla alıcıda açık onay → gönder → gönderilen duyurular listesi.
- Erişim: Android Yönetim sekmesi → operasyon ayrıntısı (`ui/management/ManagementScreens.kt`) → "Duyurular". Görünürlüğü `MeContext.permissions` içindeki `announcement.send.group` / `announcement.send.organization` izinlerine bağla (`domain/ManagementAccess.kt`). Yetkiyi asıl sunucu denetler.
- Mevcut kalıpları yeniden kullan: `data/ApiCall.kt` (`ApiCaller`, `IdempotencyKeys.run`), `ui/common/` (`LoadViewModel`, `LoadContent`, `FormField`, `MessageCard`, `toUiText`), örnek modül `ui/management/TaskManagementScreens.kt` ve `data/TaskManagementRepository.kt`.
- Hata kodlarını Türkçe iletilere eşle: `OPERATION_NOT_ACTIVE`, `FORBIDDEN`, `CLIENT_NOT_ALLOWED`, `VALIDATION_FAILED` (`CONFIRMATION_REQUIRED`, `EMPTY_AUDIENCE`; sunucunun alan iletileri Türkçedir).
- Test: `android/app/src/test/java/org/gks/android/data/AnnouncementManagementTest.kt` (FakeBackend kalıbı, bkz. `TaskManagementTest.kt`): istek gövdesi, zorunlu anahtar, ağ hatasında aynı anahtar, hata eşleme, görünürlük.

**Kesin kurallar:**
- Tamamlanan modülleri yeniden yazma; gönüllü, grup lideri ve diğer yönetim ekranlarını bozma.
- MFA, ADR-014 yönetim oturumu (12 sa mutlak), `Grants.corporate`, `requireManagementSession` kurallarını değiştirme.
- Veritabanını silme/sıfırlama; Git işlemi yapma; parola, belirteç, TOTP anahtarı veya `.env` içeriğini hiçbir yere yazma.
- Kullanıcı uygulamayı gerçek telefonda (USB, `adb reverse tcp:8080 tcp:8080`) test ediyor olabilir: çalışan backend'i gereksiz yeniden başlatma, telefona kendiliğinden kurulum yapma. Telefonu etkileyecek bir işlem gerekirse önce dur ve sor.
- Bellek: tek Gradle çağrısı, yalnızca değişen modülün testleri (`cd android && ./gradlew assembleDebug testDebugUnitTest`, JDK 17), bitince `./gradlew --stop`. Backend testi gerekirse yalnızca ilgili paket, `--max-workers=1`. Bellek hatasında tekrar deneme, dur ve bildir.
- Paralel ajan başlatma; doğrulanmamış bilgiyi kesin bilgi gibi raporlama.
- Arayüz metinleri ve rapor Türkçe.

**Bitirince:** `PROJECT_PROGRESS.md` dosyasını güncelle (tamamlanan modül tablosu, test sayıları, sıradaki iş: denetim kaydı), değişen dosyaları ve test sonuçlarını raporla. Sonraki modüle geçme.
