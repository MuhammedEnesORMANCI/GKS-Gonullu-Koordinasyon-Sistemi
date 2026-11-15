# Gereksinim İnceleme Raporu

| Alan | Bilgi |
|---|---|
| Kaynak belge | `Gonullu_Koordinasyon_Sistemi_Gereksinim_Analizi.docx` (Sürüm 0.1, 27.10.2026, "Paydaş görüşmesi öncesi taslak") |
| İnceleme tarihi | 14.11.2026 |
| İnceleyen rol | Kıdemli iş analisti / yazılım mimarı |
| Durum | **Kesinleşti.** 33 sorunun tamamı karara bağlandı (K-01 – K-33); açık konular varsayımlarla çözüldü (VS-01 – VS-23). Bu rapordaki çelişki, belirsizlik ve eksik çözüm önerileri onaylandı. Karar durumlarının tek kaynağı: [`decision-log.md`](decision-log.md) |

## 0. Okuma kılavuzu

| Etiket | Anlamı |
|---|---|
| `CON-xx` | Çelişki: belgenin iki yeri birbiriyle uyuşmuyor |
| `AMB-xx` | Belirsizlik: gereksinim var ama birden fazla şekilde yorumlanabilir veya test edilemez |
| `GAP-xx` | Eksik: gerekli olduğu hâlde belgede hiç tanımlanmamış konu |
| `A-xx` | Analistin ilk çalışma varsayımı (tümü onaylandı ve K kararlarına dönüştü, §7). Yeni varsayımlar `VS-xx` (`decision-log.md` §3) |
| `Q-xx` | Paydaşa veya proje sahibine sorulacak soru (bkz. §8) |
| **Öneri** | Analistin önerisi. Karar paydaşa aittir |
| `⟨TBD⟩` | Değeri belirlenmemiş parametre. *Bu rapordaki tüm TBD'ler onaylanan değerlerle değiştirildi (`decision-log.md` §5).* |

---

## 1. Genel değerlendirme

**Güçlü yanlar**
- Belge, platform sınırlarını açıkça kabul ediyor (§6.1: sessiz mod/DND aşılamayabilir). "Garanti edilemeyen davranış ürün vaadi olarak yazılmamalı" ilkesi doğru.
- "Gönderildi", "teslim edildi", "görüldü" ve "yanıtlandı" ayrımı yapılmış (FR-18, BR-06). Bu ayrım kritik sistemler için çok değerli.
- Gizlilik varsayılanları (NFR-15), en az yetki, operasyonla sınırlı konum ve sunucu tarafı yetki kontrolü (NFR-02) baştan düşünülmüş.
- Kapsam dışı konular açıkça listelenmiş (§2.2).

**Temel zayıflıklar**
1. ~~Projenin niteliği belirsiz (Q-01).~~ **Kesinleşti (K-01):** akademik prototip; yalnızca sentetik veri (K-04); Android + Web (K-03). Karar mercii VS-01 ile çözüldü: proje ekibi karar verir, akademik danışman kapsam ve teslimatı değerlendirir (CON-12).
2. **Fonksiyonel olmayan gereksinimlerin neredeyse hiçbirinde ölçülebilir değer yok.** Performans, ölçek, erişilebilirlik, yedekleme ve uyumluluk hedefleri sayısal değil. Mevcut hâlleriyle test edilemezler.
3. **Öncelik bağımlılıkları kırık.** Zorunlu (Z) gereksinimler, "önemli" (Ö) olarak işaretlenmiş FR-19 Operasyon'a bağlı (bkz. CON-06).
4. **Durum modelleri yetersiz.** Grup veya çok kişiye giden görev ve alarmlarda kişi bazlı durum ile toplam durum ayrılmamış (AMB-06, CON-09).
5. **Rol adları ve yetkileri bölümler arasında tutarsız** (CON-01, CON-02).
6. Gereksinimlerin bir kısmı "değerlendirilmelidir" diye yazılmış. Bu bir tasarım notu, bağlayıcı bir gereksinim değil (NFR-03, NFR-06, FR-23).

---

## 2. Gereksinim envanteri ve değerlendirme

### 2.1 Fonksiyonel gereksinimler

Netlik: **N** = net, **K** = kısmen net, **B** = belirsiz, **Ç** = çelişkili.
Test edilebilirlik: **E** = evet, **K** = kısmen, **H** = hayır (ölçüt eksik).

