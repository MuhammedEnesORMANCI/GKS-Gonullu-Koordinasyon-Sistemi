# GKS — Elle Test Listesi (web + Android)

Son güncelleme: 16.11.2026 (commit `8469734` itibarıyla). Her senaryoda **Adımlar** ve **Beklenen** var. Sonucu kutuya işaretleyin; beklenenden farklıysa ekran görüntüsü ve saatle not alın (denetim kaydında saatle bulunur).

Kısaltmalar: **W** = web (http://localhost:5173), **A** = Android, **ADM** kurum yöneticisi, **KOR** koordinatör, **GON** gönüllü, **TEK** teknik destek, **UYM** uyum.

---

## 0. Başlatma

### 0.1 İlk kez V15 ile başlatma (bir kez yapılır — ayrı DB kullanıcısı, ADR-020)

Geliştirme veritabanı şu an V14'te; V15 bir sonraki backend açılışında uygulanır. **Önce bu adımlar yapılmazsa backend açılmaz** (uygulama artık `gks_app` kullanıcısıyla bağlanır). Ayrıntı: `docs/operations/db-roles.md`.

1. Backend'i durdurun (bootRun penceresinde Ctrl+C). Web (Vite) açık kalabilir.
2. `.env` dosyasına ekleyin (değerleri siz seçin; `POSTGRES_PASSWORD`'den farklı):
   ```
   GKS_DB_APP_USER=gks_app
   GKS_DB_APP_PASSWORD=<güçlü parola>
   ```
3. Kökte: `docker compose up -d postgres` → kapsayıcı yeniden oluşturulur, **veri korunur**. (`docker compose down -v` KULLANMAYIN.)
4. `docker compose exec postgres sh /docker-entrypoint-initdb.d/10-gks-app-user.sh` → `Uygulama kullanıcısı hazır: gks_app`.
5. `cd backend && .\gradlew.bat bootRun` → günlükte `Successfully applied 1 migration ... v15`. `curl http://localhost:8080/actuator/health` → `UP`.

- [ ] **Beklenen:** backend açılır; mevcut operasyon, kullanıcı ve denetim kayıtları yerinde.
- [ ] **Doğrulama:** `docker compose exec postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB> -c "set role gks_app; delete from audit.audit_entries"` → `permission denied`.

### 0.2 Sonraki başlatmalar

1. `docker ps` → `gks-postgres-1`, `gks-mailpit-1` çalışıyor.
2. `cd backend && .\gradlew.bat bootRun` (aynı anda **tek** bootRun).
3. `cd web && npm run dev` → http://localhost:5173.
4. Android: telefon USB'de, `adb reverse tcp:8080 tcp:8080`; uygulamanın son sürümü yüklü (Android Studio'dan Run veya `.\gradlew.bat installDebug`).
5. Mailpit (davet e-postaları): http://localhost:8025.

---

## 1. Test hesapları

| Hesap | Rol | Nerede kullanılır | Nasıl hazırlanır |
|---|---|---|---|
| `adm@…` | ADM (kurum) | W, A (yönetim, TOTP) | Mevcut ilk yönetici (`.env` LD-54) |
| `kor1@…` | KOR — Operasyon A | W, A (TOTP) | ADM: davet (kurum) → kayıt → `/roller`'dan KOR (Operasyon A) |
| `kor2@…` | KOR — Operasyon B | W | Aynı şekilde, yalnızca Operasyon B |
| `gon1@…`, `gon2@…` | GON — Operasyon A | A (telefon) | KOR1: Operasyon A'ya davet → A'da "Kod ile kayıt" / davet kabulü |
| `gon3@…` | GON — Operasyon A, grupta değil | A veya demo betiği | `node scripts/demo-davet-kabul.mjs` |
| `tek@…` | TEK | W | ADM: `/roller` |
| `uym@…` | UYM | W | ADM: `/roller` |

Operasyonlar: **Operasyon A = Aktif** (alarm ve konum yalnızca aktif operasyonda), **Operasyon B = Planlandı**. Operasyon A'da bir grup: "Arama" (lider gon1, üye gon2). ADM/KOR için telefonda TOTP uygulaması gerekir.

---

## 2. Roller ve menüler

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| R1 | W: ADM ile giriş (TOTP) | Menü: Operasyonlar, Davetler, Duyurular, Acil alarmlar, Roller, Kullanıcılar, Denetim kayıtları | [ ] |
| R2 | W: ADM, Operasyon A (KOR değil) | Görevler, gruplar, duyuru, **acil alarm**, **son konumlar** açık; "salt-okur" bandı yok (ADR-015/018/020) | [ ] |
| R3 | W: KOR1 giriş | Yalnızca Operasyon A yönetimi; Roller/Kullanıcılar(tam)/Denetim yok | [ ] |
| R4 | W: KOR1 → Operasyon B adresini elle aç | 403 / yetki iletisi; veri görünmez | [ ] |
| R5 | W: TEK giriş (TOTP istemez) | Kullanıcılar (ad + durum), devre dışı/oturum kapatma; operasyon yönetimi yok | [ ] |
| R6 | W: UYM giriş | Yalnızca "Denetim kayıtları" (salt-okur) | [ ] |
| R7 | W: gon1 ile web girişi | "Bu hesap web paneline erişemez; mobil uygulamayı kullanın." | [ ] |
| R8 | A: gon1 giriş (TOTP yok) | Ana sayfa, Görevler, Gruplar, Bildirimler, Hesap; Yönetim sekmesi yok | [ ] |
| R9 | A: KOR1 giriş | TOTP adımı; Yönetim sekmesi görünür | [ ] |

## 3. Rol atama (W `/roller`, yalnızca ADM)

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| RA1 | kor2'ye Operasyon A KOR rolü ver | Listede görünür; kor2 yeniden yüklemede Operasyon A'yı yönetir | [ ] |
| RA2 | Aynı rolü tekrar ver | "Kayıt zaten var" | [ ] |
| RA3 | kor2'nin Operasyon A rolünü onayla kaldır; kor2'nin açık sekmesini izle | kor2 sekmesinde menü yenilenir (SSE `subscription.revoked`), Operasyon A işlemleri 403 | [ ] |
| RA4 | ADM kendi ADM rolünü kaldırmayı dener | 403 | [ ] |
| RA5 | `/denetim` → "Rol atandı/kaldırıldı" | Kayıtlar, yetki dayanağı "Kurum yöneticisi yetkisi" | [ ] |

## 4. Kullanıcı yönetimi (W `/kullanicilar`)

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| K1 | ADM: gon3'ü devre dışı bırak (neden yaz) | Durum "Devre dışı"; gon3'ün telefonu bir sonraki istekte giriş ekranına döner ("Hesabınız devre dışı") | [ ] |
| K2 | ADM: gon3'ü etkinleştir | Giriş yeniden mümkün | [ ] |
| K3 | ADM: kor1 için "Oturumları kapat" | kor1'in web ve telefon oturumları kapanır; web'de açık sekme hemen giriş ekranına gider (SSE `session.revoked`) | [ ] |
| K4 | ADM: gon2 parola sıfırla | Mailpit'te geçici parola; ilk girişte parola değişikliği zorunlu | [ ] |
| K5 | ADM: kor1 MFA sıfırla | kor1'in sonraki girişinde yeni TOTP kurulumu | [ ] |
| K6 | TEK: ADM hesabını devre dışı bırakmayı dener | 403 | [ ] |
| K7 | ADM: kendi hesabını devre dışı bırakmayı dener | 403 | [ ] |
| K8 | `/denetim` | Devre dışı kaydında neden **metni yok**, yalnızca "neden verildi" | [ ] |

## 5. Duyuru (3 hedef + kurum)

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| D1 | W KOR1 `/duyurular`: Operasyon A, hedef **operasyonun tamamı**, önizle, gönder | Önizlemede tüm katılımcılar; gon1/gon2/gon3'ün telefonunda gelen kutusu **yenilemeden** güncellenir; uygulama açıksa yerel bildirim | [ ] |
| D2 | Hedef **gruplar** ("Arama") | Yalnızca gon1, gon2 alır; gon3 almaz | [ ] |
| D3 | Hedef **kişiler** (gon3) | Yalnızca gon3 alır | [ ] |
| D4 | W ADM: kurum duyurusu | Kurumdaki tüm aktif kullanıcılar alır | [ ] |
| D5 | A KOR1 (Yönetim → Operasyon A → Duyurular): üç hedefle tekrarla | Aynı sonuçlar | [ ] |
| D6 | İkinci tarayıcıda KOR1 duyuru geçmişi açıkken birincide gönder | Geçmiş yenilemeden güncellenir (SSE `announcement.sent`) | [ ] |
| D7 | `/denetim` | "Duyuru gönderildi"; başlık/metin yazılmamış, yalnızca hedef türü ve sayılar | [ ] |

## 6. Acil alarm

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| AL1 | W KOR1: Operasyon **B** (Planlandı) → Acil alarmlar | "Acil alarm gönder" pasif; "yalnızca Aktif durumdaki operasyonda…" uyarısı ve aktifleştirme bağlantısı (16.11 düzeltmesi) | [ ] |
| AL2 | Operasyon **A** → "Acil alarm gönder": mesaj + istenen eylem, grup "Arama" + kişi gon3, **Önizle** | Onay penceresinde 3 alıcı ve geldikleri grup; KOR1 alıcı değil | [ ] |
| AL3 | "3 kişiye gönder" | Alarm sayfası açılır: durum **Açık**, Bekliyor: 3 | [ ] |
| AL4 | A gon1 (uygulama açık) | Birkaç saniye içinde **üstte kırmızı alarm bandı** ve **yüksek öncelikli bildirim** (ses/titreşim) | [ ] |
| AL5 | gon1 banda dokun → "Yardım gerekiyor" | "Yanıtınız iletildi"; W alarm sayfası **yenilemeden**: Yanıtladı 1, Yardım gerekiyor 1, gon1 satırı en üstte, "Görüldü" zamanı dolu | [ ] |
| AL6 | gon1 yanıtı "Yoldayım" yapar | Tabloda güncellenir; `/denetim` "Alarma yanıt verildi" (önceki seçenek görünür) | [ ] |
| AL7 | gon2 yanıt vermez | "Teslim edildi"/"Görüldü" sütunlarında zaman veya "bilinmiyor" | [ ] |
| AL8 | W KOR1: **İptal et** (neden yazarak) | Durum "İptal edildi"; telefonlarda "ALARM İPTAL EDİLDİ" bildirimi; bant kaybolur; gon2 yanıt vermeye çalışırsa "kapatıldı veya iptal edildi" | [ ] |
| AL9 | İptal edilen alarmda "Düzeltilmiş alarm gönder" | Form önceki metinle dolu; gönderilince eski alarmda "Bu alarm düzeltildi" bağlantısı | [ ] |
| AL10 | W ADM (KOR değil): yeni alarm gönder ve **Kapat** | Çalışır; `/denetim` yetki dayanağı "Kurum yöneticisi yetkisi" | [ ] |
| AL11 | (İsteğe bağlı, ~12 dk) bir alıcı hiç yanıt vermez | 5 hatırlatma sayacından sonra "Yanıtsız"; "Yanıtsızlara yeniden gönder" etkinleşir, gönderince gelen kutusunda alarm yeniden okunmamış | [ ] |
| AL12 | Açık alarm varken Operasyon A'yı "Tamamlandı" yap | Alarm "Kapatıldı — operasyon kapandığı için". **Testten sonra operasyonu yeniden aktif edemeyebilirsiniz; bu adımı en sona bırakın.** | [ ] |
| AL13 | Uygulama tamamen kapalıyken (görev listesinden kaydırılmış) alarm gönder | **Bildirim gelmez** (beklenen, FCM yok). Uygulama açılınca bant görünür | [ ] |

## 7. Konum

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| L1 | A gon1: Operasyon A → "Konum paylaşımı" kartı | Kimin gördüğü (koordinatör ve kurum yöneticisi) ve 30 gün saklama yazıyor | [ ] |
| L2 | "Paylaşımı başlat" → konum iznini **reddet** | "Konum izni verilmedi; paylaşım başlatılmadı" | [ ] |
| L3 | Tekrar başlat → izin ver (Android 13+ bildirim izni de sorulur) | Kalıcı bildirim "Konum paylaşılıyor" + "Durdur"; kartta "Konumunuz paylaşılıyor" | [ ] |
| L4 | W KOR1: Operasyon A → Son konumlar (en geç ~1 dk) | gon1 satırı "Güncel"; paylaşmayanlar listesinde gon2 "Paylaşım kapalı" | [ ] |
| L5 | W ADM: aynı sayfa | Görünür (ADR-020) | [ ] |
| L6 | W UYM/TEK: aynı adres | "Konumları yalnızca operasyonun koordinatörü ve kurum yöneticisi görebilir." | [ ] |
| L7 | Sayfayı 3 kez yenile → `/denetim` (ADM) | Her görüntüleme ayrı "Konumlar görüntülendi" kaydı; ayrıntıda koordinat yok | [ ] |
| L8 | Telefonda GPS'i kapat | Birkaç dakika sonra tabloda "Güncel değil (2 dk+)" → 15 dk sonra "Son bilinen konum" | [ ] |
| L9 | Bildirimdeki "Durdur" | Bildirim kalkar; W'de gon1 "Paylaşım durduruldu" (koordinat gösterilmez) | [ ] |
| L10 | Operasyon B (Planlandı) için kart | Kart görünmez (yalnızca aktif operasyon) | [ ] |
| L11 | Paylaşım açıkken uygulamayı arka plana al, W'den gon1'e duyuru gönder | Konum servisi çalıştığı için **bildirim gelir** (ADR-021) | [ ] |

## 8. Tarih-saat seçici (A)

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| T1 | A KOR1: Yönetim → Operasyon A → Görev oluştur → Başlangıç/Bitiş seçiciyle 14:30–16:00 | Seçici yerel saati gösterir; kayıttan sonra görev ayrıntısında 14:30–16:00 | [ ] |
| T2 | Aynı görevi W'de aç | Aynı saatler (Türkiye saati); kayma yok | [ ] |
| T3 | Bitişi başlangıçtan önce seç | Alan hatası, kayıt yapılmaz | [ ] |
| T4 | Operasyon düzenle → planlanan bitiş tarihi seç/temizle | Kaydedilir; temizlenince boş kalır | [ ] |

## 9. Canlı akış (SSE) ve bildirimler

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| S1 | W: KOR1 iki sekmede; birinde bildirim okut | Diğer sekmede zil rozeti yenilemeden azalır | [ ] |
| S2 | W: ADM sekmesi açıkken başka sekmeden "Tüm oturumlardan çık" | Açık sekme giriş ekranına döner | [ ] |
| S3 | W: tarayıcı geliştirici araçları → Ağ → `stream` | `text/event-stream`, 15 sn'de bir `: ping` | [ ] |
| S4 | W: backend'i yeniden başlat | Sekme birkaç saniye içinde yeniden bağlanır, sayfa yenilemeden veriler güncel | [ ] |
| S5 | A gon1 uygulama açık; W'den görev ata / duyuru gönder | Bildirim sekmesi rozeti **yenilemeden** artar | [ ] |
| S6 | A: uçak modunu 30 sn aç-kapa | Yeniden bağlanır; arada gönderilen duyuru da gelir (Last-Event-ID) | [ ] |
| S7 | A (Android 13+): ilk girişte bildirim izni açıklaması → "Daha sonra" | Uygulama çalışır; bildirim gelmez ama bant/gelen kutusu güncellenir | [ ] |
| S8 | A: Ayarlar → Uygulamalar → GKS → Bildirimler | "Acil alarmlar" (yüksek önem) ve "Duyurular" kanalları var | [ ] |
| S9 | A: uygulama arka planda, konum servisi **kapalı**; duyuru gönder | Bildirim **gelmez** (beklenen); uygulama açılınca gelen kutusunda | [ ] |

## 10. Denetim kayıtları (W `/denetim`, ADM ve UYM)

| # | Adımlar | Beklenen | ✓ |
|---|---|---|---|
| AU1 | Yanlış parolayla giriş dene, sonra doğru gir | "Başarısız giriş" (kişi adı, istemci Web, IP) ve "Giriş yapıldı"; parola görünmez | [ ] |
| AU2 | Olmayan e-postayla giriş dene | "Başarısız giriş", kişi "Tanınmayan hesap"; e-posta yazılmaz | [ ] |
| AU3 | Telefondan çıkış yap | "Çıkış yapıldı", istemci Android | [ ] |
| AU4 | K5 sonrası kor1 TOTP'yi yeniden kurar | "İki adımlı doğrulama kuruldu" | [ ] |
| AU5 | Süzgeç: eylem "Acil alarm gönderildi", dayanak "Kurum yöneticisi yetkisi" | Yalnızca ADM'nin alarmları | [ ] |
| AU6 | Sayfada düzenleme/silme | Hiçbir kayıtta düzenle/sil düğmesi yok | [ ] |
| AU7 | UYM ile aynı sayfa | Okunur; başka menü yok | [ ] |

---

**Bilinen sınırlar (hata değil):** FCM yok — uygulama kapalıyken alarm/duyuru bildirimi gelmez; harita yok (tablo); alarm hatırlatmaları yalnızca sayaç ilerletir; Android'de alarm oluşturma/izleme ekranı yok (web'de). Kökte duran `android/app/google-services.json` henüz kullanılmıyor ve commit edilmedi.
