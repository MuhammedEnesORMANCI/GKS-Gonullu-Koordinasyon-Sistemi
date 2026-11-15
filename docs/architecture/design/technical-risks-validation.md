# Teknik Riskler ve Doğrulama Planı

| Alan | Bilgi |
|---|---|
| Durum | **Kabul edildi** (14.11.2026). DR-01 ve DR-12 sınırları kesin |
| Dayanak | ADR-011, RT-01 – RT-21 (`docs/analysis/risks.md`), kaynak belge §6.1 |
| Ne zaman | Teknik keşif: **Hafta 2**. Sonuç kapsam kapısı **KP1**'de (Hafta 3 sonu) değerlendirilir (`mvp-scope.md` §10) |

> Bu doküman risk kaydını (`risks.md`) tekrar etmez. Tasarımın dayandığı ve **henüz doğrulanmamış** teknik varsayımları, Android'in **garanti etmediği** davranışları ve bunların nasıl test edileceğini tanımlar.

## 1. Android'de garanti edilemeyen davranışlar

Aşağıdakiler kullanıcı ayarlarına, Android sürümüne ve cihaz üreticisine bağlıdır. **Ürün metinlerinde, demoda ve raporda garanti olarak sunulmaz.**

| # | Davranış | Neden garanti edilemez | Tasarımdaki karşılık |
|---|---|---|---|
| G-01 | Alarmın sessiz modda veya titreşim modunda ses çıkarması | Kanal sesi zil sesi moduna ve kullanıcı ayarına bağlı | Ses çalmasa da bildirim ve ongoing durum korunur; ses davranışı cihaz matrisinde ölçülür (TV-03) |
| G-02 | Rahatsız Etmeyin / Odak modunu aşması | Kanalın DND istisnası kullanıcı izni gerektirir; uygulama bunu kendiliğinden açamaz | MVP'de istenmez. Genişletilmiş hazırlık ekranı (US-31) kullanıcıyı ayara yönlendirebilir |
| G-03 | Kilit ekranında veya başka uygulama açıkken **kendiliğinden tam ekran** açılması | Full-screen intent; Android 14+ kısıtlamaları; MVP kararı | **MVP'de kullanılmaz, uygulama da denemez** (DR-01, RT-03, US-42 R3). Uygulama açıkken tam ekran; arka planda/kilitte yüksek öncelikli bildirim, dokununca tam ekran (K-13) |
| G-04 | Push mesajının cihaza ulaşması | FCM en iyi çaba ilkesiyle çalışır; ağ, Doze, kota | Alıcı bazlı durum, "bilinmiyor" gösterimi, hatırlatma, manuel yeniden gönderim |
| G-05 | Zorla durdurulmuş uygulamaya mesaj ulaşması | Android, kullanıcı açana kadar zorla durdurulmuş uygulamaya yayın iletmez. Bazı üreticiler son uygulamalardan kaydırmayı zorla durdurma gibi işler | Kullanıcı eğitimi; hazırlık ekranı; panelde teslim "bilinmiyor" |
| G-06 | "Teslim edildi" sinyalinin her zaman gelmesi | Sinyal, uygulamanın çalışıp ağa erişebilmesine bağlı | Sinyal yoksa durum yanlış olumlu gösterilmez (AC-24.2) |
| G-07 | Konum paylaşımının uygulama arka plandayken kesintisiz sürmesi | Üretici pil yönetimi ön plan servisini durdurabilir; yeniden başlatma veya kapanma sonrası servis kendiliğinden dönmez | Güncellik etiketleri (2 dk / 15 dk); kesinti uygulama açılınca gösterilir (ADR-011) |
| G-08 | Bildirim izninin verilmiş olması (Android 13+) | Çalışma zamanında istenen izin; kullanıcı reddedebilir veya sonradan kapatabilir | Kalıcı uyarı (AC-31.2), TD-17 |
| G-09 | Konum doğruluğu | GPS, iç mekân, cihaz donanımı | `accuracy` alanı gösterilir; düşük doğruluk işaretlenir |
| G-10 | Cihaz saatinin doğru olması | Kullanıcı saati elle değiştirebilir | Sunucu alış zamanı da saklanır; fark > 2 dk ise işaret (AC-19.3) |
| G-11 | Alarm bildiriminin kaydırılarak kapatılamaması (ongoing) | Android 14+ ongoing bildirimleri kilit ekranı dışında kullanıcı tarafından kapatılabilir kılar (doğrulanacak, DV listesi) | Kapatılan bildirim hemen yeniden gösterilmez; sunucu hatırlatmaları ve AND-03 açık alarm bandı sürer (LD-06) |

