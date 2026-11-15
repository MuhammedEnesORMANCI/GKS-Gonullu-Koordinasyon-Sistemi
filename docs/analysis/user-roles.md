# Kullanıcı Rolleri ve Yetki Seviyeleri

> **Durum: Kesinleşti** (K-07, K-08, K-09, K-15, K-26 ve diğerleri; bkz. [`decision-log.md`](decision-log.md)). Kaynak belgenin §3 bölümü ile FR-03, BR-01 ve NFR-15'ten türetildi. Önceki sürümdeki `?` hücrelerinin tamamı onaylanan kararlarla çözüldü. Hücrenin dayanağı köşeli parantez içinde.
>
> **Çerçeve:** Mobil platform yalnızca **Android**'dir (K-03). Tüm kullanıcılar sentetik hesaplardır (K-04). Akademik prototipte "Kurum Yöneticisi" ve "Veri Sorumlusu" rolleri kurgusal bir senaryo kurumunu temsil eder (VS-01).

## 1. Kanonik rol listesi

| Kod | Rol | Belgedeki karşılığı | Platform (MVP) | Sistem kullanıcısı mı? |
|---|---|---|---|---|
| R-ADM | **Kurum Yöneticisi** | "Kurum yöneticisi"; FR-03'teki "Sistem yöneticisi" (CON-01) | Web | Evet |
| R-KOR | **Operasyon Koordinatörü** | "Operasyon koordinatörü" | Web (aynı kişi gönüllü olarak da atandıysa Android) [VS-06] | Evet |
| R-LDR | **Grup Lideri** | "Grup lideri" | Android [VS-06] | Evet |
| R-GON | **Gönüllü** | "Gönüllü" | Android | Evet |
| R-TEK | **Teknik Sistem Yöneticisi** | "Teknik sistem yöneticisi" | Altyapı ve web | Evet, iş verisine erişimi yok |
| R-UYM | **Veri Sorumlusu / Uyum (Denetçi)** | "Veri sorumlusu / hukuk / bilgi güvenliği temsilcisi" | Web | Evet: denetim kayıtlarını ve politika ayarlarını **salt-okur** görür [K-26] |

## 2. Kapsam modeli [K-07]

Roller global değil, **kapsamlı** atanır:

```
Kurum (prototipte kurgusal senaryo kurumu)
 └── Operasyon  (Planlandı / Aktif / Askıda* / Tamamlandı / İptal)   *Genişletilmiş MVP
      └── Grup   (OPERATIONAL: operasyona bağlı, operasyonla arşivlenir) [K-11]
           └── Görev → Atanan kişiler (kişi bazlı durum, kapasite) [K-12]
```

| Kapsam | Anlamı |
|---|---|
| **Kurum** | Kurumdaki tüm kayıtlar |
| **Op** | Kullanıcının koordinatör olarak atandığı operasyon(lar) ve bunların grupları/görevleri |
| **Grup** | Kullanıcının lideri olduğu grup(lar) |
| **Kendi** | Yalnızca kullanıcının kendi kaydı, üyesi olduğu gruplar ve kendisine atanan görevler/mesajlar |

**Rol ataması = (kullanıcı, rol, kapsam).** Örnek: Ayşe, "Sel-2026" operasyonunda koordinatör, "Lojistik-A" grubunda gönüllü olabilir.

**Çoklu rol [K-07]:** Bir kullanıcı birden çok role sahip olabilir. Grup lideri ve koordinatör aynı zamanda gönüllü olarak alarm ve görev alır. Bir alarmın göndereni, kendi gönderdiği alarmın alıcısı olmaz.

**Operasyonel işlem ilkesi [K-07]:** Görev, acil alarm ve konum görüntüleme gibi operasyonel işlemleri yalnızca o operasyonun KOR'u yapar. ADM bunlara ihtiyaç duyarsa kendini o operasyona koordinatör olarak atar. Bu atama denetim kaydına yazılır. Gerekçeli acil erişim (break-glass) onaylandı ama R2'de gelecek [K-15].

## 3. Rol tanımları

