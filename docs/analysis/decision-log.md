# Karar Kaydı

> Projedeki gereksinim kararlarının **tek doğruluk kaynağıdır.** Diğer dokümanlardaki karar durumları bu dosyaya göre okunur. Soru kimlikleri (`Q-xx`) `requirements-review.md` §8 ve §9.2'den gelir.
>
> Son güncelleme: 14.11.2026 · **Gereksinim soruları aşaması kapandı:** 33 sorunun tamamı kesinleşti. Açık konular varsayımlarla çözüldü (§3). Varsayımlar kesin karardan ayrı gösterilir.

## 0. Durum tanımları

| Durum | Anlamı |
|---|---|
| **Kesinleşti** | Proje sahibi (kullanıcı) tarafından açıkça verilen karar. |
| **Onaylandı / Kesinleşti** | Analistin önceki önerisi; proje sahibi önerilerin tamamını kabul etti. Kesin gereksinim olarak uygulanır. |
| **Varsayım (VS)** | Gerçek bilgi olmadan, makul ve güvenli varsayımla çözülen konu. Kesin gereksinim gibi uygulanır, ancak `Gerçek bilgi gerekir` işaretliyse ilk fırsatta doğrulanmalıdır. |
| **Ertelendi** | Yalnızca sonraki bir sürümü ilgilendiren ayrıntı; o sürümün analizinde belirlenecek. MVP'yi engellemez. |

**Karar mercii (VS-01):** Gereksinim kararlarını proje ekibi alır. Proje kapsamı ve teslimat için son değerlendirme mercii akademik danışmandır. Belgede "kurum belirler" denen yerlerde gerçek bir kurum yetkisi varsayılmaz. Bu yerler prototipteki **kurgusal senaryo kurumu** adına proje ekibince kararlaştırılmıştır.

---

## 1. Kesinleşen kararlar (33)

### 1.1 Proje sahibinin doğrudan kararları

| Karar | Soru | Karar metni | Durum |
|---|---|---|---|
| **K-01** | Q-01 | Projenin hedefi ders projesi / akademik prototip. | Kesinleşti |
| **K-02** | Q-13 | Başlangıç kapasitesi 500 kullanıcı ve 5 operasyon. | Kesinleşti |
| **K-03** | Q-16 | İlk sürüm Android + Web. iOS sonraki sürümde değerlendirilecek (taahhüt değil). | Kesinleşti |
| **K-04** | Q-23 | İlk aşamada yalnızca sentetik veri kullanılacak. | Kesinleşti |
| **K-05** | Q-25 | Proje ders dönemi içinde tamamlanacak. Ekip 4 kişi; yetkinlikler backend, mobil uygulama, web ve test. | Kesinleşti |

### 1.2 Onaylanan öneriler

Proje sahibinin özet kararları kalın yazıldı. Ayrıntılar önceki önerilerden korundu. Önceden önerisi olmayan noktalar `[VS]` ile işaretlendi ve §3'teki varsayımlara bağlandı.