## 2. ADR-011: Android konum ve alarm davranışı

### 2.1 Tasarım varsayımları

| # | Varsayım | Doğruluğu neye bağlı | Doğrulama |
|---|---|---|---|
| V-01 | Uygulama görünürken başlatılan, `location` türünde ve kalıcı bildirimli bir ön plan servisi, uygulama arka plana geçince "kullanım sırasında" izniyle konum almaya devam eder; ayrı "her zaman" izni gerekmez | Android'in ön plan servisi ve konum izni kuralları; manifest beyanları (`FOREGROUND_SERVICE_LOCATION`, servis türü) | TV-01 |
| V-02 | Servis, kalıcı bildirimdeki "Durdur" eylemiyle veya FCM'den gelen "konumu durdur" mesajıyla arka plandayken durdurulabilir | Servis kendi sürecinde olduğundan genelde mümkün; test edilmeli | TV-02 |
| V-03 | 60 sn aralıklı konum alımının pil tüketimi bir demo günü için kabul edilebilir | Cihaz, konum doğruluk ayarı | TV-05 |
| V-04 | Yüksek öncelikli FCM data mesajı, Doze modundaki cihazda bildirim gösterecek kadar uygulamayı uyandırır | FCM öncelik kuralları. Mesaj görünür bildirimle sonuçlanmazsa Android önceliği düşürebilir; tasarım her alarmda bildirim gösterir | TV-03 |
| V-05 | `alarm` kanalında özel ses ve yüksek önem, uygulama arka plandayken heads-up bildirim üretir | Kanal önemi kullanıcı tarafından düşürülebilir | TV-03, TV-04 |
| V-06 | Bildirim eylem düğmeleriyle (TD-21) uygulama açılmadan yanıt verilebilir | Bildirim eylemleri ve kısa ömürlü arka plan çalışma izni | TV-06 |
| V-07 | Bildirim sınıfları ayrı kanallardadır; kullanıcının "genel" kanalı kapatması alarmı etkilemez | Kanal ayarları kanal başınadır | TV-04 |

### 2.2 Keşif başarısız olursa (ADR-011'deki alternatif)

| Başarısız varsayım | Alternatif | Kapsam etkisi |
|---|---|---|
| V-01 | Konum yalnızca uygulama ön plandayken gönderilir; arka planda "güncel değil" etiketi devreye girer | Yok. K-14 ve FR-12 ile uyumlu |
| V-02 | Durdurma mesajı alınamazsa sunucu ölçümleri reddeder ve yanıt servisi durdurur (TD-18) | Yok |
| V-04 / V-05 | Hatırlatmalar ve uygulama açılışında açık alarm kontrolü asıl güvence olur; test raporunda sınır belirtilir | Yok; sınır raporlanır |
| V-06 | Yanıt yalnızca tam ekran alarm ekranından verilir | TD-21 düşer |

## 3. Teknik doğrulama listesi (Hafta 2 keşfi)

Keşif, ekipteki gerçek cihazlarda (VS-05: en az 3 cihaz, en az biri Android 14+, en az biri Android 10–13, en az iki farklı üretici) ve emülatörde yapılır. Sonuçlar KP1'de raporlanır.