| ID | Konu | Belge önceliği | Netlik | Test | Bulgular |
|---|---|---|---|---|---|
| FR-01 | Kayıt ve kimlik doğrulama | Z | B | K | AMB-01, GAP-01 |
| FR-02 | Profil yönetimi | Z | B | H | AMB-02 |
| FR-03 | Rol bazlı erişim | Z | K | K | CON-01, bkz. `user-roles.md` |
| FR-04 | Grup yönetimi | Z | K | E | CON-02, AMB-03 |
| FR-05 | Grup sohbeti | Ö | B | K | CON-03, AMB-04 |
| FR-06 | Duyuru / toplu mesaj | Z | K | E | CON-04 |
| FR-07 | Görev oluşturma ve atama | Z | K | E | AMB-05, GAP-05 |
| FR-08 | Görev yanıtı ve durumu | Z | Ç | K | CON-05, AMB-06, AMB-07 |
| FR-09 | Toplanma noktası | Z | N | E | GAP-06 |
| FR-10 | Konum paylaşım kontrolü | Z | B | K | AMB-08, CON-06 |
| FR-11 | Canlı konum görünümü | Z | K | E | AMB-09, AMB-10 |
| FR-12 | Konumun güncelliği | Z | B | H | AMB-11 |
| FR-13 | Acil alarm gönderimi | Z | K | E | AMB-12, AMB-13, GAP-07 |
| FR-14 | Ayırt edilebilir alarm arayüzü | Z | B | H | AMB-14 |
| FR-15 | Alarmı yanıtlama | Z | K | E | AMB-15, CON-08 |
| FR-16 | Yanıtsız alarm takibi | Z | Ç | K | CON-07, AMB-16 |
| FR-17 | Eskalasyon kuralı | Ö | B | H | CON-03, AMB-17 |
| FR-18 | Bildirim durumu | Z | K | K | CON-09, RT-04 (risks.md) |
| FR-19 | Operasyon durumu | Ö | K | E | CON-06 |
| FR-20 | Denetim kayıtları | Z | K | E | GAP-08, GAP-09 |
| FR-21 | Arama ve filtreleme | Ö | K | K | Ölçek belli olmadan kapsamı belirsiz |
| FR-22 | Hesap güvenliği yönetimi | Z | N | E | GAP-02 |
| FR-23 | Yardım çağrısı / SOS | S | B | H | CON-08, "değerlendirilmelidir" ifadesi bağlayıcı değil |
| FR-24 | Çevrimdışı durum bildirimi | Ö | B | K | AMB-18, NFR-10 ile örtüşüyor |

### 2.2 Fonksiyonel olmayan gereksinimler

| ID | Nitelik | Belge önceliği | Sorun | Test edilebilir hâle getirmek için gereken |
|---|---|---|---|---|
| NFR-01 | Güvenli aktarım | Z | Protokol sürümü belirtilmemiş | Asgari TLS sürümü (**karar:** TLS ≥ 1.2, §6) ve HSTS |
| NFR-02 | Sunucu tarafı yetkilendirme | Z | Net | Yetki matrisinden türetilen negatif testler |
| NFR-03 | Yönetici hesaplarında MFA | Ö | "desteklenmeli veya değerlendirilmeli", yani bağlayıcı değil | **Çözüldü:** ADM ve KOR için TOTP zorunlu (K-19), §6 |
| NFR-04 | Veri şifreleme | Z | "Risk temelli" ifadesi ölçülemez | Hangi verinin (DB, yedek, dışa aktarım, konum) hangi yöntemle şifreleneceği ve anahtarların nerede tutulacağı |
| NFR-05 | Denetlenebilirlik | Z | "Değiştirilmeye karşı korunan" ifadesinin yöntemi yok | Append-only saklama ve/veya hash zinciri; kimlerin okuyabileceği (GAP-08) |
| NFR-06 | Bildirim güvenilirliği | Z | "Değerlendirilmelidir" ifadesi bağlayıcı değil | Yeniden deneme sayısı ve aralığı, hata görünürlüğü, idempotency kuralı |
| NFR-07 | Alarm arayüzü | Z | FR-14 ile aynı; "belirgin" ölçülemez | Bkz. AMB-14 |
| NFR-08 | Performans | Ö | Hiçbir sayı yok | **Çözüldü:** §6 (VS-04) |
| NFR-09 | Erişilebilirlik | Ö | Standart belirtilmemiş | **Çözüldü:** WCAG 2.1 AA + platform rehberleri, §6 |
| NFR-10 | Çevrimdışı / kararsız ağ | Ö | FR-24 ile örtüşüyor | Tek gereksinimde birleştirilmeli |
| NFR-11 | Yedekleme ve kurtarma | Ö | RPO ve RTO yok | **Çözüldü:** §6 (VS-04) |
| NFR-12 | Ölçeklenebilirlik | Ö | **Çözüldü:** 500 kullanıcı, 5 operasyon (K-02) | Eşzamanlılık ve alarm alıcı sayısı VS-02, VS-03; §6 |
| NFR-13 | Uyumluluk | Ö | **Çözüldü:** Android + Web (K-03) | minSdk 26, cihaz matrisi ve tarayıcılar VS-05; §6. iOS sonraki sürümde değerlendirilecek |
| NFR-14 | Bakım yapılabilirlik | Ö | "Kritik senaryolar" tanımlı değil | Kritik senaryo listesi (**karar:** tüm `user-stories.md` MVP kabul kriterleri) |
| NFR-15 | Gizlilik varsayılanları | Z | Kısmen net | Profil alanı bazında görünürlük tablosu (bkz. `user-roles.md` §5) |

**Belgede hiç bulunmayan NFR alanları:** GAP-10 ile GAP-16 arası (§5).

### 2.3 İş kuralları

