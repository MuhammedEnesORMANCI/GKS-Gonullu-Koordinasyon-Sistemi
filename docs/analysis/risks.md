# Risk Analizi

> **Durum: Kesinleşti** (14.11.2026). Olasılık ve etki değerleri analistin **tahminidir.** Kararlar K-01 – K-33, varsayımlar VS-01 – VS-23 ve teknoloji yığını ADR-008'e göre yeniden puanlandı. Bu turda değişen satırlar "↻", yeni satırlar "✚" ile işaretli. Gerçek veriye veya gerçek kullanıma geçilirse uyum ve operasyonel riskler yeniden puanlanmalıdır.

## 1. Puanlama

- **Olasılık (O):** 1 = düşük · 2 = orta · 3 = yüksek
- **Etki (E):** 1 = düşük · 2 = orta · 3 = yüksek (gönüllü güvenliği, mahremiyet veya hukuki sonuç)
- **Skor = O × E** → 6–9: **Yüksek** · 3–4: **Orta** · 1–2: **Düşük**

## 2. Özet

| Skor | Riskler |
|---|---|
| **Yüksek (9)** | RT-01, RT-07 |
| **Yüksek (6)** | RT-04, RT-06, RT-16, RO-01, RO-02, RO-03, RO-04, RO-07, RO-11, RC-06, RP-01, RP-03, RP-08 |
| **Orta** | RT-02, RT-03, RT-05, RT-09, RT-10, RT-12, RT-13, RT-14, RT-15, RT-17, RT-18, RT-19, RT-20, RT-21, RO-05, RO-06, RO-09, RO-10, RO-12, RO-13, RC-01, RC-02, RC-07, RC-08, RP-04, RP-05, RP-09 |
| **Düşük** | RT-08, RT-11, RO-08, RC-03, RC-04, RC-05, RP-02 |
| **Kapandı** | RP-06, RP-07 |

**Son durum:** En yüksek riskler teknik çekirdekte kaldı: Android alarm davranışı (RT-01) ve yetkisiz konum erişimi (RT-07). Takvim riski (RP-03) kapsam halkaları ve haftalık planla 9'dan 6'ya indi. Yeni en önemli proje riski teknoloji varsayımı (RP-08). KVKK riskleri (RC-01 – RC-05) yalnızca sentetik veri kullanıldığı için düşük kaldı.

## 3. Teknik riskler