| TV | Doğrulanacak konu | Yöntem | Başarı ölçütü | İlgili |
|---|---|---|---|---|
| TV-01 | Ön plan servisiyle arka planda konum (V-01) | Paylaşımı başlat → uygulamayı arka plana al → ekranı kilitle → 30 dk boyunca alınan ölçümleri say | Her cihazda ölçümlerin ≥ %90'ı 60 ± 15 sn aralıkla geliyor; sapan cihazlar raporlanıyor | ADR-011, RT-02, VS-18 |
| TV-02 | Servisin uzaktan ve bildirimden durdurulması (V-02) | Operasyonu kapat → FCM "durdur" mesajı → servis durumu; ayrıca bildirimden durdurma | ≤ 1 dk içinde durma (AC-08.3) | TD-18 |
| TV-03 | Alarm bildirimi davranış matrisi (V-04, V-05) | Durumlar: ön plan / arka plan / uygulama kapalı (süreç sonlanmış) / ekran kilitli / Doze / sessiz / titreşim / DND / zorla durdurulmuş | Her hücre için: bildirim geldi mi, ses çaldı mı, ne kadar sürede? Tablo AC-21.4 raporunun temeli olur | RT-01, AC-21.4 |
| TV-04 | Kanal ayrımı (V-07) | Genel kanalı kapat, alarm gönder | Alarm etkilenmiyor | HZ-9 |
| TV-05 | Pil tüketimi (V-03) | 2 saat paylaşım; batarya yüzdesi ve sistem pil istatistikleri | Rapor; kabul eşiği yok, gözlem | RT-05 |
| TV-06 | Bildirimden yanıt (V-06) | Kilit ekranında ve arka planda eylem düğmesiyle yanıt | Yanıt sunucuya ≤ 10 sn içinde ulaşıyor | TD-21 |
| TV-07 | Çevrimdışı alarm yanıtı | Uçak modunda yanıt → ağ açılınca | Tek yanıt kaydı; kullanıcı bilgilendirmesi doğru | K-24, AC-22.4 |
| TV-08 | FCM uçtan uca gecikme | 50 alarm gönderimi; kayıttan alındı bilgisine kadar | Dağılım raporlanır. Sunucu tarafı p95 ≤ 5 sn ayrıca ölçülür | NFR-08 |
| TV-09 | Bildirim izni reddi (Android 13+) | İzni reddet → alarm gönder → uygulamayı aç | Kalıcı uyarı görünüyor; alarm uygulama içinde listeleniyor | AC-31.2, TD-17 |
| TV-10 | OSM döşemelerinin Android haritada (MapLibre) çalışması | Raster OSM stiliyle harita; atıf | Harita açılıyor; döşeme politikasına uygun kullanıcı aracısı ve atıf | ADR-008, RT-11 |
| TV-11 | SSE'nin ters vekil (Caddy) arkasında kopmadan çalışması | 30 dk açık bağlantı; vekil zaman aşımı; yeniden bağlanma | Bağlantı düşerse ≤ 5 sn içinde yeniden kuruluyor; olay kaçmıyor (`Last-Event-ID`) | ADR-009 |
| TV-12 | Eşzamanlı görev kabulü | Aynı göreve aynı anda N+1 kabul isteği | Kabul sayısı kapasiteyi aşmıyor | RT-21, AC-14.6 |
| TV-13 | Web panelinin telefon tarayıcısında kullanılabilirliği (DR-12) | Android telefonda Chrome ile WEB-01 (TOTP), WEB-09, WEB-10 | Giriş, alarm oluşturma, onay ve alıcı durumu dar ekranda tamamlanabiliyor; yerleşim optimize edilmez | ADR-013, RO-13 |

TV-11, TV-12 ve TV-13 Android uygulaması dışında olsa da sistemin teknik varsayımlarını doğruladığı için aynı listede tutuldu. TV-12 Hafta 6–7'de, TV-13 Hafta 8–9'da, diğerleri Hafta 2'de yapılır.

## 4. Diğer teknik riskler (tasarım düzeyi)

| Risk | Tasarımdaki önlem | İlgili |
|---|---|---|
| Sohbet gibi gelecek trafik alarmı geciktirebilir | `CRITICAL` kuyruğu ayrı işçi havuzunda | RT-16, ADR-004 |
| Yetki hatasıyla konum sızıntısı | Tek kaynak yetki matrisi; liste sorgularında kapsam filtresi; tahmin edilemeyen kimlikler; negatif testler | RT-07, US-28 |
| Modül sınırlarının aşınması | Spring Modulith doğrulama testleri CI'da | ADR-001 |
| Bayat alarm gösterimi | Durum doğrulaması (TD-10), `collapse_key`, TTL | TD-08 |
| Kimlik belirteci sızıntısı | Kısa ömürlü erişim belirteci, döndürülen yenileme belirteci, HttpOnly çerez, şifreli Android deposu | ADR-012 |
| Sentetik verinin gerçekçi olmaması | Datafaker Türkçe yerel ayarı, sabit tohum, gerçekçi grup ve konum dağılımı | K-04 |