| ID | Değerlendirme |
|---|---|
| BR-01 | Net. Yetki testlerinin temelini oluşturuyor. |
| BR-02 | Net. Ancak "hedef kişi/grup" için grup görevinde kişi bazlı kayıt gerekiyor (AMB-06). |
| BR-03 | Net. |
| BR-04 | **"Önemli gönderimler" tanımsız.** Hangi gönderimlerde onay adımı zorunlu? **Karar:** tüm acil alarmlarda ve alıcı sayısı 50'yi aşan duyurularda (VS-14). |
| BR-05 | **FR-16 ile gerilimli** (CON-07). Yanıt geldikten sonra hangi durumda tekrar bildirim süreceği tanımsız. |
| BR-06 | Net ve değerli. |
| BR-07 | "Eski" sayılma eşiği yok (AMB-11). |
| BR-08 | Net. "Kritik veri dışa aktarma" özelliği ise FR'de tanımlı değil (GAP-09). |
| BR-09 | "…veya kurumca onaylanan farklı bir kural" ifadesi açık uçlu bir istisna. MVP'de istisnasız uygulanması önerilir. Operasyon kapatılmayı unutulursa ne olacağı da tanımsız (RO-10). |
| BR-10 | Net, ancak politikayı yapılandıracak ekran ve rol tanımlı değil (GAP-17). |
| BR-11 | Net. |
| BR-12 | Net ve önemli. "Yardım gerekiyor" yanıtı ve serbest metin alanları dolaylı olarak sağlık verisi toplayabilir (RC-05). |

### 2.4 Kullanım senaryoları (KS-01 – KS-04)

- **KS-01:** Alternatif akıştaki "belirlenen süre" tanımsız. Ayrıca "yanıt bekliyor" adında bir görev durumu kullanılıyor, oysa bu durum ne FR-08'de ne de §5.1 durum modelinde var (CON-05).
- **KS-02:** İyi yazılmış. Sınır maddesi doğru. Eskalasyon süresi ve hedefi tanımsız (AMB-17).
- **KS-03:** Ön koşul ("hukuki işleme şartları doğrulanmalı") yazılımın değil organizasyonun sorumluluğu. Yazılım tarafında karşılığı, kullanıcıya aydınlatma metnini gösterip onay kaydı tutmak (GAP-03).
- **KS-04:** Net.
- **Eksik senaryolar:** gönüllü katılımı (davet → aktivasyon → izinler), operasyon yaşam döngüsü, alarm iptali/düzeltme, kayıp cihaz, hesap kapatma ve veri silme, bağlantı kesintisi. Bunlar `user-stories.md` içinde ayrıca ele alındı.

---

## 3. Çelişkiler (CON)

| ID | Çelişki | Kaynaklar | Çözüm (**onaylandı**) |
|---|---|---|---|
| CON-01 | **Rol adları tutarsız.** §3'te "Kurum yöneticisi" ve "Teknik sistem yöneticisi" var. FR-03'te ise "Sistem yöneticisi" geçiyor ve hangisi olduğu belli değil. §8.2'de "yönetici/grup lideri" deniyor. | §3, FR-03, §8.2 | Kanonik rol listesi `user-roles.md` içinde önerildi. Q-03 |
| CON-02 | **Grup liderinin grup oluşturup oluşturamayacağı belirsiz.** §3: grup lideri yalnızca yönettiği gruplarla sınırlı. KS-04 ve §8.2: "koordinatör/grup lideri" veya "yönetici/grup lideri" grup oluşturur. Var olmayan bir grubu yönetiyor olamaz. | §3, KS-04, §8.2, FR-04 | **Karar:** grup oluşturma koordinatör ve kurum yöneticisine ait olsun. Grup lideri yalnızca kendi grubunun bilgisini ve toplanma noktasını düzenlesin; üye ekleme/çıkarma yetkisi Q-04 ile netleşsin. |
| CON-03 | **İlk sürüm kapsamı ile öncelikler uyuşmuyor.** §2.1 "grup içi sohbet" ve "escalation kuralı"nı ilk sürüm kapsamına alıyor. FR-05 (sohbet) ve FR-17 (eskalasyon) ise yalnızca "Ö". | §2.1, FR-05, FR-17 | MVP önerisi `mvp-scope.md` içinde: sohbet R2'de; eskalasyon MVP'de yalnızca "yanıtsızlar listesi + manuel tekrar" olarak, otomatik kural R2'de. Q-20 |
| CON-04 | **"Tüm gönüllülere duyuru" ile koordinatörün sınırlı kapsamı çatışıyor.** FR-06 tüm gönüllülere gönderime izin veriyor, §3 ise koordinatörü "sorumlu olduğu operasyon ve gruplar" ile sınırlıyor. | FR-06, §3 | **Karar:** kurum geneline duyuru yalnızca kurum yöneticisi tarafından gönderilsin; koordinatör kendi kapsamına göndersin. Q-05 |
| CON-05 | **Görev durum listeleri farklı.** FR-08: atanmış, kabul edildi, devam ediyor, tamamlandı, iptal (Reddedildi ve Taslak yok). §5.1: Taslak, Atandı, Kabul/Reddedildi, Devam ediyor, Tamamlandı, İptal. KS-01: "yanıt bekliyor". | FR-08, §5.1, KS-01 | Tek bir durum modeli tanımlanmalı ve görev düzeyi ile atanan kişi düzeyi ayrılmalı (AMB-06). Öneri: atanan kişi düzeyinde `Yanıt bekliyor → Kabul / Ret`; görev düzeyinde `Taslak → Yayında → Devam ediyor → Tamamlandı / İptal`. |
| CON-06 | **Zorunlu gereksinimler "önemli" bir gereksinime bağlı.** FR-10, FR-11 ve BR-09 (Z) "aktif operasyon" kavramına dayanıyor, ama operasyonu tanımlayan FR-19 "Ö". FR-19 olmadan konum özelliği tanımlanamaz. | FR-10, FR-11, FR-19, BR-09, KS-03 | FR-19'un en az "aç / aktif / kapat" kısmı MVP'ye (Z) yükseltilmeli. |
| CON-07 | **"Yanıt verene kadar" ifadesi politika sınırı ve OS kısıtıyla çelişiyor.** FR-16 sınırsız tekrar ima ediyor. BR-10 tekrarın politikayla sınırlandırılmasını istiyor, §6.1 ise garanti verilemeyeceğini söylüyor. BR-05 yanıt geldikten sonra bile tekrarın sürebileceğini söylüyor. | FR-16, BR-05, BR-10, §6.1 | **Karar:** "Yanıt gelene kadar **ya da** politikadaki azami tekrar sayısı/süresi dolana kadar." Azami değerler 2 dk × 5 (K-13) |
| CON-08 | **Yardım isteme özelliği hem kapsam içi hem kapsam dışı.** §3'te gönüllünün sorumluluğu "gerektiğinde yardım isteme". FR-15'te "Yardım gerekiyor" yanıtı var. FR-23 (SOS) ise "S", yani sonraya bırakılmış. | §3, FR-15, FR-23 | **Karar:** MVP'de yardım isteği yalnızca bir alarma yanıt olarak mümkün olsun (FR-15). Gönüllünün kendi başlattığı SOS R3'te. Q-21 |
| CON-09 | **Bildirim durum modeli FR-18 ile uyuşmuyor.** FR-18 gönderildi, teslim, görüldü ve yanıt durumlarını ayrı istiyor. §5.1 alarm modelinde "Teslim edildi" ve "Görüldü" yok. Ayrıca alarmın kendi durumu ile her alıcının durumu karıştırılmış. | FR-18, §5.1 | **Karar:** iki ayrı model: **Alarm** (`Taslak → Gönderildi → Açık → Kapatıldı / İptal`) ve **Alıcı** (`Kuyrukta → Gönderildi → [Teslim edildi]* → Görüldü → Yanıtlandı / Yanıtsız → Eskale edildi`). *Teslim sinyali platformdan alınabildiği durumlarda. |

