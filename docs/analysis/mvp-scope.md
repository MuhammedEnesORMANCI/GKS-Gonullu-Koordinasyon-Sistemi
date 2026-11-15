# MVP Kapsamı

> **Durum: Kesinleşti.** Proje sahibi tüm önerileri kabul etti (14.11.2026). Kararlar ve varsayımlar: [`decision-log.md`](decision-log.md). Önceki sürümdeki hiçbir özellik silinmedi. Halkası değişen özelliklerin gerekçesi §3.2'de. Kimlikler `requirements-review.md` ve `user-stories.md` dosyalarına aittir. 14.11.2026: tasarım kararları TD-01 – TD-22 kabul edildi; DR-01 (tam ekran alarm sınırı) ve DR-12 (Android'den kurumsal işlem yok) kapsama işlendi. **Halkalar değişmedi.**
>
> **Halkalar:** **Çekirdek** = dönem sonu teslimi için zorunlu · **Genişletilmiş** = Çekirdek bittikten sonra, kapsam kapısı KP3'te (§10.2) zaman varsa · **Sonraki sürüm** = R2 ve sonrası; ders kapsamı dışında (VS-08)

## 1. MVP hedefi

Belgenin tanımına göre (§10, "MVP / İlk sürüm") MVP şunu doğrulamalıdır:

> Eşzamanlı olarak aktif olabilen 5 operasyona kadar (K-02, VS-03) operasyon kapsamında yetkili bir koordinatörün grup kurup görev atayabildiği ve görev kapasitesini izleyebildiği, gönüllülerin görevleri kabul veya ret edebildiği, koordinatörün güncelliği açıkça belirtilmiş konumları görebildiği ve **acil alarmın gönderim, görülme, yanıt ve yanıtsızlık durumlarının uçtan uca izlenebildiği**, rol ve kapsam bazlı erişimin sunucuda zorlandığı en küçük ürün. Android + Web (K-03), yalnızca sentetik veri (K-04).

**Ana doğrulama hipotezi:** Koordinatör, alarm gönderiminden sonra **en geç 12 dakika içinde** (5 hatırlatma × 2 dk + pay, K-13) "kim aldı, kim yanıtladı, kime ulaşılamadı" sorusunu tek ekranda doğru biçimde cevaplayabilir. Bu süre boyunca durum en fazla 5 sn gecikmeyle güncellenir.

## 2. Kapsamı şekillendiren kararlar

| Konu | Karar | MVP'ye etkisi |
|---|---|---|
| Proje niteliği | **K-01** akademik prototip; karar mercii VS-01 | Kurumsal KVKK süreçleri, mağaza yayını ve operasyonel prosedürler ön koşul değil |
| Kapasite | **K-02** 500 kullanıcı, 5 operasyon (VS-02, VS-03) | Tek sunucu örneği yeterli. Sentetik yük testiyle doğrulanır |
| Platformlar | **K-03** Android + Web | iOS yok; push yalnızca FCM. Cihaz matrisi VS-05 |
| Veri | **K-04** yalnızca sentetik veri | Tekrar üretilebilir sentetik veri seti bir proje işidir (§9.2) |
| Takvim ve ekip | **K-05** ders dönemi, 4 kişi; backend, mobil, web, test | 14 haftalık plan (VS-07, §10). Ders kapsamı yalnızca MVP (VS-08) |
| Teknoloji | **ADR-008** (VS-09) | Java/Spring Boot, PostgreSQL, Kotlin/Compose, TypeScript/React |
| Canlı kullanım | Kaynak belge §8.2 | Gerçek veriyle canlıya geçiş bu projenin kapsamında değil (VS-10) |

## 3. MVP özellikleri ve halkalar