| Karar | Soru | Karar | Ayrıntı |
|---|---|---|---|
| **K-06** | Q-02 | **Kayıt kurum veya operasyon daveti üzerinden.** | Açık kayıt yok. Davet ADM (kurum geneli) veya KOR (kendi operasyonu) tarafından oluşturulur. Doğrulama e-postadaki tek kullanımlık davet bağlantısıyla yapılır. SMS OTP yok (K-20). Davet geçerliliği 72 saat `[VS-14]`. Prototipte e-postalar bir geliştirme posta yakalayıcısına düşer (ADR-008). |
| **K-07** | Q-03 | **Yetkilendirme rol ve operasyon kapsamına göre.** | Kanonik roller: ADM, KOR, LDR, GON, TEK, UYM (`user-roles.md` §1). Roller kapsamlı atanır (kullanıcı, rol, kapsam). Bir kullanıcı birden çok role sahip olabilir (eski A-04, A-05). FR-03'teki "Sistem yöneticisi" = Kurum Yöneticisi (CON-01). Operasyonel işlemleri (görev, alarm, konum) yalnızca o operasyonun koordinatörü yapar; ADM bunlar için kendini kayıt altında koordinatör olarak atar. |
| **K-08** | Q-04 | **Grup liderine sınırlı yönetim yetkisi.** | Grup oluşturma ADM ve KOR'da (CON-02). Lider kendi grubunun bilgisini ve toplanma noktasını düzenler ve üye listesini görür. Üye ekleme ve çıkarma yapamaz (KOR yapar). |
| **K-09** | Q-05 | **Duyuru ve alarm yetkisi kurumsal rolden gelir.** | Kurum geneline duyuru yalnızca ADM'de (CON-04). KOR kendi operasyon kapsamına duyuru ve acil alarm gönderir. Grup lideri alarm ve duyuru göndermez; toplanma noktası güncellemesi otomatik bildirim üretir. Alarm, konum ve kurum geneli duyuru grup üyelik rolünden türemez (`authorization.md` §4). |
| **K-10** | Q-06 | Sabit profil alanları. | MVP alanları: ad-soyad, görünen ad, e-posta (giriş için), telefon (isteğe bağlı) `[VS-15]`. Kurumca yapılandırılabilir alan yok (R2). Yetkinlik/uygunluk etiketleri R2. |
| **K-11** | Q-07 | Grup yapısı. `[VS]` | Operasyon grubu (`OPERATIONAL`) bir operasyona bağlıdır; operasyon kapanınca arşivlenir. Kullanıcı aynı anda birden çok grup ve operasyonda olabilir. Toplanma noktası gruba bağlıdır. Kalıcı ekip türü (`STANDING`) R2. `[VS-16]` |
| **K-12** | Q-08 | **Görevlerde kişi bazlı durum ve kapasite takibi.** | Kanonik model (CON-05): atama düzeyinde `Yanıt bekliyor → Kabul / Ret`, görev düzeyinde `Taslak → Yayında → Devam ediyor → Tamamlandı / İptal`. Gruba atanan görevde her üye için ayrı atama kaydı oluşur (AMB-06). Görevin isteğe bağlı bir **gereken kişi sayısı (kapasite)** alanı var. Kabul sayısı kapasiteyi aşamaz. Kapasite dolunca bekleyen atamalar "Kapasite doldu" olarak kapanır ve bildirim gider. Görev durumunu KOR ilerletir. Gönüllü kabulden sonra görevi bırakamaz, gerekirse KOR atamayı kaldırır `[VS-17]`. Varsayılan yanıt süresi 60 dk `[VS-14]`. |
| **K-13** | Q-09 | **Hatırlatmalar MVP'de sınırlı; otomatik eskalasyon sonraki sürümde.** | Yanıt seçenekleri sabit: "Aldım", "Yardım gerekiyor", "Göreve gidiyorum". Hatırlatma 2 dk aralıkla, en fazla 5 kez (push + uygulama içi alarm ekranı). Sonrasında alıcı "Yanıtsız" olur. Yanıt gelince hatırlatma durur. KOR yanıtsızlara tek işlemle yeniden gönderebilir. Otomatik eskalasyon R2 (FR-17, CON-07). Alarm ayrı kanal ve sesle gelir; uygulama ön plandayken tam ekran açılır; yanıt düğmeleri en az 48×48 dp (AMB-14). |
| **K-14** | Q-10 | **Konum kullanıcı onayıyla paylaşılır.** | Paylaşımı gönüllü başlatır ve durdurur. Yalnızca aktif bir operasyonda ve gönüllü o operasyona dahilse mümkün (eski A-07). Güncellik: 2 dk'dan eski konum "güncel değil", 15 dk'dan eski konum "son bilinen". Paylaşım açıkken kalıcı bildirimli bir Android ön plan servisiyle 60 sn'de bir konum alınır `[VS-18]` (ADR-011). Kural tabanlı otomatik etkinleştirme R2'de; o sürümde de kullanıcı onayı gerektirir (CON-21). |
| **K-15** | Q-11 | Konumu yalnızca aktif operasyonun KOR'u görür. | Grup lideri ve gönüllüler başkalarının konumunu görmez (NFR-15). ADM için gerekçeli acil erişim onaylandı ancak R2'ye bırakıldı. MVP'de ADM yalnızca kendini o operasyona koordinatör olarak atayarak, kayıt altında erişir (K-07). |
| **K-16** | Q-12 | **Konum geçmişi MVP'de tutulmaz.** | Kullanıcı ve operasyon başına yalnızca son konum saklanır; yeni ölçüm eskisinin üzerine yazılır. Geçmiş istenirse ayrı bir KVKK değerlendirmesiyle sonraki sürümde ele alınır. |
| **K-17** | Q-14 | **Bildirimler aktif operasyonla ilişkili.** `[VS]` | "Olay" ayrı bir kavram değildir; FR-13'teki referans her zaman bir operasyondur. Acil alarm yalnızca **aktif** bir operasyonda gönderilebilir. Kapsam istisnası: ADM'nin kurum geneli duyurusu operasyona bağlı değildir (CON-20). `[VS-19]` |
| **K-18** | Q-15 | Sağlayıcılar. `[VS]` | Push: Firebase Cloud Messaging. Harita: OpenStreetMap tabanlı (ADR-008). Barındırma: Docker Compose ile tek sunucu demo ortamı (VS-11). Veriler sentetik olduğu için veri yerleşimi kısıtı yok (K-04). `[VS-20]` |
| **K-19** | Q-17 | **MFA yetkili hesaplarda.** | ADM ve KOR için TOTP tabanlı ikinci faktör zorunlu (US-07). MFA sıfırlama yalnızca ADM tarafından yapılır ve kayda alınır. |
| **K-20** | Q-18 | **SMS yedek bildirimi MVP dışında.** | MVP kanalları push (FCM) ve uygulama içi (eski A-06). SMS ve sesli arama sonraki sürümde, ayrı kararla. Yazılım dışı yedek iletişim prosedürü prototipte gerekmez (K-01). |
| **K-21** | Q-19 | **İlk sürüm Türkçe.** | Tek dil (eski A-03). Metinler kaynak dosyalarında tutulur ki ileride dil eklenebilsin. Saat dilimi Europe/Istanbul `[VS-21]`. |
| **K-22** | Q-20 | **Grup sohbeti sonraki sürümde.** | Sohbet R2'de. Eskalasyon MVP'de yalnızca manuel (yanıtsızlar listesi + yeniden gönderim). Bire bir sohbet R3. Mesaj saklama süresi R2 analizinde belirlenir (Ertelendi). |
| **K-23** | Q-21 | **SOS sonraki sürümde.** | Gönüllünün kendi başlattığı yardım çağrısı R3. MVP'de yardım isteği yalnızca alarma "Yardım gerekiyor" yanıtıyla verilir (CON-08). |
| **K-24** | Q-22 | **Çevrimdışı kullanım kontrollü ve sınırlı.** | MVP: çevrimdışı göstergesi ve son senkronizasyon zamanı. Yalnızca alarm yanıtı otomatik yeniden denenir (idempotent). Diğer işlemler "gönderilemedi" olarak işaretlenir ve elle tekrarlanır. Genel çevrimdışı kuyruk ve senkronizasyon R2. |
| **K-25** | Q-24 | **Entegrasyonlar sonraki sürümlerde.** | Kurum entegrasyonu, rapor ve dışa aktarma MVP'de yok (R2/R3). |
| **K-26** | Q-26 | **Denetim kayıtlarına sınırlı erişim.** | İş denetim kayıtlarını yalnızca ADM ve UYM (salt-okur denetçi) görür. Teknik logları TEK görür. Hiçbir rol uygulama üzerinden kayıt değiştiremez veya silemez. Prototipte kayıtlar dönem sonuna kadar saklanır `[VS-22]`. |
| **K-27** | Q-27 | **Topluluk grupları onaylı oluşturma ile (R3).** | Aktif kullanıcı topluluk grubu oluşturma talebinde bulunur, ADM onaylar. Moderasyon ve şikâyet altyapısı R3'ten önce hazır olmalıdır. |
| **K-28** | Q-28 | Gönüllünün gruptan kendi isteğiyle ayrılması R2'de. | MVP'de üyeliği yalnızca KOR sonlandırır. |
| **K-29** | Q-29 | **Uçtan uca şifreli mesajlaşma ilk sürümde yok.** | Gerekçe: denetim, saklama ve moderasyon yükümlülükleriyle çelişir. Aktarım şifrelemesi (TLS) her zaman var. |
| **K-30** | Q-30 | Gruptan ayrılan üye sohbet geçmişini göremez. | Sohbet R2'de geldiğinde uygulanır (D-01). |
| **K-31** | Q-31 | Topluluk grubu acil alarm hedefi olamaz. `[VS]` | K-17 gereği alarm aktif operasyonla ilişkilidir; hedef kitle operasyon kapsamındaki kişi ve gruplardan oluşur. `[VS-23]` |
| **K-32** | Q-32 | **Her aktif grubun bir lideri olmalı.** | Grup oluşturulurken lider seçimi zorunlu. Son lider, yerine yeni lider atanmadan çıkarılamaz. Topluluk gruplarında (R3) aynı kural `OWNER` için geçerli. |
| **K-33** | Q-33 | **Görüşme kaydı yapılmayacak.** | Sesli ve görüntülü görüşmeler (R4/R5) kaydedilmez. |