---

> **Ek çelişkiler (14.11.2026):** CON-12 – CON-21 kesinleşen kararlarla ortaya çıktı ve çözüldü. Ayrıntı: [`decision-log.md`](decision-log.md) §4.

## 4. Belirsizlikler (AMB)

> **Durum:** Tüm belirsizlikler bağlı oldukları soruların kararlarıyla çözüldü (Q → K eşlemesi: `decision-log.md` §1.2; sayısal değerler §5).

| ID | Konu | Belirsizlik | Bağlı soru |
|---|---|---|---|
| AMB-01 | Kayıt yöntemi | Açık kayıt mı, kurum daveti mi, ikisi birden mi? Açık kayıtta onay akışı ne olacak? | Q-02 |
| AMB-02 | Profil alanları | Zorunlu ve isteğe bağlı alanlar tanımlı değil. Kurumun bunları sistemden yapılandırması mı gerekiyor, yoksa sabit mi? | Q-06 |
| AMB-03 | Çoklu üyelik | Bir gönüllü aynı anda birden fazla grupta ya da operasyonda olabilir mi? Grup bir operasyona mı ait, yoksa kalıcı mı? | Q-07 |
| AMB-04 | Sohbet erişim politikası | "Üyelik sona erdiğinde erişim politikası uygulanmalı" deniyor, ama politika tanımlı değil (geçmişi görebilir mi?). | Q-20 |
| AMB-05 | Grup görevi | Göreve kaç kişi gerekli (kontenjan)? Kontenjan dolunca diğer kabuller ne olacak? İlk kabul eden mi alır? | Q-08 |
| AMB-06 | Görev durumunun düzeyi | Görev bir gruba atanınca kabul/ret kişi bazlı mı tutulacak? Görevin toplam durumu nasıl hesaplanacak? | Q-08 |
| AMB-07 | Durum geçişleri | "Devam ediyor" ve "Tamamlandı" durumlarını kim işaretliyor: gönüllü, grup lideri, koordinatör? Kabul edilmiş bir görev sonradan bırakılabilir mi? | Q-08 |
| AMB-08 | Konumu kim başlatır | Gönüllü mü başlatır, yoksa operasyon aktif olunca kural gereği otomatik mi açılır? Konum ne sıklıkla alınır? Uygulama arka plandayken de alınır mı? | Q-10 |
| AMB-09 | Konumu kim görür | Grup lideri konum görebilir mi (KS-03 aktörlerinde var, §3'te açık değil)? Gönüllüler birbirinin konumunu görebilir mi? | Q-11 |
| AMB-10 | Konum geçmişi | FR-11 yalnızca son konumu istiyor. KS-03 ise "konumu kaydeder" diyor. İz/geçmiş tutulacak mı? | Q-12 |
| AMB-11 | Güncellik eşiği | Konum kaç saniye/dakika sonra "güncel değil" sayılacak? | Q-10 |
| AMB-12 | Alarmı kim gönderir | Yalnızca koordinatör mü? Grup lideri kendi grubuna alarm gönderebilir mi? | Q-05 |
| AMB-13 | "Olay" kavramı | FR-13 "operasyon/olay referansı" diyor. "Olay" terimler listesinde yok. Operasyondan farklı bir nesne mi? Aktif operasyon dışında alarm gönderilebilir mi? | Q-14 |
| AMB-14 | "Belirgin alarm" | Hangi özelliklerle ayırt edilecek: tam ekran, ses, titreşim, renk, kilit ekranı? Belgede ölçüt yok. | Q-09 |
| AMB-15 | Yanıt seçenekleri | Seçenekler sabit mi, yoksa kurum yapılandırabilir mi? Her alarm için ayrı seçenek tanımlanabilir mi? | Q-09 |
| AMB-16 | Hatırlatma davranışı | Hatırlatma aralığı, sayısı, sesli mi sessiz mi, push mu yoksa yalnızca uygulama içi mi? | Q-09 |
| AMB-17 | Eskalasyon | Kaç dakika sonra ve kime (rol, kişi, kanal)? Eskalasyon alıcısı da yanıt vermezse ne olacak? | Q-09 |
| AMB-18 | Çevrimdışı davranış | Çevrimdışıyken neler yapılabilir (alarm yanıtını kuyruğa alma, görev kabulü)? Senkronizasyonda çakışma olursa hangi veri geçerli olacak? | Q-22 |

---

## 5. Eksikler (GAP)

> **Durum:** MVP önerileri onaylandı. Halkalar (Çekirdek / Genişletilmiş / Sonraki sürüm) `mvp-scope.md` §3'te.

### 5.1 Fonksiyonel eksikler

| ID | Eksik | Neden önemli | MVP önerisi |
|---|---|---|---|
| GAP-01 | Parola sıfırlama, hesap doğrulama yöntemi (SMS/e-posta), parola politikası | Hesap kurtarma olmadan sistem işletilemez | MVP |
| GAP-02 | Hesap kapatma ve veri silme talebi akışı | §6.2 test tablosunda var, FR'de yok. KVKK açısından gerekli | MVP (asgari düzey) |
| GAP-03 | Aydınlatma metninin gösterilmesi, onay/rıza kaydı ve metin sürümlemesi | KVKK §7.2. Konum özelliğinin ön koşulu | MVP |
| GAP-04 | Gönüllünün gruba katılma yöntemi (§3'te "gruplara katılma" var: kendisi mi katılır, atanır mı?) | Yetki modelini etkiliyor | Q-07 |
| GAP-05 | Atanmış görevin düzenlenmesi ve atanan kişilere değişiklik bildirimi | Saha gerçekliğinde görevler değişir | MVP |
| GAP-06 | Toplanma noktasının bağlı olduğu nesne (grup, operasyon, görev?) | Veri modelini etkiliyor | Q-07 |
| GAP-07 | Acil alarmın iptali veya düzeltme mesajı | Risk tablosunda önlem olarak geçiyor, FR'de yok. Yanlış alarmın tek çaresi bu | MVP |
| GAP-08 | Denetim kayıtlarını kimin görüntüleyeceği ve saklama süresi | NFR-05 ve §8.2 "normal kullanıcılardan korunmalı" diyor ama okuyabilecek rol tanımsız | MVP |
| GAP-09 | Veri dışa aktarma ve raporlama | FR-20, BR-08 ve §3.1'de ("rapor ve denetim") anılıyor, ama gereksinim olarak yok | R2 (MVP'de dışa aktarma yok) |
| GAP-17 | Bildirim/eskalasyon politikalarının yapılandırma ekranı | BR-10 "yönetici tarafından belirlenen politika" diyor | MVP: sabit yapılandırma; R2: arayüz |
| GAP-18 | Kullanıcının bildirim izinlerini kontrol eden ve kapalıysa uyaran "hazırlık kontrolü" ekranı | Alarm güvenilirliğinin en etkili önlemi | MVP |
| GAP-19 | Grup liderinin kullanacağı platform | §3.1'e göre koordinatörler web, gönüllüler mobil kullanıyor. Sahadaki grup liderinin mobilde (Android, K-03) hangi yönetim işlevlerini yapacağı belli değil | AK-06 |
| GAP-20 | Gönüllü uygunluk/müsaitlik ve yetkinlik etiketleri | §7.1 veri kategorisinde geçiyor, FR'de yok | R2 |

### 5.2 Fonksiyonel olmayan eksikler

| ID | Eksik |
|---|---|
| GAP-10 | **Erişilebilirlik/çalışma süresi (uptime) hedefi yok.** Acil durum sistemi için en temel NFR bu. |
| GAP-11 | İzleme, alarm ve gözlemlenebilirlik (sunucu ya da push servisi çökerse kim, nasıl haberdar olacak?) |
| GAP-12 | Oturum süresi ve zaman aşımı, hız sınırlama (rate limiting), kaba kuvvet saldırısı koruması |
| GAP-13 | Dil/yerelleştirme (yalnızca Türkçe mi?), saat dilimi ve tarih biçimi |
| GAP-14 | Verinin barındırılacağı yer ve yurtdışına aktarım kısıtı (Q-15) |
| GAP-15 | Push token yaşam döngüsü (cihaz değişimi, çoklu cihaz) |
| GAP-16 | Sunucu ve cihaz saati arasındaki fark (güncellik hesabını doğrudan etkiler, RT-10) |

---

## 6. Test edilebilir ifadeler (onaylandı)

Aşağıdaki değerler **onaylanmıştır** (önceki öneriler) veya VS-14 varsayılanlarıdır. Tam liste: `decision-log.md` §5.

| ID | Test edilebilir ifade |
|---|---|
| FR-12 / BR-07 | Son konum zaman damgası 2 dk'dan eskiyse konum "güncel değil" olarak etiketlenir ve görsel olarak canlı konumdan ayrılır. 15 dk'dan eskiyse haritada canlı işaretçi gösterilmez, yalnızca "son bilinen" gösterilir. |
| FR-14 / NFR-07 | Acil alarm: (a) kendine özgü bildirim kanalı ve sesi, (b) uygulama açıkken tam ekran alarm görünümü; arka planda veya kilit ekranında yüksek öncelikli bildirim, dokununca tam ekran (DR-01), (c) en az 20 sp yazı boyutu, (d) yanıt düğmeleri en az 48×48 dp, (e) normal mesaj listesinden ayrı bir alanda gösterim. Kilit ekranında kendiliğinden tam ekran ve DND/sessiz modu aşma MVP'de yok (US-42 R3). |
| FR-16 | Yanıt verilmeyen alarm için uygulama içi/push hatırlatma her 2 dk'da bir, en fazla 5 kez yapılır. Sonrasında alıcı "Yanıtsız" durumuna geçer (K-13). |
| FR-17 | *(R2, Ertelendi)* Alarm gönderiminden [R2 analizinde belirlenecek süre] sonra yanıtsız alıcılar [R2 analizinde belirlenecek rol]'e listelenerek bildirilir. MVP'de yalnızca manuel yeniden gönderim (K-13). |
| NFR-08 | Normal yükte API isteklerinin %95'i 1 sn altında yanıtlanır. Alarm kaydı ile push servisine iletim arası %95 oranında 5 sn altında kalır. (Push servisinden cihaza ulaşma süresi sistemin kontrolünde değildir; ölçülür ama garanti edilmez.) |
| NFR-09 | Android ve web arayüzleri WCAG 2.1 AA kontrast ve etiket kriterlerini karşılar. Ekran okuyucuyla (Android'de TalkBack) alarm yanıtı verilebilir. *(VoiceOver, iOS eklendiğinde.)* |
| NFR-11 | RPO 24 saat, RTO 4 saat, günlük yedek. Geri yükleme dönem içinde bir kez (Hafta 12) test edilir (VS-04). |
| NFR-12 | 500 kayıtlı kullanıcı ve 5 operasyon (K-02); 150 eşzamanlı oturum, 5 eşzamanlı aktif operasyon ve tek seferde 500 alıcıya alarm (VS-02, VS-03), NFR-08 hedeflerini aşmadan desteklenir. Doğrulama sentetik yük testiyle yapılır (K-04). |
| NFR-13 | Android 8.0+ (minSdk 26) sürümlerinde ve VS-05 cihaz matrisinde (en az 3 gerçek cihaz + emülatör); web için güncel Chrome, Edge ve Firefox'un son 2 ana sürümünde test edilir. iOS bu sürümün kapsamında değildir (K-03). |
| GAP-10 | Prototipte çalışma süresi kabul koşulu değildir; demo süresince çalışırlık beklenir (VS-04). |

---

## 7. Çalışma varsayımları (onaylandı)

Aşağıdaki varsayımların tamamı onaylandı ve karşılık gelen kararlara dönüştü. Yeni varsayımlar (VS-01 – VS-23) `decision-log.md` §3'te.

| ID | Varsayım | Karar |
|---|---|---|
| A-01 | Sistem tek bir kurum için kurulacak (çok kiracılı/multi-tenant değil). Prototipte bu kurum kurgusal senaryo kurumudur. | ✅ Onaylandı (K-07, VS-01) |
| A-02 | ~~MVP gerçek kişisel veriyle değil, sentetik veriyle gösterilecek.~~ **Varsayım değil artık: K-04 ile kesinleşti.** | Q-23 → K-04 |
| A-03 | Arayüz dili yalnızca Türkçe. | ✅ K-21 |
| A-04 | Bir kullanıcının birden fazla rolü olabilir (ör. grup lideri aynı zamanda gönüllüdür ve alarm alır). | ✅ K-07 |
| A-05 | Roller kapsamlı atanır: bir kişi bir operasyonda koordinatör, başka bir grupta yalnızca gönüllü olabilir. | ✅ K-07 |
| A-06 | Alarm kanalı MVP'de yalnızca push + uygulama içi. SMS/arama yok (§2.2 ile uyumlu). | ✅ K-20 |
| A-07 | Konum yalnızca aktif bir operasyonda ve gönüllü bu operasyona dahilse toplanır. | ✅ K-14 |

---

## 8. Sorular ve kararlar

Öncelik: **K** = kritik (MVP tanımını engeller), **Y** = yüksek (tasarımı engeller), **O** = orta (geliştirme sırasında netleşebilir). Belgedeki 16 soru (§9.2) korunarak birleştirildi ve yeni sorular eklendi.

> **Durum (14.11.2026):** 33 sorunun tamamı kesinleşti (✅). Q-01, Q-13, Q-16, Q-23 ve Q-25 proje sahibinin doğrudan kararıdır; diğerleri onaylanan önerilerdir. Karar numaraları satır sonunda; ayrıntı ve varsayımlar: [`decision-log.md`](decision-log.md). Öncelik sütunu yerine karar durumu yazıldı.

| ID | Durum | Soru | Belge §9.2 | Etkilediği yerler |
|---|---|---|---|---|
| Q-01 | ✅ | Proje yalnızca ders prototipi mi, gerçek bir kurumda pilot/canlı kullanım mı hedefleniyor? Veri sorumlusu kim? **→ K-01: akademik prototip.** Kalan: AK-01 | 1 | Her şey |
| Q-02 | ✅ | Gönüllü katılımı: açık kayıt mı, davet mi, mevcut kimlik sistemi mi? Kimlik doğrulama yöntemi (e-posta, SMS OTP)? **→ K-06.** | 2 | FR-01 |
| Q-03 | ✅ | Kanonik rol listesi ve kapsam modeli `user-roles.md`'deki öneriye uygun mu? Bir kişi birden fazla role sahip olabilir mi? **→ K-07.** | 3 | FR-03, CON-01 |
| Q-04 | ✅ | Grup lideri üye ekleyip çıkarabilir mi? Grup oluşturabilir mi? **→ K-08.** | – | CON-02 |
| Q-05 | ✅ | Duyuruyu ve acil alarmı kim, hangi kapsama gönderebilir? Grup lideri alarm gönderebilir mi? Kurum geneline gönderim kimde? **→ K-09.** | 6 | FR-06, FR-13, CON-04 |
| Q-06 | ✅ | Profilde hangi alanlar zorunlu? Alanlar sabit mi, kurum yapılandırabilir mi? **→ K-10.** | – | FR-02 |
| Q-07 | ✅ | Grup kalıcı mı, operasyona bağlı mı? Kullanıcı birden fazla grup/operasyonda olabilir mi? Toplanma noktası neye bağlı? **→ K-11.** | – | FR-04, FR-09 |
| Q-08 | ✅ | Grup görevlerinde kontenjan var mı? Kabul/ret kişi bazlı mı? "Devam ediyor" ve "Tamamlandı"yı kim işaretler? Görev yanıtı için süre sınırı var mı? **→ K-12.** | – | FR-07, FR-08 |
| Q-09 | ✅ | Alarmın "yanıt verene kadar" davranışında tam olarak ne tekrar edecek? Yanıt seçenekleri neler? Kaç kez, hangi aralıkla? Eskalasyon kaç dakika sonra, kime? **→ K-13.** | 7, 8 | FR-14 – FR-17 |
| Q-10 | ✅ | Konumu kim başlatır (gönüllü / kural)? Hangi sıklıkla alınır? Uygulama arka plandayken de alınmalı mı? Hangi süreden sonra "güncel değil" sayılır? **→ K-14.** | 4 | FR-10, FR-12 |
| Q-11 | ✅ | Konumu kimler görebilir? Grup lideri? Gönüllüler birbirini? **→ K-15.** | – | FR-11 |
| Q-12 | ✅ | Konum geçmişi tutulacak mı? Tutulacaksa amacı, süresi, erişimi ne olacak? **→ K-16.** | 5 | FR-11, KVKK |
| Q-13 | ✅ | Beklenen kullanıcı sayısı, eşzamanlı operasyon, grup büyüklüğü, tek seferde alarm alıcısı ve erişilebilirlik (uptime) hedefi nedir? **→ K-02: 500 kullanıcı, 5 operasyon.** Kalan: AK-02, AK-03, AK-04 | 13 | NFR-08, NFR-12, GAP-10 |
| Q-14 | ✅ | "Olay" operasyondan ayrı bir kavram mı? Aktif operasyon dışında alarm gönderilebilir mi? **→ K-17.** | – | FR-13 |
| Q-15 | ✅ | Onaylı harita, push ve bulut sağlayıcısı var mı? Veri Türkiye'de mi tutulmalı? **→ K-18.** | 11 | Mimari, RC-04 |
| Q-16 | ✅ | Hedef cihazlar ve OS sürümleri neler? Android ve iOS ikisi de MVP'de mi? Grup lideri hangi işlemleri mobilde yapacak? **→ K-03: Android + Web; iOS sonra değerlendirilecek.** Kalan: AK-05, AK-06, AK-12, AK-13 | 12 | NFR-13, GAP-19 |
| Q-17 | ✅ | Hangi rollerde MFA zorunlu olacak? **→ K-19.** | – | NFR-03 |
| Q-18 | ✅ | İnternet yoksa yedek kanal (SMS/arama) bekleniyor mu, bütçesi var mı? Sistem çalışmadığında operasyonel yedek prosedür ne? **→ K-20.** | 9 | §2.2, RO-06 |
| Q-19 | ✅ | Arayüz dili yalnızca Türkçe mi? **→ K-21.** | – | GAP-13 |
| Q-20 | ✅ | Grup sohbeti ve eskalasyon MVP'de gösterilmek zorunda mı (§2.1'de var, öncelikleri "Ö")? Bire bir sohbet gerekli mi? Mesajlar ne kadar süre saklanacak? **→ K-22.** | 10, 16 | CON-03 |
| Q-21 | ✅ | Gönüllünün kendi başlattığı yardım çağrısı (SOS) MVP için gerekli mi? **→ K-23.** | – | CON-08 |
| Q-22 | ✅ | Çevrimdışıyken hangi işlemler kuyruğa alınabilir? **→ K-24.** | – | FR-24 |
| Q-23 | ✅ | KVKK aydınlatması, açık rıza gerekip gerekmediği, saklama-imha süreleri ve ihlal yönetiminden kim sorumlu? **→ K-04: ilk aşamada yalnızca sentetik veri.** Asıl KVKK sorusu gerçek veriye geçişe ertelendi: AK-10, CON-15 | 14 | GAP-02, GAP-03, RC-xx |
| Q-24 | ✅ | Zorunlu entegrasyon, rapor, dışa aktarma veya veri aktarımı var mı? **→ K-25.** | 15 | GAP-09 |
| Q-25 | ✅ | Teslim takvimi nedir (ders dönemi bitişi, ara teslimler)? Ekip büyüklüğü ve yetkinlikleri? **→ K-05: ders dönemi içinde; 4 kişi; backend, mobil, web, test.** Kalan: AK-07, AK-08, AK-09 | 16 | MVP boyutu |
| Q-26 | ✅ | Denetim kayıtlarını kim görüntüleyebilecek, kayıtlar ne kadar saklanacak? **→ K-26.** | – | GAP-08 |

---

## 9. Genişleme gereksinimleri (14.11.2026 eki)

Proje sahibi, aşağıdaki özelliklerin **ilk sürümde geliştirilmemesini, ancak mimarinin bunlara hazır olmasını** istedi. Bunlar şu an "gelecek gereksinimi" niteliğinde. Ayrıntılı kabul kriterleri ilgili sürüm planlanırken yazılacak. Mimari karşılıkları `docs/architecture/` altında.

| ID | Özellik | MVP ile ilişkisi | Önerilen sürüm | Mimari referans |
|---|---|---|---|---|
| FX-01 | Kullanıcıların grup oluşturabilmesi | MVP'de yalnızca koordinatör operasyon grubu kurar (FR-04). Kullanıcı grupları ayrı bir tür olarak eklenecek (`COMMUNITY`) | R3 | domain-model §3.1, authorization §4 |
| FX-02 | Gönüllülerin gruplara katılabilmesi | MVP'de yok (GAP-04). Talep/onay ve davet bağlantısı | R2 (talep), R3 (açık katılım) | domain-model §3.3–3.4 |
| FX-03 | Yöneticilerin üye yönetebilmesi | **MVP'de mevcut** (FR-04). Gelecekte grup içi roller | MVP + R2 | authorization §5.1 |
| FX-04 | Grup içi yazılı mesajlaşma | FR-05. MVP'den R2'ye ertelenmişti | R2 | communication §3 |
| FX-05 | Fotoğraf, belge ve dosya paylaşımı | Yeni | R3 | communication §4 |
| FX-06 | Grup sesli görüşme | Yeni | R4 | communication §5 |
| FX-07 | Görüntülü görüşme | Yeni | R5 | communication §5 |
| FX-08 | Grup bazlı duyuru ve bildirimler | **MVP'de mevcut** (FR-06, Z). Gelecekte tercihler ve grup yöneticisinin duyurusu | MVP + R2/R3 | communication §1 |
| FX-09 | Acil durumlarda belirli gruplara özel bildirim | **MVP'de mevcut** (FR-13, Z). Gelecekte grup bazlı şablonlar ve eskalasyon | MVP + R2 | communication §1.4 |

### 9.1 Genişlemenin ortaya çıkardığı çelişki ve riskler
- **CON-10:** FX-01 (kullanıcıların grup kurması) ile FR-13 / FR-11 bir araya gelince, grup kuran bir kullanıcının alarm gönderme veya konum görme yetkisi kazanma riski doğar. **Çözüm önerisi (mimaride uygulandı):** Bu yetkiler yalnızca kurum rolünden gelir, grup üyeliğinden türemez (`authorization.md` §4).
- **CON-11:** FX-08 ve FX-09 "gelecek özellik" listesinde, ama orijinal belgede ilk sürüm için zorunlu. MVP'den çıkarılmadı. Yalnızca genişletilmiş biçimleri sonraki sürümlere bırakıldı.
- **AMB-19:** FX-05 fotoğrafları EXIF/GPS meta verisi taşır. Konum yetkisi olmayan kişilere dolaylı konum sızabilir (RC-06).

### 9.2 Genişlemeye ait sorular (kesinleşti)

| ID | Durum | Soru | Etkilediği |
|---|---|---|---|
| Q-27 | ✅ | Topluluk grubunu kimler oluşturabilecek (tüm gönüllüler mi, yalnızca belirli roller mi)? Operasyon dışı bu grupların içeriğinden kurum veri sorumlusu olarak sorumlu mu, moderasyonu kim yapacak? **→ K-27.** | FX-01, R3 |
| Q-28 | ✅ | Gönüllü bir operasyon grubundan kendi isteğiyle ayrılabilir mi? **→ K-28.** | FX-02 |
| Q-29 | ✅ | Mesajlarda uçtan uca şifreleme beklentisi var mı? (Öneri: hayır, denetim ve moderasyonla çelişir) **→ K-29.** | FX-04 |
| Q-30 | ✅ | Gruptan ayrılan kişi, üye olduğu dönemin sohbet geçmişini görebilir mi? **→ K-30.** | FX-04, AMB-04 |
| Q-31 | ✅ | Koordinatör, acil alarm gönderirken topluluk grubunu hedef kitle olarak seçebilir mi? **→ K-31.** | FX-01, FX-09 |
| Q-32 | ✅ | Grupta en az bir lider veya sahip bulunması zorunlu mu? **→ K-32.** | FX-01, FX-03 |
| Q-33 | ✅ | Sesli/görüntülü görüşmelerin kaydı gerekli mi? (Öneri: hayır) **→ K-33.** | FX-06, FX-07 |