| # | Özellik | Belge kaynağı | Belge önceliği | Halka | MVP sınırı | Hikâyeler |
|---|---|---|---|---|---|---|
| 1 | Davetle katılım, giriş/çıkış | FR-01, GAP-01 | Z | **Çekirdek** | Kurum veya operasyon daveti (K-06) | US-01, 03 |
| 1a | Parola sıfırlama | GAP-01 | – | **Genişletilmiş** | Çekirdekte parolayı ADM sıfırlar | US-03 (AC-03.4) |
| 2 | Aydınlatma metni ve onay kaydı | §7.2, GAP-03 | – | **Genişletilmiş** | Kurgusal örnek metin (VS-10, CON-15) | US-02 |
| 3 | Temel profil | FR-02 | Z | **Çekirdek** | Sabit alanlar (K-10) | US-04 |
| 4 | Kapsamlı rol ataması ve sunucu tarafı yetki | FR-03, NFR-02 | Z | **Çekirdek** | Sabit roller (K-07) | US-05, 28 |
| 5 | Oturum iptali, hesabı devre dışı bırakma | FR-22 | Z | **Çekirdek** | – | US-06 |
| 6 | Yetkili hesaplarda MFA | NFR-03 | Ö | **Çekirdek** (CON-19) | TOTP, ADM ve KOR, yalnızca web paneli (K-19) | US-07 |
| 7 | Operasyon: oluştur, aktif et, kapat | FR-19 | Ö | **Çekirdek** (CON-06) | Planlandı / Aktif / Tamamlandı / İptal | US-08 |
| 7a | Operasyonun "Askıda" durumu | FR-19 | Ö | **Genişletilmiş** | – | US-08 |
| 8 | Grup oluşturma ve üye yönetimi; her grupta lider | FR-04 | Z | **Çekirdek** | Lider sınırlı yetkili (K-08), lider zorunlu (K-32) | US-09, 10 |
| 9 | Duyuru (grup/kişi) ve alıcı önizleme | FR-06 | Z | **Çekirdek** | Kurum geneli yalnızca ADM (K-09) | US-11 |
| 10 | Toplanma noktası paylaşma ve güncelleme | FR-09 | Z | **Çekirdek** | Grup bilgisinin bir alanı olarak; güncellemede bildirim | US-12 |
| 10a | Toplanma noktasının ayrı nesne olması (iptal, geçmiş) | FR-09 | Z | **Genişletilmiş** | – | US-12 (AC-12.2 iptal kısmı) |
| 11 | Görev oluşturma, atama, kabul/ret, durum, **kapasite takibi** | FR-07, FR-08 | Z | **Çekirdek** | Kişi bazlı durum ve kapasite (K-12, CON-18) | US-13–16 |
| 12 | Konum paylaşım kontrolü ve durum ekranı | FR-10, BR-11 | Z | **Çekirdek** | Kullanıcı onayıyla, ön plan servisi (K-14) | US-17 |
| 13 | Son bilinen konum görünümü (harita/liste) | FR-11 | Z | **Çekirdek** | Yalnızca son konum (K-16), yalnızca KOR (K-15) | US-18 |
| 14 | Konum güncellik etiketleme | FR-12, BR-07 | Z | **Çekirdek** | 2 dk / 15 dk | US-19 |
| 15 | Acil alarm gönderimi ve onay adımı | FR-13, BR-04 | Z | **Çekirdek** | Yalnızca aktif operasyonda (K-17); **MFA'lı web panelinden veya ADM/KOR'un TOTP ile açtığı Android yönetim oturumundan** (DR-12 güncellemesi, ADR-014; önceki: yalnızca web) | US-20 |
| 16 | Ayırt edilebilir alarm arayüzü | FR-14, NFR-07 | Z | **Çekirdek** | Ayrı kanal ve ses. **Uygulama açıkken tam ekran; arka planda veya kilit ekranında yüksek öncelikli bildirim, dokununca tam ekran.** Kilit ekranında kendiliğinden tam ekran, DND/sessiz modu aşma ve Critical Alerts **yok** (DR-01) | US-21 |
| 17 | Alarm yanıtı | FR-15 | Z | **Çekirdek** | Sabit 3 seçenek (K-13) | US-22 |
| 18 | Yanıtsız takip, sınırlı hatırlatma, manuel tekrar | FR-16 | Z | **Çekirdek** | 2 dk × 5; otomatik eskalasyon yok (K-13) | US-23 |
| 19 | Alıcı bazlı bildirim durumu | FR-18, BR-06 | Z | **Çekirdek** | "Teslim edildi" yalnızca sinyal alınabildiğinde | US-24 |
| 20 | Alarm iptali ve düzeltme | GAP-07 | – | **Çekirdek** | – | US-25 |
| 21 | Denetim kaydı yazımı ve listeleme | FR-20, NFR-05 | Z | **Çekirdek** | Yalnızca ADM ve UYM görür (K-26) | US-27 |
| 21a | Denetim kaydı filtreleri | FR-20 | Z | **Genişletilmiş** | – | US-27 (AC-27.4) |
| 22 | Güvenli aktarım, şifreleme, sır yönetimi | NFR-01, 04 | Z | **Çekirdek** | Prototip düzeyinde (ADR-008) | US-29 |
| 23 | Hesap kapatma talebi | §6.2, GAP-02 | – | **Genişletilmiş** | Manuel işleme (CON-15) | US-30 |
| 24 | Bildirim izni kapalı uyarısı | GAP-18 | – | **Çekirdek** | Ana ekranda kalıcı uyarı | US-31 (AC-31.2) |
| 24a | Tam hazırlık kontrolü ekranı ve KOR görünümü | GAP-18 | – | **Genişletilmiş** | – | US-31 (AC-31.1, 31.3) |
| 25 | Bağlantı durumu göstergesi; alarm yanıtının yeniden denenmesi | FR-24 (kısmi), NFR-10 | Ö | **Çekirdek** | Kontrollü ve sınırlı (K-24) | US-32, US-22 (AC-22.4) |
| 26 | Liste filtreleri (görev ve alarm durumu) | FR-21 (kısmi) | Ö | **Genişletilmiş** | Çekirdekte durum gruplaması var, filtre yok | US-16 (AC-16.2) |