### 1.3 Diğer onaylanan öneriler

| Grup | Durum | Not |
|---|---|---|
| Çelişki çözümleri CON-01 – CON-17 | Onaylandı / Kesinleşti | `requirements-review.md` §3 ve §9.1, bu dosyanın §4'ü |
| Belirsizlik ve eksik çözüm önerileri (AMB, GAP) | Onaylandı / Kesinleşti | Değerler §5 parametre tablosunda |
| Çalışma varsayımları A-01, A-03 – A-07 | Onaylandı; karşılık gelen K kararlarına dönüştü | A-01 tek kurum (K-07), A-02 → K-04, A-03 → K-21, A-04/A-05 → K-07, A-06 → K-20, A-07 → K-14 |
| MVP kapsamı ve halkalar | Onaylandı / Kesinleşti | `mvp-scope.md` §3 ve §9 |
| ADR-001 – ADR-007 | Kabul edildi | `architecture/decisions.md` |
| ADR-008 – ADR-012 (teknoloji yığını ve ilgili kararlar) | Kabul edildi, VS-09'a dayalı | `architecture/decisions.md` |
| ADR-013 (Android'den kurumsal işlem yok) | Kabul edildi | `architecture/decisions.md`, DR-12 |
| Tasarım kararları TD-01 – TD-22 | Kabul edildi | §1.4 |

---

### 1.4 Tasarım aşaması kararları (14.11.2026)

Tasarım kararları gereksinim kararı değildir; K-01 – K-33'ü değiştirmez, yalnızca uygulanma biçimini kesinleştirir. Ayrıntı: `architecture/design/system-architecture.md` §9.

| Konu | Karar | Durum |
|---|---|---|
| TD-01 – TD-22 | Önerilen çözümler. TD-02 güvenli alternatifle: https davet bağlantısı + statik davet sayfası + elle girilen kod (e-posta istemcileri özel URI şemalarını engeller) | Kabul edildi |
| **DR-01: Tam ekran alarm sınırı** | Uygulama açıkken alarm tam ekran açılır. Arka planda veya kilit ekranında yüksek öncelikli bildirim gösterilir; dokununca alarm ekranı açılır. Kilit ekranında kendiliğinden tam ekran (full-screen intent) MVP'de **yoktur** (US-42 R3). Uygulama, Android'in izin vermediği durumlarda kilit ekranını, Rahatsız Etmeyin'i veya sessiz modu aşmaya çalışmaz | Kesinleşti (K-13 ve RT-03'ün açıklaması; yeni K değil) |
| **DR-12: Android'den alarm gönderimi** | **15.11.2026 güncellemesi (ADR-014):** kurumsal işlemler (alarm dahil) MFA'lı web oturumunda veya ADM/KOR'un TOTP ile açtığı 12 sa mutlak Android yönetim oturumunda yapılır. *Önceki metin:* Kurumsal işlemler (alarm dahil) yalnızca MFA'lı web oturumunda yapılır; Android'den mümkün değildir. Gerekçe: K-19 MFA'yı yalnızca web'de zorunlu kılar; uzun ömürlü mobil oturum ve cihaz kaybı riski. Gelecek seçenekler (step-up MFA, cihaz bağlama, talep-onay) karar gerektirir ve MVP'ye eklenmedi | Kesinleşti (ADR-013) |
| ALERT ↔ NOTIFICATION kardinalitesi | 1:1'den **1:N**'e düzeltildi (iptal ve kapanış ayrı bildirimdir); hatırlatmalar teslim denemesidir | Tasarım düzeltmesi |