| ID | Risk | O | E | Skor | Önlem | İlgili |
|---|---|---|---|---|---|---|
| RT-01 | **Mobil OS bildirim kısıtları** `[belge]`: sessiz mod, DND/Odak, kapatılmış izin ve pil tasarrufu alarmın sesini ve görünümünü engelleyebilir. | 3 | 3 | **9** | Android davranışı teknik prototiple doğrulanır (K-03; iOS eklenirse ayrıca prototiplenir). Alarm için ayrı bildirim kanalı. Gerçek cihaz matrisi testi. Hazırlık kontrolü ekranı (US-31). Garanti edilemeyen davranışlar ürün vaadine yazılmaz. | FR-14, FR-16, §6.1 |
| RT-02 ↻ | **Arka plan konum kısıtları (Android):** "her zaman" konum izni ve üretici pil yönetimi, uygulama arka plandayken konumu durdurabilir. *iOS kısıtları iOS eklendiğinde geçerli.* | 2 | 2 | 4 | Kullanıcının başlattığı, kalıcı bildirimli ön plan servisi; ayrı arka plan izni istenmez (ADR-011, VS-18). Hafta 2 keşfi. Başarısızlıkta yalnızca ön planda konum + "güncel değil" etiketi. Mağaza yayını yok (VS-12). | FR-10, K-14, ADR-011 |
| RT-03 ↻ | **Yükseltilmiş alarm izinleri:** Android 14+ tam ekran bildirim (full-screen intent) izni kısıtlı; DND istisnası kullanıcı izni gerektirir. *iOS Critical Alerts, iOS eklenirse Apple onayı gerektirir.* | 2 | 2 | 4 | **MVP'de kullanılmaz** (DR-01, US-42 R3): uygulama açıkken tam ekran; arka planda/kilitte yüksek öncelikli bildirim, dokununca tam ekran. DND ve sessiz mod aşılmaya çalışılmaz; davranış TV-03 ile ölçülüp raporlanır. | §6.1, US-42, K-03, DR-01 |
| RT-04 | **Push servisine bağımlılık** (MVP'de yalnızca FCM, K-03; APNs iOS ile gelir): teslim garantisi yoktur. Platform teslim sinyali vermez, gecikme olabilir. "Teslim edildi" durumu her zaman ölçülemez. | 3 | 2 | **6** | Uygulama, bildirim alındığında sunucuya alındı bilgisi gönderir (mümkünse). Sinyal yoksa durum "bilinmiyor" olarak gösterilir (US-24 AC-24.2). Push hataları kaydedilir ve gösterilir. | FR-18, NFR-06 |
| RT-05 | **Pil tüketimi:** sık konum alımı pili tüketir, kullanıcı izni kapatır veya uygulamayı siler. | 2 | 2 | 4 | Konum sıklığı ve doğruluğu parametrik olur. Operasyon dışında sıfır konum. Pil ölçüm testi. | FR-10, Q-10 |
| RT-06 | **İnternet/GPS kesintisi** `[belge]`: afet bölgelerinde altyapı zayıftır, bildirim gecikir, konum güncellenmez. | 3 | 2 | **6** | Güncellik etiketleri (US-19), çevrimdışı gösterge (US-32), yeniden deneme, operasyonel yedek iletişim prosedürü (RO-06). | FR-24, NFR-10 |
| RT-07 | **Yetkisiz konum erişimi** `[belge]`: yetki kontrolü hatası (IDOR, eksik kapsam kontrolü) gönüllü konumunu sızdırır. | 3 | 3 | **9** | Sunucu tarafı yetki (NFR-02). Yetki matrisinden türetilen otomatik negatif testler (US-28). Konum erişim kaydı. Güvenlik testi. Tahmin edilemeyen ID'ler. | NFR-02, BR-01 |
| RT-08 ↻ | **Ani yük:** tüm kullanıcılar aynı anda bağlanır ve toplu alarm çok sayıda push üretir. K-02 ile hacim 500 kullanıcıyla sınırlı. | 1 | 2 | 2 | Push gönderimi kuyruk üzerinden yapılır. 500 sentetik kullanıcıyla yük testi yapılır (K-02, K-04). | NFR-08, NFR-12 |
| RT-09 | **Yinelenen veya çakışan işlemler:** ağ tekrarı yüzünden çift alarm veya çift yanıt oluşur. Çevrimdışı senkronizasyonda veri çakışır. | 2 | 2 | 4 | İstemci kaynaklı idempotency anahtarı (US-20 AC-20.5). Sunucu zaman damgası. Çevrimdışı kuyruk R2'ye bırakılır. | NFR-06, FR-24 |
| RT-10 | **Saat tutarsızlığı:** cihaz saati yanlışsa konum ve yanıt zamanları ile güncellik hesabı hatalı olur. | 2 | 2 | 4 | Hem cihaz ölçüm zamanı hem sunucu alış zamanı saklanır. Fark büyükse kayıt işaretlenir (US-19 AC-19.3). | FR-12, BR-07 |
| RT-11 ↻ | **Harita servisi:** OSM döşeme kullanım politikası (yoğun kullanım yasak), kesinti veya erişim sınırlaması. | 1 | 2 | 2 | Düşük hacimli prototip kullanımı, istemci önbelleği, uygun kullanıcı aracısı ve atıf. Gerekirse başka bir OSM döşeme sağlayıcısına geçilir; konum verisi harita servisine gönderilmez (ADR-008). | K-18 |
| RT-12 | **Denetim kaydı bütünlüğü ve içeriği:** kayıtlar değiştirilebilir veya hassas veri içerebilir. | 2 | 2 | 4 | Append-only saklama, ayrı yetki. Kayıtlarda parola, belirteç ve içerik bulunmaz (AC-27.2). | NFR-05 |
| RT-13 | **Kayıp/çalınan cihaz:** açık oturum üzerinden alarm ve konum verisine erişilir. | 2 | 2 | 4 | Oturum iptali (US-06), kısa ömürlü belirteçler. Uygulama kilidi (biyometri/PIN) değerlendirilir. | FR-22 |
| RT-14 | **Tek nokta arızası:** kritik anda sunucu veya veritabanı kesintisi. Erişilebilirlik hedefi tanımlı değil. | 2 | 2 | 4 | Erişilebilirlik hedefi (GAP-10), izleme ve uyarı (GAP-11), yedekleme ve kurtarma testi (NFR-11). | GAP-10, NFR-11 |
| RT-15 | **Gerçek zamanlı altyapı karmaşıklığı:** canlı konum, sohbet ve anlık durum güncellemeleri ekibin yetkinliğini aşabilir. | 2 | 2 | 4 | Sohbet R2'ye bırakılır. MVP'de basit yöntem (ör. kısa aralıklı yenileme) değerlendirilir. Mimari aşamada karar verilir. | FR-05, FR-11 |

## 4. Operasyonel riskler

> K-01 ile sistem gerçek operasyonda kullanılmayacak. Bu riskler prototipte gözlemlenemez, ancak **ürün tasarımını yönlendirdikleri için korunmuştur** (ör. onay adımı, iptal, durum ayrımı). Puanlar "gerçek kullanım olsaydı" varsayımıyla bırakıldı.

| ID | Risk | O | E | Skor | Önlem | İlgili |
|---|---|---|---|---|---|---|
| RO-01 | **Yanlış hedefe acil mesaj** `[belge]` | 2 | 3 | **6** | Alıcı önizlemesi, onay adımı (US-20), iptal ve düzeltme akışı (US-25). | BR-04 |
| RO-02 | **Alarm yorgunluğu:** sık veya gereksiz alarm ve hatırlatma, kullanıcıların bildirimleri kapatmasına yol açar. | 2 | 3 | **6** | Alarm yalnızca yetkili rollerde. Tekrar sayısı sınırlı (CON-07). Alarm kullanım politikası (Q-05, Q-09). Alarm ve duyuru ayrımı. | FR-16, BR-10 |
| RO-03 | **Sahte güven:** koordinatör "gönderildi" bilgisini "ulaştı" sanar veya sistemi garantili bir kanal olarak görür. | 3 | 2 | **6** | Durumlar ayrı gösterilir (US-24). "Bilinmiyor" açıkça gösterilir. Koordinatör eğitimi. Sistem sınırlarının belgelenmesi. | BR-06, §6.1 |
| RO-04 | **Benimseme:** gönüllüler uygulamayı kurmaz, izin vermez ya da paralel mesajlaşma uygulamalarını kullanmaya devam eder. | 3 | 2 | **6** | Kolay katılım (SC-01), hazırlık kontrolü, pilot öncesi kullanılabilirlik testi, kurumun kullanım politikası. | §1.1 |
| RO-05 | **Koordinatör iş yükü ve eğitim:** karmaşık panel, kriz anında hata yapma ihtimalini artırır. | 2 | 2 | 4 | Kritik akışlarda az adım. Kullanılabilirlik testi. Kısa kullanım kılavuzu. | NFR-07 |
| RO-06 | **Sistem çalışmadığında yedek prosedür yok.** | 2 | 2 | 4 | Kurumun yedek iletişim prosedürü yazılır (yazılım dışı). Q-18. | §9.1 |
| RO-07 | **İçeriden kötüye kullanım:** yetkili bir kişi konumu kişisel amaçla izler (takip, taciz). | 2 | 3 | **6** | Konum erişimi yalnızca aktif operasyonda (BR-09). Erişim kaydı ve periyodik denetim. MFA. En az yetki. | RT-07, NFR-15 |
| RO-08 | **Yanlış veya kötü niyetli yardım çağrısı.** | 1 | 2 | 2 | SOS R3'e bırakıldı. MVP'de yardım isteği yalnızca alarma yanıt olarak var. | FR-23 |
| RO-09 | **Rollerin güncel tutulmaması:** ayrılan veya görevi biten koordinatörün yetkisi kalır. | 2 | 2 | 4 | Operasyon kapanışında kapsamlı rollerin otomatik pasifleşmesi. Periyodik yetki gözden geçirmesi. | `user-roles.md` §6 |
| RO-10 ↻ | **Operasyonun kapatılmayı unutulması:** konum toplanmaya devam eder (BR-09'un fiilen ihlali). | 2 | 2 | 4 | Planlanan bitişten 2 saat sonra KOR'a hatırlatma (VS-14). Gönüllü paylaşımı istediği an durdurabilir (K-14). Otomatik askıya alma yok ("Askıda" durumu Genişletilmiş MVP). | BR-09 |

## 5. Uyum ve hukuki riskler (KVKK)

> Bu bölüm teknik bir risk değerlendirmesidir. Hukuki görüş değildir (belge §7 ile uyumlu).

| ID | Risk | O | E | Skor | Önlem | İlgili |
|---|---|---|---|---|---|---|
| RC-01 ↻ | **Veri sorumluluğu ve hukuki dayanak belirsizliği** `[belge]`: canlı kullanım engellenebilir veya mevzuat riski doğar. K-01 ve K-04 ile prototipte gerçek kişisel veri işlenmiyor. | 1 | 3 | 3 | Yalnızca sentetik veri (K-04). Gerçek veriye geçiş kararı alınırsa AK-10 ile yeniden açılır. | §7, K-01, K-04 |
| RC-02 ↻ | **Aydınlatma ve rıza eksikliği:** gönüllü, kurumla ilişkisi nedeniyle konum paylaşmaya kendini zorunlu hissedebilir. Prototipte gerçek gönüllü yok (K-04). | 1 | 3 | 3 | Aydınlatma ve onay kaydı (US-02). Konum paylaşımının gönüllünün kontrolünde olması (US-17). Hukuki şartın kurumca belirlenmesi. | §7.2, KS-03 |
| RC-03 ↻ | **Belirsiz saklama süreleri:** verinin, özellikle konumun, gereğinden uzun tutulması. Prototipte veri sentetik (K-04). | 1 | 2 | 2 | MVP'de konum geçmişi tutulmaz (yalnızca son konum). Süreler kurumdan alınır (Q-23). Otomatik imha işi tasarlanır. | §7.3 |
| RC-04 ↻ | **Yurtdışına veri aktarımı:** bulut, push ve harita sağlayıcıları veriyi yurtdışında işleyebilir. Prototipte aktarılan veri sentetik (K-04). | 1 | 2 | 2 | Sağlayıcı ve barındırma kararı kurumla verilir (Q-15). Push yükünde kişisel veri asgaride tutulur. | §7.2 |
| RC-05 ↻ | **Özel nitelikli veriye sızma:** "Yardım gerekiyor" yanıtı ve serbest metin alanları (görev notu, sohbet) dolaylı olarak sağlık bilgisi toplayabilir. Prototipte veri sentetik; önlem tasarımda korunur. | 1 | 2 | 2 | Alarm yanıtlarında serbest metin olmaz (AC-22.5). Kullanıcıya uyarı metni gösterilir. BR-12 uygulanır. | BR-12 |

## 6. Proje riskleri

| ID | Risk | O | E | Skor | Önlem | İlgili |
|---|---|---|---|---|---|---|
| RP-01 | **Kapsam genişlemesi** `[belge]` | 3 | 2 | **6** | `mvp-scope.md` onaylanır. Değişiklikler gerekçeyle ve sürüm hedefiyle kaydedilir. | §9.1 |
| RP-02 ↻ | **Paydaşa erişim ve geri bildirim gecikmesi:** tüm sorular kararlaştırıldı (K-06 – K-33). Kalan risk danışman geri bildiriminin gecikmesi. | 1 | 2 | 2 | Analiz paketi Hafta 1'de danışmana sunulur (VS-01). Geri bildirim gelene kadar onaylı kararlarla ilerlenir. | VS-01 |
| RP-03 ↻ | **Takvim ve kapsam uyumsuzluğu:** bir dönem ve 4 kişi (K-05). Kapsam Çekirdek ve Genişletilmiş halkalara ayrıldı, 14 haftalık plan ve 3 kapsam kapısı tanımlandı. Dönem uzunluğu varsayım (VS-07). | 2 | 3 | **6** | Halkalar ve kapılar (`mvp-scope.md` §3, §10). KP2'de Genişletilmiş halka R2'ye devredilebilir; §6 sadeleştirmeleri. Kritik yol işleri (alarm keşfi, yetki, bildirim hattı) erken. | K-05, VS-07 |
| RP-04 ↻ | **Gerçek cihazla test imkânı:** farklı Android üreticileri (agresif pil yönetimi) ve sürümleri bulunmayabilir. iOS bu sürümde gerekmiyor (K-03). | 2 | 2 | 4 | Android cihaz matrisi erken belirlenir (AK-05). | NFR-13, K-03 |
| RP-05 ↻ | **Ekip yetkinliği:** ekip backend, mobil, web ve test alanlarını kapsıyor (K-05). Altyapı, güvenlik testi ve UX için tanımlı yetkinlik yok (CON-17). | 2 | 2 | 4 | Yatay işler ortak sorumluluk ve plandaki haftalara bağlı (`mvp-scope.md` §9.2, §10). Docker Compose ve hazır bileşenlerle (MUI, Material 3) yük azaltılır. | K-05 |
| RP-06 ↻ | **Gerçek veriyle test yapılması.** **Kapandı:** K-04 ile yalnızca sentetik veri kullanılacak. | – | – | – | Sentetik veri seti üretimi planlandı (`mvp-scope.md` §9.2). | K-04 |
| RP-07 ↻ | **Karar merciinin belirsizliği.** **Kapandı:** VS-01 ile proje ekibi karar verir, danışman değerlendirir. | – | – | – | – | VS-01 |
| RP-08 ✚ | **Teknoloji varsayımı (VS-09) tutmazsa:** ekip Java/Spring veya Kotlin'e yabancıysa öğrenme eğrisi takvimi kaydırır. | 2 | 3 | **6** | Hafta 1'de doğrulama. Uymazsa ADR-008 B planı (NestJS); mimari ve sözleşme değişmez. Hafta 3'e kadar iskelet ve örnek modül. | VS-09, ADR-008 |
| RP-09 ✚ | **Genişletilmiş halkanın yetişmemesi:** onaylı ama Genişletilmiş'e alınan özellikler (parola sıfırlama, aydınlatma metni, hesap kapatma, filtreler, "Askıda" durumu) dönem içinde teslim edilemez. | 2 | 2 | 4 | Kapı KP3'te açık karar; devredilenler R2 listesine gerekçeyle yazılır. Kesin kararlar (MFA, kapasite) bu nedenle Çekirdeğe alındı (CON-18, CON-19). | `mvp-scope.md` §3.2 |

## 6A. Genişleme (sohbet, dosya, görüşme, topluluk grupları) riskleri

Bu riskler R2 ve sonrası için geçerlidir. Ancak RT-16 ve RT-20'nin önlemleri MVP mimarisinde alınır.

| ID | Risk | O | E | Skor | Önlem | İlgili |
|---|---|---|---|---|---|---|
| RT-16 | **Sohbet trafiğinin alarm iletimini geciktirmesi** | 2 | 3 | **6** | `CRITICAL` öncelik için ayrı kuyruk ve işçi (ADR-004). MVP'den itibaren uygulanır | FX-04 |
| RT-17 | Gerçek zamanlı bağlantı sayısının ve yayın hacminin büyümesi | 2 | 2 | 4 | Ayrılabilir geçit, pub/sub arka kanalı, REST imleçleriyle telafi (ADR-005) | FX-04 |
| RT-18 | Zararlı dosya, depolama maliyeti ve kotanın aşılması | 2 | 2 | 4 | Tarama, tür beyaz listesi, boyut ve kota sınırları (communication §4) | FX-05 |
| RT-19 | SFU/TURN maliyeti ve işletim yükü; zayıf saha ağlarında görüşme kalitesi | 2 | 2 | 4 | Önce ses, düşük bant genişliği profili, ürün seçimi R4'te (ADR-006) | FX-06, FX-07 |
| RT-20 | **Aşırı mühendislik:** gelecek özellikler için MVP'de gereksiz altyapı kurulması takvimi uzatır | 2 | 2 | 4 | Yalnızca MVP'nin zaten ihtiyaç duyduğu hazırlıklar yapılır (architecture README §5). Boş tablo ve kullanılmayan servis yok | RP-01, RP-03 |
| RT-21 ✚ | **Kapasite takibinde eşzamanlılık hatası:** aynı anda gelen kabuller kapasiteyi aşabilir. | 2 | 2 | 4 | Kabul, kapasite kontrolüyle aynı işlemde satır kilidiyle yapılır. Eşzamanlılık testi (AC-14.6). | K-12 |
| RO-11 | Topluluk gruplarında uygunsuz içerik, taciz, moderasyon eksikliği | 2 | 3 | **6** | Şikâyet ve moderasyon R3'ten önce hazır olur. Kurum politikası (Q-27) | FX-01 |
| RO-12 | Kritik bilginin alarm yerine sohbette paylaşılıp kaybolması | 2 | 2 | 4 | Kullanım kuralları ve eğitim. Sohbet bildirimleri alarm kanalından görsel olarak ayrı | FX-04 |
| RO-13 ✚ | **Sahadaki koordinatör alarmı Android'den gönderemez** (DR-12): web paneline erişimi yoksa alarm gecikir. | 2 | 2 | 4 | Web paneli telefon tarayıcısından MFA ile açılabilir (TV-13). Gelecek seçenekler ADR-013'te; MVP'ye eklenmez. | ADR-013, K-19 |
| RC-06 | **Fotoğraf meta verisiyle konum sızıntısı** (EXIF/GPS) | 3 | 2 | **6** | Sunucu tarafında meta verinin zorunlu olarak silinmesi (F-04) | FX-05, NFR-15 |
| RC-07 | Ses ve görüntünün yeni bir kişisel veri kategorisi olması; kayıt yapılırsa saklama yükümlülüğü | 1 | 3 | 3 | Kayıt yapılmaz (K-33). Aydınlatma metni güncellenir | FX-06, FX-07 |
| RC-08 | Mesaj ve dosyaların saklama süresinin belirsizliği | 2 | 2 | 4 | Konuşma türüne göre saklama politikası ve otomatik imha (Q-20, AK-10) | FX-04, FX-05 |

## 7. Bağımlılıklar

| Bağımlılık | Tür | Etkilediği |
|---|---|---|
| Push bildirim servisi (MVP: FCM; APNs iOS ile) | Dış teknik | FR-13 – FR-18 |
| Mobil OS konum ve bildirim API'leri, mağaza politikaları | Dış teknik | FR-10, FR-14, RT-01–03 |
| Harita sağlayıcısı | Dış teknik | FR-09, FR-11 |
| Kurumun KVKK kararları (aydınlatma, saklama, hukuki şart) | Organizasyonel | Canlı kullanım, US-02, US-30 |
| Danışman değerlendirmesi (VS-01) | Organizasyonel | Kapsam ve teslimat onayı |
| Test cihazları ve geliştirici hesapları | Kaynak | NFR-13, US-21 |

## 8. Erken doğrulama önerileri (mimariden önce)

Bunlar kodlama değil, mimari aşamada karar vermeyi mümkün kılacak **teknik keşif** maddeleridir. Analiz onaylandıktan sonra planlanmalıdır.

1. Alarm davranışının hedef Android cihazlarda (K-03, AK-05) doğrulanması: ön planda, arka planda, kapalıyken, kilit ekranında, sessiz modda ve DND açıkken (RT-01).
2. Arka plan konum izni ve mağaza politikası gereksinimlerinin doğrulanması (RT-02).
3. Push teslim sinyalinin hangi koşullarda alınabildiğinin ölçülmesi (RT-04).