### 3.1 Fonksiyonel olmayan hedefler

- NFR-01, 02, 04, 05, 06, 07 ve 15: **zorunlu, Çekirdek.**
- NFR-08 performans: p95 API ≤ 1 sn; alarm kaydından FCM'e iletim p95 ≤ 5 sn; panel güncellemesi alarm için ≤ 5 sn, görev için ≤ 10 sn. Demo ortamında yük testiyle ölçülür (VS-04).
- NFR-09 erişilebilirlik: alarm ve görev ekranları için (kontrast, ≥ 48 dp dokunma alanı, TalkBack etiketi).
- NFR-11 yedekleme: günlük yedek, RPO 24 saat, RTO 4 saat; geri yükleme Hafta 12'de bir kez test edilir (VS-04).
- NFR-12 ölçek: 500 kayıtlı sentetik kullanıcı, 150 eşzamanlı oturum, 5 eşzamanlı aktif operasyon, 500 alıcılı tek alarm (K-02, VS-02, VS-03).
- NFR-13 uyumluluk: VS-05 cihaz matrisi (minSdk 26) ve güncel Chrome/Edge/Firefox.
- NFR-14 bakım: yetki, alarm ve konum kabul kriterleri otomatik testle kapsanır. Modül sınırları testle doğrulanır (ADR-008).
- Çalışma süresi (GAP-10): kabul koşulu değil; demo süresince çalışırlık (VS-04).

### 3.2 Halkası değişen veya bölünen özellikler (gerekçeli)

Hiçbir özellik silinmedi. Aşağıdakiler önceki öneriye göre yer değiştirdi:

| Özellik | Önceki konum | Yeni konum | Gerekçe |
|---|---|---|---|
| MFA (US-07) | Genişletilmiş önerisi | **Çekirdek** | K-19 açık bir güvenlik kararı; boş zamana bırakılmamalı (CON-19). Kapsamı web paneli + TOTP ile dar tutuldu |
| Görev kapasitesi | R2 ("Grup görevlerinde kontenjan") | **Çekirdek** | K-12 açık karar (CON-18) |
| Parola sıfırlama | MVP (tek satırda) | **Genişletilmiş** | Sentetik kullanıcılarla demo için ADM sıfırlaması yeterli. E-posta akışı ek iş |
| Aydınlatma metni (US-02), hesap kapatma (US-30) | MVP | **Genişletilmiş** | K-04 ile prototipteki gerekçeleri zayıfladı (CON-15) |
| Hazırlık kontrolü (US-31) | MVP | **Bölündü:** uyarı Çekirdek, tam ekran Genişletilmiş | Alarm güvenilirliği için asgari uyarı şart (RT-01) |
| Toplanma noktası | Ayrı nesne | **Bölündü:** grup alanı Çekirdek, ayrı nesne Genişletilmiş | FR-09 Z karşılanır, model sadeleşir |
| Denetim kaydı filtreleri, liste filtreleri | MVP | **Genişletilmiş** | Çekirdek akışta zorunlu değil |
| Operasyonun "Askıda" durumu | MVP (isteğe bağlı) | **Genişletilmiş** | Hipotez için zorunlu değil |
| ADM gerekçeli acil konum erişimi | Öneri (user-roles §3) | **Sonraki sürüm (R2)** | Onaylandı; ek akış ve denetim ekranı gerektiriyor. MVP'de ADM kendini KOR olarak atar (K-15) |

## 4. Belge önceliğine göre yapılan değişiklikler (gerekçeli)