## 2. Onay bekleyen kararlar (yok)

**Yok.** Q-02 – Q-12, Q-14, Q-15, Q-17 – Q-22, Q-24 ve Q-26 – Q-33 (28 soru) K-06 – K-33 olarak kesinleşti.

**Ertelenen ayrıntılar** (MVP'yi etkilemez; ilgili sürümün analizinde belirlenecek): otomatik eskalasyon süresi ve hedefi (R2), mesaj saklama süresi (R2), ADM gerekçeli acil erişim akışı (R2), dosya boyutu sınırı ve izinli türlerin son listesi (R3), topluluk grubu moderasyon politikası (R3), SFU ürün seçimi (R4).

---

## 3. Açık konular ve varsayımla çözümleri

Tüm açık konular kapandı. `Gerçek bilgi gerekir = Evet` olan varsayımlar, ekip tarafından gerçek durumla karşılaştırılmalıdır. Karşılaştırma yeni bir karar sorusu değil, bir doğrulama adımıdır (§3.1).

| AK | Konu | Çözüm (varsayım) | VS | Gerçek bilgi gerekir |
|---|---|---|---|---|
| AK-01 | Karar mercii | Proje ekibi karar verir; danışman kapsam ve teslimatın son değerlendiricisidir; kurgusal senaryo kurumu kullanılır | VS-01 | Hayır |
| AK-02 | Kullanıcı hacmi tanımı | 500 **kayıtlı** sentetik kullanıcı. En kötü durum: tek alarm 500 alıcıya. Eşzamanlı 150 aktif oturum. Grup büyüklüğü en fazla 50 | VS-02 | Hayır |
| AK-03 | Eşzamanlı operasyon | 5 operasyonun tamamı aynı anda aktif olabilir (en kötü durum test edilir) | VS-03 | Hayır |
| AK-04 | Performans ve erişilebilirlik hedefleri | Onaylanan değerler (§5) demo ortamında test hedefi. Çalışma süresi (uptime) kabul koşulu değil; yalnızca demo süresince çalışırlık. RPO 24 saat, RTO 4 saat, günlük yedek, geri yükleme dönemde bir kez test edilir | VS-04 | Hayır |
| AK-05 | Android sürümü, cihazlar, tarayıcılar | minSdk 26 (Android 8.0, bildirim kanalları bu sürümle geldi). Hedef SDK, derleme anındaki Google Play gerekliliği. Cihaz matrisi: en az 3 cihaz; en az biri Android 14+, en az biri Android 10–13, en az ikisi farklı üretici (biri agresif pil yönetimli) + emülatör. Web: güncel Chrome, Edge, Firefox (son 2 ana sürüm) | VS-05 | **Evet:** ekibin elindeki gerçek cihazlar |
| AK-06 | Grup liderinin platformu | MVP'de grup lideri yalnızca Android kullanır (grubunu görür, grup bilgisini ve toplanma noktasını düzenler). Web paneli ADM, KOR, TEK ve UYM içindir | VS-06 | Hayır |
| AK-07 | Takvim | 14 haftalık ders dönemi varsayıldı; plan hafta numaralarıyla yapıldı, tarih kullanılmadı (`mvp-scope.md` §10). Dönem 12 veya 16 hafta çıkarsa aynı yerde uyarlama kuralı var | VS-07 | **Evet:** dönemin gerçek hafta sayısı ve teslim haftaları |
| AK-08 | Ders kapsamı | Ders dönemi kapsamı **yalnızca MVP**'dir (Çekirdek; zaman kalırsa Genişletilmiş). R2 ve sonrası yalnızca mimari hazırlık ve dokümantasyon olarak teslim edilir | VS-08 | Hayır |
| AK-09 | Teknoloji yığını | Java 21 + Spring Boot (Spring Modulith), PostgreSQL, Kotlin + Jetpack Compose (Android), TypeScript + React (Web), JUnit/Testcontainers/Playwright/k6 (test). Ayrıntı ve gerekçe ADR-008 | VS-09 | **Evet:** ekibin Java/Kotlin ve TypeScript bilgi düzeyi. Uymazsa ADR-008'deki B planı uygulanır |
| AK-10 | Gerçek veri halinde KVKK rolleri | Prototipte uygulanmaz (K-04). Gerçek veriye geçiş kararı alınırsa kaynak belge §7 kontrol listesiyle ayrı bir analiz aşaması zorunludur. Prototipteki aydınlatma metni kurgusal örnek metindir | VS-10 | Hayır (gerçek kullanıma kadar) |
| AK-11 | Demo ortamı | Docker Compose ile tek makine veya tek sanal sunucu; ücretsiz Firebase projesi; geliştirmede yerel ortam | VS-11 | **Evet:** demonun çalışacağı makine/sunucu |
| AK-12 | Android dağıtımı | Google Play'de yayın yok. İmzalı APK ve/veya Firebase App Distribution ile test dağıtımı | VS-12 | Hayır |
| AK-13 | iOS değerlendirmesi | MVP tesliminden sonra, ders kapsamı dışında. Ölçütler: Android'deki alarm ve konum bulgularının iOS'ta karşılığı, Apple geliştirici hesabı, Critical Alerts onayı | VS-13 | Hayır |

### 3.1 Gerçek bilgi gerektiren varsayımlar (doğrulama listesi)

| VS | Doğrulama | Ne zaman | Uymazsa |
|---|---|---|---|
| VS-05 | Ekipteki gerçek Android cihazlar listelenir | Hafta 1 | Emülatör ile eksik matris riski kabul edilir; RP-04 |
| VS-07 | Dönemin hafta sayısı ve teslim haftaları | Hafta 1 | `mvp-scope.md` §10.3 uyarlama kuralı |
| VS-09 | Ekibin dil ve çatı bilgisi | Hafta 1 | ADR-008 B planı; mimari değişmez |
| VS-11 | Demo makinesi/sunucusu ve Firebase hesabı | Hafta 2 | Yerel makinede demo |

### 3.2 Karar ayrıntılarında kullanılan diğer varsayımlar

Bu varsayımlar, önceki önerilerde değeri olmayan noktaları tamamlar. Hiçbiri gerçek bilgi gerektirmez; değişiklik ekip kararıyla yapılabilir.

| VS | Varsayım | Kullanıldığı karar |
|---|---|---|
| VS-14 | Önerisi olmayan sayısal parametrelerin varsayılanları (§5'te "VS-14" kaynaklı satırlar) | K-06, K-12, §5 |
| VS-15 | Profil alanları: ad-soyad, görünen ad, e-posta, telefon (isteğe bağlı) | K-10 |
| VS-16 | Operasyon grubu operasyona bağlıdır ve onunla arşivlenir; çoklu üyelik serbesttir; toplanma noktası gruba bağlıdır | K-11 |
| VS-17 | Görev durumunu yalnızca KOR ilerletir; gönüllü kabulden sonra görevi bırakamaz | K-12 |
| VS-18 | Konum, paylaşım açıkken kalıcı bildirimli Android ön plan servisiyle 60 sn'de bir alınır. Ayrı bir arka plan konum izni gerekmez (Android'in "kullanım sırasında" izni ön plan servisi boyunca geçerli kalır). Hafta 2 teknik keşfinde doğrulanır | K-14, ADR-011 |
| VS-19 | "Bildirimler aktif operasyonla ilişkili" kuralı, acil alarm ve operasyon bildirimleri içindir; ADM'nin kurum geneli duyurusu istisnadır | K-17, CON-20 |
| VS-20 | FCM, OSM tabanlı harita ve Docker Compose demo ortamı kullanılır | K-18 |
| VS-21 | Saat dilimi Europe/Istanbul; tüm zaman damgaları sunucuda UTC saklanır | K-21 |
| VS-22 | Prototipte denetim kayıtları dönem sonuna kadar saklanır | K-26 |
| VS-23 | Topluluk grubu, operasyona bağlı olmadığı için alarm hedefi olamaz | K-31 |

---

## 4. Çelişkiler

| ID | Çelişki | Çözüm | Durum |
|---|---|---|---|
| CON-01 – CON-11 | `requirements-review.md` §3 ve §9.1 | Önerilen çözümler | Onaylandı / Kesinleşti |
| CON-12 | Akademik prototip ile kuruma bağlı gereksinimler | VS-01: ekip karar verir, danışman değerlendirir; kurgusal senaryo kurumu | Çözüldü |
| CON-13 | 5 operasyon ile "tek aktif operasyon" ifadesi | VS-03: 5 operasyon eşzamanlı aktif olabilir; MVP hedefi buna göre yazıldı | Çözüldü |
| CON-14 | Bir dönem ve 4 kişi ile MVP büyüklüğü | Üç halka (Çekirdek / Genişletilmiş / Sonraki sürüm) ve haftalık plan, kapsam kapılarıyla (`mvp-scope.md` §9–10) | Çözüldü |
| CON-15 | K-04'ün Q-23'ü tam karşılamaması | Aydınlatma (US-02) ve hesap kapatma (US-30) Genişletilmiş halkaya alındı. Asıl KVKK konusu VS-10 | Çözüldü |
| CON-16 | Android + Web ile iOS'a özgü ifadeler | İfadeler "iOS eklenirse" olarak işaretlendi | Çözüldü |
| CON-17 | Yetkinlik alanları ile yatay işler | Ortak sorumluluk ve haftalık planda ayrılan zaman (`mvp-scope.md` §9.2, §10) | Çözüldü |
| CON-18 | **K-12 (MVP'de kapasite takibi) ile önceki öneri** (kontenjan R2'de) | Proje sahibinin açık kararı geçerli: kapasite takibi Çekirdek MVP'ye alındı; R2 listesinden çıkarılıp gerekçesi yazıldı | Çözüldü |
| CON-19 | **K-19 (MFA yetkili hesaplarda) ile önceki halka önerisi** (MFA Genişletilmiş'te) | Açık güvenlik kararı boş zamana bırakılmadı: MFA (yalnızca web paneli, TOTP) **Çekirdek** MVP'ye alındı | Çözüldü |
| CON-20 | **K-17 (bildirimler aktif operasyonla ilişkili) ile FR-06** (ADM'nin kurum geneli duyurusu) | VS-19: kural acil alarm ve operasyon bildirimleri içindir; kurum geneli duyuru tek istisnadır | Çözüldü (varsayım) |
| CON-21 | **K-14 (kullanıcı onayıyla konum) ile KS-03** ("kurum onaylı kuralla etkinleştirme") | Kural tabanlı etkinleştirme R2'de kalır, ancak o sürümde de kullanıcı onayı şarttır | Çözüldü |

---

## 5. Onaylanan parametre değerleri

"Kaynak" sütununda VS-14 yazan satırlar, önceki dokümanlarda değeri olmayan ve varsayımla atanan varsayılanlardır. Tüm parametreler yapılandırılabilir olmalıdır.

| Parametre | Değer | Kaynak |
|---|---|---|
| Konum "güncel değil" eşiği | 2 dk | Öneri → onaylandı (FR-12) |
| Konum "son bilinen" eşiği | 15 dk | Öneri → onaylandı |
| Konum örnekleme aralığı (paylaşım açıkken) | 60 sn | VS-18 |
| Cihaz ve sunucu saati fark eşiği | 2 dk | VS-14 |
| Operasyon kapanınca konumun durma süresi | 1 dk | Öneri → onaylandı (AC-08.3) |
| Alarm hatırlatma aralığı / azami sayısı | 2 dk / 5 | Öneri → onaylandı (FR-16) |
| Alarm yanıtının panelde görünmesi | ≤ 5 sn | Öneri → onaylandı |
| Görev yanıtının panelde görünmesi | ≤ 10 sn | Öneri → onaylandı |
| API yanıt süresi (p95, normal yük) | ≤ 1 sn | Öneri → onaylandı (NFR-08) |
| Alarm kaydından FCM'e iletim (p95) | ≤ 5 sn | Öneri → onaylandı |
| Alarm yazı boyutu / yanıt düğmesi | ≥ 20 sp / ≥ 48×48 dp | VS-14 / öneri → onaylandı |
| TLS | ≥ 1.2 | Öneri → onaylandı |
| Davet geçerliliği | 72 saat | VS-14 |
| Başarısız giriş kilidi | 5 deneme → 15 dk | VS-14 |
| Parola sıfırlama bağlantısı | 30 dk, tek kullanımlık | VS-14 |
| Ek onay gerektiren duyuru alıcı sayısı | > 50 | VS-14 |
| Görev varsayılan yanıt süresi | 60 dk | VS-14 |
| Çevrimdışı göstergesinin görünmesi | ≤ 10 sn | Öneri → onaylandı |
| Push geçici hata yeniden denemesi | 3 deneme, üstel bekleme | VS-14 |
| `CRITICAL` kuyruk bekleme uyarısı | > 30 sn | VS-14 |
| Kapatılmamış operasyon hatırlatması | Planlanan bitişten 2 saat sonra KOR'a | VS-14 |
| RPO / RTO / yedek | 24 saat / 4 saat / günlük | VS-04 |
| Kullanıcı ve grup üst sınırları | 500 kayıtlı, 150 eşzamanlı, grup başına 50, alarm başına 500 alıcı | K-02 + VS-02 |

---

## 6. Değişiklik geçmişi

| Tarih | Değişiklik |
|---|---|
| 14.11.2026 | Dosya oluşturuldu. K-01 – K-05 kaydedildi. 28 soru "Öneri – Onay bekliyor" olarak listelendi. AK-01 – AK-13 ve CON-12 – CON-17 eklendi. |
| 14.11.2026 | Proje sahibi tüm önerileri kabul etti. K-06 – K-33 eklendi (toplam 33 karar). AK-01 – AK-13, VS-01 – VS-13 ile çözüldü. VS-14 – VS-23 ve parametre tablosu (§5) eklendi. CON-12 – CON-17 çözüldü. CON-18 – CON-21 tespit edilip çözüldü. ADR-001 – ADR-012 kabul edildi. |
| 14.11.2026 | Tasarım kararları TD-01 – TD-22 kabul edildi; DR-01 ve DR-12 kesinleştirildi; ADR-013 eklendi; ALERT–NOTIFICATION kardinalitesi düzeltildi. Gereksinim kararlarında değişiklik yok. |