### R-ADM Kurum Yöneticisi
- **Sorumluluk:** Organizasyon yapısı, operasyonların oluşturulması, rol atamaları, kurum geneli duyuru, politikalar (bildirim tekrarı, BR-10; MVP'de sabit yapılandırma), MFA sıfırlama.
- **Sınır (belge §3):** Tüm verilere varsayılan ve sınırsız erişim yok. Operasyon verisine operasyonel erişim için kendini KOR olarak atar.
- **Güvenlik:** MFA zorunlu [K-19].

### R-KOR Operasyon Koordinatörü
- **Sorumluluk:** Kendi operasyonlarında davet, grup oluşturma, lider atama, üye yönetimi, görev atama ve kapasite takibi, duyuru, acil alarm, konum izleme, yanıtsızların takibi, operasyonu aktif etme ve kapatma.
- **Sınır:** Yalnızca sorumlu olduğu operasyonlar ve gruplar.
- **Güvenlik:** MFA zorunlu [K-19].

### R-LDR Grup Lideri
- **Sorumluluk:** Kendi grubunu ve üye listesini görme; grup bilgisini ve toplanma noktasını düzenleme [K-08]. Güncelleme üyelere otomatik bildirim üretir [K-09].
- **Sınır:** Grup oluşturamaz; üye ekleyemez ve çıkaramaz [K-08]. Görev oluşturamaz ve görev durumunu ilerletemez [K-12, VS-17]. Duyuru ve alarm gönderemez [K-09]. Başkalarının konumunu göremez [K-15].
- **Kural:** Her aktif grubun bir lideri vardır [K-32].

### R-GON Gönüllü
- **Sorumluluk:** Profil, üyesi olduğu grupları görme, görev kabul/ret, alarm yanıtı, kendi konum paylaşımını başlatma ve durdurma [K-14].
- **Sınır:** Kendi kapsamı. Gruplara kendisi katılamaz; davet ve KOR ataması ile girer [K-06]. Gruptan kendisi ayrılması R2'de [K-28]. Diğer üyelerin yalnızca görünen adını görür (§5).

### R-TEK Teknik Sistem Yöneticisi
- **Sorumluluk:** Sistem sağlığı, yapılandırma, teknik loglar, yedekleme, teknik gerekçeyle hesap devre dışı bırakma ve oturum iptali.
- **Sınır:** Uygulama arayüzünden iş verisine (konum, görev içeriği, duyuru, alarm metni) erişemez. Veritabanına doğrudan erişim operasyonel prosedürle sınırlanır ve kayda alınır.

### R-UYM Veri Sorumlusu / Uyum (Denetçi)
- **Sorumluluk:** İş denetim kayıtlarını ve politika ayarlarını salt-okur görme [K-26].
- **Sınır:** Hiçbir kaydı değiştiremez.

## 4. Yetki matrisi

Hücre değerleri: **Kurum** = kurum geneli · **Op** = kendi operasyonları · **Grup** = lideri olduğu gruplar · **Kendi** = yalnızca kendi kaydı/atamaları · **—** = yetki yok · **R2/R3** = o sürümde gelecek · `(K)` = işlem denetim kaydına yazılır (FR-20, BR-08).

### 4.1 Hesap ve kullanıcı yönetimi

| İşlem | ADM | KOR | LDR | GON | TEK | UYM |
|---|---|---|---|---|---|---|
| Kullanıcı davet etme (FR-01) [K-06] | Kurum (K) | Op (K) | — | — | — | — |
| Rol ve kapsam atama/kaldırma (FR-03) [K-07] | Kurum (K) | Op: yalnızca grup lideri atama (K) | — | — | — | — |
| Hesabı devre dışı bırakma (FR-22) | Kurum (K) | — | — | — | Teknik gerekçeyle (K) | — |
| Başka kullanıcının oturumlarını iptal etme (FR-22) | Kurum (K) | — | — | — | Kurum (K) | — |
| MFA sıfırlama [K-19] | Kurum (K) | — | — | — | — | — |
| Kendi oturumunu kapatma / tüm cihazlardan çıkış | Kendi | Kendi | Kendi | Kendi | Kendi | Kendi |
| Kendi profilini görme/güncelleme (FR-02) | Kendi | Kendi | Kendi | Kendi | Kendi | Kendi |
| Başka kullanıcının iletişim bilgilerini görme [NFR-15] | Kurum | Op | — | — | — | — |
| Parola sıfırlama (Çekirdek: ADM yapar; Genişletilmiş: kullanıcı kendisi) | Kurum (K) | — | Kendi* | Kendi* | — | — |
| Hesap kapatma talebi (GAP-02, Genişletilmiş) | İşler (K) | — | Talep eder | Talep eder | — | Denetim kaydından izler |

\* Genişletilmiş MVP.

### 4.2 Operasyon, grup ve toplanma noktası

| İşlem | ADM | KOR | LDR | GON | TEK | UYM |
|---|---|---|---|---|---|---|
| Operasyon oluşturma ve KOR atama (FR-19) | Kurum (K) | — | — | — | — | — |
| Operasyonu aktif etme / kapatma (FR-19) | Kurum (K) | Op (K) | — | — | — | — |
| Operasyonu askıya alma (Genişletilmiş) | Kurum (K) | Op (K) | — | — | — | — |
| Grup oluşturma; lider seçimi zorunlu (FR-04) [K-08, K-32] | — (KOR olarak) | Op (K) | — | — | — | — |
| Gruba üye ekleme/çıkarma (FR-04) [K-08] | — (KOR olarak) | Op (K) | — | — | — | — |
| Grup lideri atama/değiştirme [K-32] | — (KOR olarak) | Op (K) | — | — | — | — |
| Grup bilgisini düzenleme [K-08] | — | Op | Grup | — | — | — |
| Grubu ve üye listesini görme | Kurum | Op | Grup | Üyesi olduğu gruplar; yalnızca görünen adlar (BR-01) | — | — |
| Toplanma noktası paylaşma/güncelleme (FR-09) [K-08] | — | Op | Grup | — | — | — |
| Toplanma noktasını görme | Kurum | Op | Grup | Kendi grupları | — | — |

### 4.3 Görev [K-12]

| İşlem | ADM | KOR | LDR | GON | TEK | UYM |
|---|---|---|---|---|---|---|
| Görev oluşturma, atama, kapasite belirleme (FR-07) | — (KOR olarak) | Op (K) | — | — | — | — |
| Görev düzenleme / iptal (GAP-05) | — (KOR olarak) | Op (K) | — | — | — | — |
| Atamayı kaldırma | — (KOR olarak) | Op (K) | — | — | — | — |
| Görevi kabul/ret etme (FR-08) | — | — | Kendi | Kendi | — | — |
| "Devam ediyor" / "Tamamlandı" işaretleme [VS-17] | — | Op | — | — | — | — |
| Görev, atama ve kapasite durumlarını izleme | Kurum (salt-okur) | Op | Grup (yalnızca kendi grubuna atanan görevler, salt-okur) | Kendi | — | — |

### 4.4 İletişim [K-09, K-13, K-17]

| İşlem | ADM | KOR | LDR | GON | TEK | UYM |
|---|---|---|---|---|---|---|
| Kurum geneline duyuru (FR-06) | Kurum (K) | — | — | — | — | — |
| Gruba / kişilere duyuru (FR-06) | — (KOR olarak) | Op | — | — | — | — |
| Acil alarm gönderme; yalnızca aktif operasyonda (FR-13) | — (KOR olarak) | Op (K) | — | — | — | — |
| Alarmı iptal etme / düzeltme gönderme (GAP-07) | — (KOR olarak) | Op (K) | — | — | — | — |
| Yanıtsızlara yeniden gönderme (K-13) | — (KOR olarak) | Op (K) | — | — | — | — |
| Alarmı yanıtlama (FR-15) | Kendi | Kendi | Kendi | Kendi | — | — |
| Alıcı durumu ve yanıtsızlar listesi (FR-16, FR-18) | Kurum (salt-okur) | Op | — | — | — | — |
| Eskalasyon alıcısı olma (FR-17) | R2 | R2 | R2 | — | — | — |
| Bildirim/eskalasyon politikasını yapılandırma (BR-10) | R2 (MVP'de sabit yapılandırma) | — | — | — | Teknik parametreler (K) | Salt-okur |
| Grup sohbeti okuma/yazma (FR-05) | R2 | R2: Op | R2: Grup | R2: Kendi grupları | — | — |

### 4.5 Konum [K-14, K-15, K-16]

| İşlem | ADM | KOR | LDR | GON | TEK | UYM |
|---|---|---|---|---|---|---|
| Kendi konum paylaşımını başlatma/durdurma (FR-10) | — | — | Kendi | Kendi | — | — |
| Kural ile konum paylaşımını etkinleştirme (KS-03) | R2, kullanıcı onayıyla (CON-21) | R2, kullanıcı onayıyla | — | — | — | — |
| Son bilinen konumu görme (FR-11) | — (KOR olarak); gerekçeli erişim R2 (K) | Op, yalnızca aktif operasyon (K) | — | — | — | — |
| Konum geçmişini görme | Yok (K-16) | Yok (K-16) | — | — | — | — |

### 4.6 Denetim ve sistem [K-26]

| İşlem | ADM | KOR | LDR | GON | TEK | UYM |
|---|---|---|---|---|---|---|
| İş denetim kayıtlarını görme (FR-20) | Kurum | — | — | — | — | Kurum (salt-okur) |
| Teknik/sistem loglarını görme | — | — | — | — | Kurum | — |
| Veri dışa aktarma (GAP-09) | R2 (K) | R2 (K) | — | — | — | — |
| Sistem yapılandırma, yedekleme | — | — | — | — | Kurum (K) | — |

## 5. Profil alanı görünürlüğü [K-10, NFR-15]

| Alan | Kendisi | Aynı grubun üyesi | Grup lideri | Koordinatör (Op) | Kurum yöneticisi |
|---|---|---|---|---|---|
| Görünen ad | ✓ | ✓ | ✓ | ✓ | ✓ |
| Ad-soyad | ✓ | — | ✓ | ✓ | ✓ |
| E-posta / telefon | ✓ | — | — | ✓ | ✓ |
| Yetkinlik / uygunluk etiketi | R2 | R2 | R2 | R2 | R2 |
| Konum | ✓ (kendi paylaşım durumu) | — | — | ✓ (aktif operasyon) | — (KOR olarak) |

## 6. Yetki ilkeleri

1. **Sunucu tarafı denetim (NFR-02):** Her istekte rol, kapsam ve kaynak üzerindeki yetki kontrol edilir. Arayüzde gizlemek yeterli değildir.
2. **En az yetki ve varsayılan red (NFR-15):** Matriste açıkça izin verilmeyen işlem yasaktır.
3. **Görevler ayrılığı:** Teknik yönetici iş verisini görmez. Kurum yöneticisi operasyonel verilere KOR ataması veya (R2'de) gerekçeli erişimle ulaşır; her ikisi de kayda alınır.
4. **Yetki değişiklikleri kayda alınır (FR-20).**
5. **Operasyon kapanınca operasyon kapsamlı konum yetkisi düşer (BR-09).**
6. **Rol yaşam döngüsü:** Operasyon kapandığında o operasyondaki KOR ve lider atamaları otomatik pasifleşir (RO-09).

## 7. Grup içi roller (gelecek sürümler)

Genişleme talebiyle (FX-01 – FX-03) kurum rollerinden ayrı, **yalnızca bir grubun içinde geçerli** roller gelecek. Ayrıntı: `docs/architecture/authorization.md`.

| Grup rolü | Sürüm | Yetkiler |
|---|---|---|
| `MEMBER` | MVP | Grubu görme. R2'de sohbet |
| `LEADER` | MVP | Operasyon grubunda grup bilgisi ve toplanma noktası [K-08] |
| `ADMIN` | R2 | Üye ekleme/çıkarma, katılma talebi onayı, grup ayarları (kurum üst sınırına tabi) |
| `MODERATOR` | R2/R3 | Mesaj silme, görüşmede katılımcı yönetimi |
| `OWNER` | R3 | Topluluk grubunun sahibi; sahiplik devri, arşivleme. Her aktif topluluk grubunda bir tane [K-32] |

**Değişmez kural:** Grup rolleri hiçbir zaman acil alarm gönderme, konum görme, kurum geneline duyuru, rol atama veya denetim kaydı görme yetkisi vermez. Bu yetkiler yalnızca kurum rolünden (§1) gelir [K-09].

## 8. Karar referansları

Önceki sürümdeki açık sorular kapandı: Q-03 → K-07, Q-04 → K-08, Q-05 → K-09, Q-11 → K-15, Q-17 → K-19, Q-26 → K-26. Kurum yöneticisinin konum erişimi: MVP'de KOR ataması ile, R2'de gerekçeli erişimle (K-15).