| Gereksinim | Belgede | Karar | Gerekçe |
|---|---|---|---|
| FR-19 Operasyon | Ö | **Çekirdek** | Z öncelikli konum gereksinimleri ve BR-09 buna bağlı (CON-06) |
| NFR-03 MFA | Ö | **Çekirdek** | K-19, CON-19 |
| FR-05 Grup sohbeti | Ö (§2.1'de kapsamda) | **R2** | K-22. Gerçek zamanlı altyapı, saklama, moderasyon gerektiriyor; MVP hipotezi için şart değil |
| FR-17 Eskalasyon | Ö (§2.1'de kapsamda) | **Bölündü:** MVP'de manuel tekrar, R2'de otomatik kural | K-13. Yanlış tasarlanmış otomatik eskalasyon alarm yorgunluğunu artırır (RO-02) |
| FR-21 Arama/filtre | Ö | **Bölündü:** Genişletilmiş'te temel filtre, R2'de gelişmiş | – |
| FR-24 Çevrimdışı | Ö | **Bölündü:** Çekirdekte gösterge + alarm yanıtı tekrarı, R2'de kuyruk | K-24 |
| FR-23 SOS | S | **R3** | K-23 |

## 5. Sonraki sürümler (ders kapsamı dışında, VS-08)

### R2
| Özellik | Kaynak | Not |
|---|---|---|
| Grup sohbeti | FR-05 | K-22. Mesaj saklama süresi R2 analizinde |
| Otomatik eskalasyon kuralları | FR-17 | K-13. Süre ve hedef R2 analizinde |
| Politika yapılandırma arayüzü (tekrar, eskalasyon, yanıt seçenekleri) | BR-10, AMB-15 | MVP'de sabit yapılandırma |
| Gelişmiş arama ve filtreleme | FR-21 | |
| Çevrimdışı işlem kuyruğu ve senkronizasyon | FR-24, NFR-10 | K-24 |
| Rapor ve kayda alınan dışa aktarma | GAP-09 | K-25 |
| Yetkinlik ve uygunluk etiketleri, görev eşleştirme | §7.1, GAP-20 | |
| Kurum tarafından yapılandırılabilir profil alanları | FR-02 | K-10 |
| ~~Grup görevlerinde kontenjan~~ | AMB-05 | **Çekirdek MVP'ye taşındı** (K-12, CON-18) |
| Konum geçmişi | AMB-10 | K-16: MVP'de yok. Gerekirse ayrı KVKK değerlendirmesiyle |
| Kural tabanlı otomatik konum etkinleştirme | KS-03 | Kullanıcı onayı şartıyla (K-14, CON-21) |
| ADM gerekçeli acil konum erişimi | user-roles §3 | K-15 |
| Kalıcı ekip grupları (`STANDING`) | domain-model §3.1 | K-11 |
| Gönüllünün gruptan ayrılması | FX-02 | K-28 |
| Android'den koordinatör alarm gönderimi (step-up MFA, cihaz bağlama veya talep-onay) | DR-12 | **Öneri, karar gerektirir.** Seçenekler ADR-013'te |
| iOS uygulaması (**değerlendirme**, taahhüt değil) | K-03 | VS-13 |
| Gönüllülerin katılma talebi, onay, davet bağlantısı | FX-02 | |
| Grup içi roller (yönetici, moderatör, üye) | FX-03 | |
| Bildirim tercihleri, sohbet sessize alma (alarmlar hariç) | FX-08 | |
| Grup bazlı alarm şablonları | FX-09 | |

### R3
| Özellik | Kaynak | Not |
|---|---|---|
| Kullanıcıların grup oluşturması (topluluk grupları) | FX-01 | K-27: onaylı oluşturma. Moderasyon altyapısı ön koşul |
| Fotoğraf, belge ve dosya paylaşımı | FX-05 | Nesne depolama, tarama, EXIF/GPS temizliği |
| Bire bir sohbet, mesaj düzenleme/yanıtlama | FR-05 | K-22 |
| Gönüllünün başlattığı SOS | FR-23 | K-23 |
| SMS / sesli arama yedek kanalı | §2.2 | K-20 |
| Yükseltilmiş alarm izinleri: kilit ekranında kendiliğinden tam ekran (full-screen intent), DND istisnası; iOS eklenirse Critical Alerts | §6.1, US-42 | RT-03. **MVP'ye eklenmez** (DR-01) |
| Kurum sistemleriyle entegrasyon | §2.2 | K-25 |
| Kurumsal kimlik (SSO) | NFR-03 | |

### R4 ve R5
| Özellik | Sürüm | Kaynak | Not |
|---|---|---|---|
| Grup sesli görüşme | R4 | FX-06 | Hazır WebRTC SFU + TURN (ADR-006). Kayıt yok (K-33) |
| Görev ve operasyon ekleri | R4 | FX-05 | |
| Görüntülü görüşme | R5 | FX-07 | Zayıf saha ağları nedeniyle sesten sonra gelir |

Sürüm sıralaması ve bağımlılık zinciri: `docs/architecture/README.md` §2 ve `communication.md` §6.

### 5.1 MVP'de genişleme için yapılan hazırlıklar (kapsamı büyütmez)

Aşağıdakiler MVP'nin zaten ihtiyaç duyduğu yapıların genişlemeye uygun biçimde tasarlanmasıdır. **Ek özellik, ekran veya tablo içermez.** Ayrıntı: `docs/architecture/README.md` §5.

- Üyelik ayrı bir varlık (`role`, `status`); grupta `type` ve `join_policy` alanları (MVP'de tek değer).
- Merkezi, izin tabanlı yetki kontrolü. Alarm, konum ve kurum geneli duyuru yalnızca kurum rolünden gelir.
- Öncelik sınıflı bildirim hattı. Alarm kuyruğu diğer trafikten ayrı.
- Konu tabanlı gerçek zamanlı yayın (MVP'de web paneli için SSE, ADR-009).
- Outbox ile alan olayları (`MembershipChanged` vb.).

**MVP'de yapılmayanlar:** sohbet, ek, görüşme tabloları ve servisleri; nesne depolama; SFU; katılma talebi; grup içi rol ekranları; bildirim tercihleri.

### Kalıcı olarak kapsam dışı (belge §2.2)
- Gönüllü performansını otomatik puanlama veya sıralama
- Operasyon dışında sürekli arka plan konum izleme
- Kamuya açık gönüllü dizini veya konum haritası

## 6. Kapsam daralırsa (yedek plan)

Kapsam kapısı KP2'de (§10.2) Çekirdek gecikmedeyse, Genişletilmiş halkanın tamamı R2'ye devredilir. Gecikme bundan da büyükse şu sadeleştirmeler sırayla uygulanır:
1. Duyuru alıcı önizlemesi yalnızca sayı olarak gösterilir (liste olmadan).
2. Harita yerine yalnızca konum listesi gösterilir (güncellik etiketleriyle).
3. Gerçek zamanlı güncelleme SSE yerine 5 sn'lik yoklama ile yapılır (ADR-009).

**Hiçbir koşulda kesilmemesi gerekenler:** sunucu tarafı yetki (US-28), MFA (US-07, K-19), alarm onay adımı ve iptali (US-20, US-25), alıcı bazlı durum ayrımı (US-24), konum güncelliği (US-19), operasyon kapanınca konumun durması (US-08), görev kapasite takibi (K-12), denetim kaydı (US-27).

## 7. MVP çıkış (tamamlanma) kriterleri

1. `user-stories.md` içindeki tüm **Çekirdek** hikâyelerinin kabul kriterleri geçer. Genişletilmiş hikâyelerden tamamlananlar ayrıca raporlanır.
2. Yetki matrisi negatif testlerinin tamamı otomatik olarak geçer (US-28).
3. Alarm akışı VS-05 cihaz matrisindeki her cihazda uçtan uca test edilir. Garanti edilemeyen davranışlar raporlanır (US-21 AC-21.4).
4. Demo ve tüm testler sentetik veriyle yapılır. Gerçek kişisel veri kullanılmaz (**K-04**).
5. Gerçek bilgi gerektiren varsayımlar (VS-05, VS-07, VS-09, VS-11) doğrulanmış ya da sapmaları raporlanmıştır.
6. API (OpenAPI), veri modeli, yetki matrisi ve alarm durum akışı dokümante edilmiştir (NFR-14).
7. 500 sentetik kullanıcı ve 5 operasyonla yük testi çalıştırılır, sonuçları §3.1 hedefleriyle karşılaştırılarak raporlanır (**K-02**).
8. Danışman teslimi değerlendirmiştir (VS-01).

## 8. Karar durumu

MVP'yi kesinleştirmek için gereken tüm kararlar alındı:

| Karar | Kaynak |
|---|---|
| ✅ Proje niteliği, karar mercii | K-01, VS-01 |
| ✅ Katılım yöntemi | K-06 |
| ✅ Rol ve kapsam modeli | K-07, K-08, K-09 |
| ✅ Görev durumu ve kapasite | K-12 |
| ✅ Alarm hatırlatma, yanıt ve eskalasyon | K-13 |
| ✅ Konum başlatma, sıklık, güncellik, görünürlük | K-14, K-15, K-16 |
| ✅ Platformlar ve cihaz matrisi | K-03, VS-05 |
| ✅ Sohbet ve eskalasyonun yeri | K-22, K-13 |
| ✅ Takvim, ekip, teknoloji | K-05, VS-07, VS-09, ADR-008 |
| ✅ MVP içi önceliklendirme | §3, CON-14 |

## 9. Planlama girdileri: ekip ve kapasite

> **Kişilere görev ataması yapılmamıştır.** Yetkinlik alanları yalnızca iş akışlarını gruplamak için kullanıldı.

### 9.1 Yetkinlik alanlarına göre iş akışları

| İş akışı (yetkinlik) | Çekirdek MVP'deki ana içerik | Teknoloji (ADR-008) |
|---|---|---|
| **Backend** | Kimlik, oturum, MFA; merkezi yetki (`can()`) ve rol/kapsam; operasyon, grup, görev (kapasite dahil) ve konum modülleri; bildirim hattı (outbox, öncelik kuyrukları, FCM); alarm durum makinesi ve hatırlatma zamanlayıcısı; denetim kaydı; SSE yayını; sentetik veri üreticisi | Java 21, Spring Boot, Spring Modulith, PostgreSQL |
| **Mobil (Android)** | Davet ve giriş; görev listesi ve kabul/ret; konum paylaşımı (ön plan servisi, izinler); alarm bildirim kanalı ve tam ekran alarm; alarm yanıtı ve yeniden deneme; bildirim izni uyarısı; bağlantı göstergesi; grup lideri ekranları | Kotlin, Jetpack Compose, FCM |
| **Web** | Koordinatör ve yönetici paneli: kullanıcı/davet/rol, MFA, operasyon, grup, görev ve kapasite, duyuru, alarm gönderimi ve alıcı durumu, konum haritası/listesi, denetim kayıtları | TypeScript, React, Leaflet |
| **Test** | Yetki matrisinden negatif API testleri; modül sınırı testleri; Android cihaz matrisinde alarm testi; konum güncellik testleri; 500 kullanıcı / 5 operasyon yük testi; uçtan uca ve kabul testleri | JUnit 5, Testcontainers, Playwright, k6 |

### 9.2 Tanımlı yetkinliği olmayan yatay işler (ortak sorumluluk, CON-17)

| İş | Neden gerekli | Plandaki yeri (§10) |
|---|---|---|
| Altyapı, dağıtım ve CI | Docker Compose, CI, FCM projesi, sır yönetimi (NFR-01) | H1–H2 kurulum, H13 demo ortamı |
| Güvenlik testi | NFR-02, NFR-04, RT-07 | Sürekli negatif testler + H12 |
| UX / arayüz tasarımı | Alarm ekranının ayırt edilebilirliği (FR-14), erişilebilirlik (NFR-09) | H2–H3 ekran taslakları |
| **Sentetik veri üretimi** (K-04) | 500 kullanıcı, gruplar, operasyonlar, görevler ve konumlar için tekrar üretilebilir, sabit tohumlu veri | H3 tanım, H4–H5 üretim |
| Analiz, dokümantasyon ve karar takibi | `decision-log.md`'nin güncelliği, ders teslimleri | Sürekli; H13 |

### 9.3 Halkaların gerekçesi

26 özellikten oluşan önceki MVP, bir dönemlik 4 kişilik ekip için yüksek riskliydi (CON-14, RP-03). Kabul edilen çözüm: hiçbir özelliği silmeden Çekirdek ve Genişletilmiş halkalara ayırmak, Genişletilmiş halkayı da yalnızca kapsam kapısı KP3'te (§10.2) zaman varsa başlatmak.

## 10. Haftalık takvim (VS-07: 14 haftalık dönem, tarih içermez)

### 10.1 Plan

| Hafta | Aşama | Ana çıktılar | Yetkinlik alanlarının odağı |
|---|---|---|---|
| H1 | Analiz kapanışı | Analiz dokümanlarının danışmana sunulması; gerçek bilgi gerektiren varsayımların doğrulanması (VS-05, VS-07, VS-09); depo, iş takibi ve çalışma kuralları | Tümü |
| H2 | Tasarım + teknik keşif | UML kullanım senaryosu ve etkinlik diyagramları; kritik ekran taslakları (alarm, görev, grup yönetimi, operasyon haritası); **Android alarm ve ön plan servisi konum keşfi** (RT-01, RT-02, VS-18); Docker Compose ve CI iskeleti | Mobil: keşif · Backend: altyapı · Web/Test: diyagram ve taslak |
| H3 | Tasarım | Veri modeli, OpenAPI sözleşmesi, yetki matrisi → test veri kaynağı, sentetik veri tanımı, test planı. **Kapı KP1** | Backend + Test: sözleşme ve matris · Web/Mobil: ekran akışları |
| H4–H5 | Geliştirme – Sprint 1 | Kimlik, davet, oturum, MFA; rol/kapsam ve `can()`; operasyon ve grup (lider zorunlu); web: giriş, kullanıcı/rol, operasyon ve grup ekranları; Android: giriş ve grup görünümü; negatif yetki test altyapısı; sentetik veri üreticisi | Tümü |
| H6–H7 | Geliştirme – Sprint 2 | Görev, atama ve kapasite; duyuru; toplanma noktası; bildirim hattı (outbox, öncelik kuyrukları, FCM); Android: görev listesi ve kabul/ret, bildirim kanalları; web: görev ve duyuru ekranları | Tümü |
| H8–H9 | Geliştirme – Sprint 3 | Acil alarm (onay, tam ekran, yanıt, hatırlatma, iptal, alıcı durumu); SSE; konum paylaşımı ve harita, güncellik. **Kapı KP2 (dönem ortası): uçtan uca alarm akışı demosu** | Tümü |
| H10 | Geliştirme – Sprint 4 | Denetim kaydı, oturum iptali, bağlantı göstergesi, bildirim izni uyarısı; Çekirdek eksiklerinin kapatılması. **Kapı KP3** | Tümü |
| H11 | Genişletilmiş MVP veya tampon | KP3 kararına göre Genişletilmiş özellikler veya Çekirdek hata ve borçları | Tümü |
| H12 | Test haftası | Cihaz matrisi alarm testi, 500/5 yük testi, güvenlik ve yetki testleri, yedekten geri yükleme testi, kabul testleri | Test ağırlıklı, tümü destekler |
| H13 | Stabilizasyon | Hata düzeltme, kod dondurma, dokümantasyon (API, veri modeli, yetki matrisi, alarm akışı), demo ortamının hazırlanması, demo senaryosu | Tümü |
| H14 | Teslim | Sunum, demo, rapor ve danışman değerlendirmesi | Tümü |

Test, H3'ten itibaren her sprintte süreklidir. Her sprint sonunda çalışan bir artım ve kısa bir gözden geçirme yapılır.

### 10.2 Kapsam kapıları

| Kapı | Zaman | Ölçüt | Ölçüt karşılanmazsa |
|---|---|---|---|
| KP1 | H3 sonu | Veri modeli, OpenAPI ve yetki matrisi hazır; teknik keşif sonucu ADR-011 ile uyumlu | Keşif başarısızsa konum yaklaşımı ADR-011'deki alternatife geçer; tasarım en fazla 1 hafta uzar, H11 tamponu kullanılır |
| KP2 | H9 sonu | Uçtan uca alarm akışı (web → backend → FCM → Android → yanıt → web) çalışıyor | Genişletilmiş halka R2'ye devredilir; §6 sadeleştirmeleri değerlendirilir |
| KP3 | H10 sonu | Çekirdek özelliklerin kabul kriterleri büyük ölçüde geçiyor | H11 tamamen Çekirdek'e ayrılır; Genişletilmiş halka R2'ye devredilir |

### 10.3 Dönem uzunluğu farklıysa (VS-07)

- **12 hafta:** H11 tamponu kaldırılır, Genişletilmiş halka baştan R2'ye devredilir ve H12–H13 birleştirilir.
- **16 hafta:** Bir sprint (2 hafta) H10'dan sonra Genişletilmiş halkaya ayrılır. Test haftası değişmez.
