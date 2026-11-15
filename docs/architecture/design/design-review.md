# Tasarım Kontrol Raporu

| Alan | Bilgi |
|---|---|
| Tarih | 14.11.2026 |
| Kapsam | `architecture/design/`, `architecture/diagrams/`, `architecture/ui-flows/` (yeni); `domain-model.md` ve `README.md` (küçük düzeltmeler) |
| Durum | **Kapandı** (14.11.2026). TD-01 – TD-22 kabul edildi; tüm bulgular (DR-01 – DR-17) çözüldü. Kesinleşmiş kararlar (K-01 – K-33) değiştirilmedi |

## 1. Yapılan kontroller ve sonuçları

| # | Kontrol | Yöntem | Sonuç |
|---|---|---|---|
| 1 | Diyagram sözdizimi | 33 Mermaid diyagramı `mermaid-cli` ile derlendi | **33/33 hatasız** |
| 2 | Kimlik ve referans bütünlüğü | 28 kimlik ailesi (K, Q, VS, CON, ADR, US, AC, TD, AND, WEB, UC, TV vb.) tüm dokümanlarda tarandı | **Tanımsız referans yok** |
| 3 | Gereksinim uyumu | 29 Çekirdek hikâyenin tasarım dokümanlarında karşılığı arandı | 29/29 karşılanıyor (§2) |
| 4 | Rol yetkileri | Web rol-menü tablosu, use case diyagramı ve ekran rolleri `user-roles.md` §4 ile karşılaştırıldı | Uyumlu (§3) |
| 5 | MVP dışı özellik sızıntısı | Yeni dosyalarda R2+ terimleri bağlamıyla tarandı | Sızıntı yok (§4) |
| 6 | Varlık ilişkileri | ERD ile `domain-model.md` varlıkları karşılaştırıldı | Uyumlu; 3 ek varlık TD olarak işaretli (§5) |

## 2. Gereksinim izlenebilirliği (Çekirdek)

| Alan | Hikâyeler | Diyagram | Ekran |
|---|---|---|---|
| Hesap ve erişim | US-01, 03, 04, 05, 06, 07, 10, 28 | sequence-login, sequence-invitation, activity-invitation, use case G1 | AND-01, 02, 10, 11 · WEB-01, 04, 12 |
| Operasyon | US-08 | activity-join-operation, sequence-location (kapanış) | WEB-03 · AND-04 |
| Grup ve toplanma noktası | US-09, 10, 12 | activity-join-operation, use case G2 | WEB-05 · AND-06, 06a |
| Duyuru | US-11 | use case G3 | WEB-07 · AND-09 |
| Görev | US-13 – US-16 | activity-task, sequence-task-assignment | WEB-06 · AND-05 |
| Konum | US-17 – US-19 | activity-location, sequence-location | WEB-08 · AND-07 |
| Acil alarm | US-20 – US-25 | activity-alert, sequence-alert, emergency-alert-design | WEB-09, 10 · AND-08 |
| Denetim | US-27 | tüm sequence diyagramlarında audit katılımcısı | WEB-11 |
| Güvenlik ve dağıtım | US-29 | deployment-diagram, sequence-login | – |
| Hazırlık ve bağlantı | US-31 (AC-31.2), US-32 | activity-invitation, emergency-alert-design §3 | AND-03 |

## 3. Rol ve yetki kontrolü

| Kontrol noktası | Karar | Tasarımdaki karşılık | Durum |
|---|---|---|---|
| Grup lideri üye ekleyemez, alarm/duyuru gönderemez, konum göremez | K-08, K-09, K-15 | AND-06a'da üye yönetimi yok; use case'te LDR yalnızca UC-26/27/30/32/45/46 | ✓ |
| ADM operasyonel işlemleri yalnızca KOR olarak atanırsa yapar | K-07 | Use case'te ADM'den görev, alarm ve konuma bağlantı yok; web'de bu ekranlar ADM için salt-okur | ✓ |
| Konumu yalnızca aktif operasyonun KOR'u görür; erişim denetime yazılır | K-15, BR-08 | WEB-08 yalnızca KOR; sequence-location'da `LOCATION_VIEWED` | ✓ |
| MFA ADM ve KOR için web'de zorunlu | K-19 | sequence-login, WEB-01; Android oturumu kurumsal izin taşımaz | ✓ |
| Denetim kayıtlarını yalnızca ADM ve UYM görür | K-26 | WEB-11 menüsü | ✓ |
| TEK iş verisi görmez | user-roles §3 | WEB-04'te yalnızca görünen ad ve hesap durumu (TD-14) | ✓ |
| Kurum geneli duyuru yalnızca ADM | K-09 | WEB-07 | ✓ |
| Gönderen kendi alarmının alıcısı olmaz | user-roles §2 | emergency-alert-design Adım 1 | ✓ |

## 4. MVP dışı özellikler

Aşağıdakiler hiçbir diyagram veya ekranda **işlevsel olarak yer almaz.** Yalnızca "yok" ya da "R2+" notuyla anılır: grup sohbeti, otomatik eskalasyon, gönüllünün başlattığı SOS, bildirim tercihleri, katılma talebi, topluluk/kalıcı ekip grupları, dosya paylaşımı, sesli/görüntülü görüşme, rapor/dışa aktarma, konum geçmişi, kural tabanlı konum etkinleştirme, ADM gerekçeli acil konum erişimi, SMS yedek kanalı, kilit ekranında kendiliğinden tam ekran alarm (full-screen intent), iOS.

Genişletilmiş MVP öğeleri (parola sıfırlama, aydınlatma onayı, hesap kapatma, hazırlık ekranı, filtreler, "Askıda" durumu, ayrı toplanma noktası varlığı) kesik çerçeve veya "G" etiketiyle ayrıldı.

## 5. Varlık ilişkileri (`domain-model.md` ↔ `erd.md`)

- `domain-model.md` §2 ve §5A'daki tüm MVP varlıkları ERD'de var.
- ERD'de olup `domain-model.md`'de olmayan üç varlık **TD** olarak işaretlendi: `ALERT_RECIPIENT` (TD-03), `REFRESH_TOKEN` (TD-04), `CONSENT_RECORD` (TD-22, Genişletilmiş).
- `DEVICE` varlığı domain-model'de alanları olmadan geçiyordu; alanları ERD'de TD olarak önerildi.
- Toplanma noktası: Çekirdekte grup alanları, Genişletilmiş'te ayrı varlık (domain-model §5A ile uyumlu).
- R2+ varlıkları ayrı bir "bağlanma noktaları" diyagramında, oluşturulmayacakları notuyla gösterildi.

## 6. Bulgular: eksik veya çelişkili noktalar

| ID | Bulgu | Tür | Çözüm | Durum |
|---|---|---|---|---|
| DR-01 | **Tam ekran alarm kapsamı.** Talepteki "Android cihazda tam ekran alarm akışı başlar" adımı her durumu kapsıyor gibi okunuyor. Kararlara göre (K-13, RT-03, US-42 R3) MVP'de tam ekran yalnızca uygulama ön plandayken kendiliğinden açılır; arka planda veya kilit ekranında yüksek önemli bildirim çıkar ve dokununca tam ekran açılır | Kapsam netleştirmesi | Tasarım mevcut kararlara göre yapıldı. Kilit ekranında kendiliğinden tam ekran isteniyorsa US-42'nin MVP'ye alınması gerekir; bu bir karar değişikliğidir | Kapandı: mevcut davranış korundu, MVP'ye eklenmedi |
| DR-02 | Operasyona katılım `domain-model.md`'de modellenmemişti | Eksik | TD-01: `ROLE_ASSIGNMENT(GON, OPERATION)` ve operasyon daveti | Kapandı (TD kabul edildi) |
| DR-03 | Alarm yanıtı, yanıt seçeneği ve hatırlatma sayacını tutacak varlık yoktu | Eksik | TD-03: `ALERT_RECIPIENT` | Kapandı (TD kabul edildi) |
| DR-04 | Oturum iptali için yenileme belirteci varlığı ve belirteç süreleri tanımsızdı | Eksik | TD-04 (süreler varsayım) | Kapandı (TD kabul edildi) |
| DR-05 | Alarm durum modelindeki "Kapatıldı" geçişini kimin ve ne zaman yaptığı tanımsızdı | Belirsizlik | TD-06 | Kapandı (TD kabul edildi) |
| DR-06 | Alarm yanıtının değiştirilip değiştirilemeyeceği tanımsızdı | Belirsizlik | TD-07: değiştirilebilir, son yanıt geçerli | Kapandı (TD kabul edildi) |
| DR-07 | US-25 başlığında "düzeltme" var ama kabul kriteri yok | Belirsizlik | TD-09: iptal + bağlı yeni alarm | Kapandı (TD kabul edildi) |
| DR-08 | Tasarım talebindeki web ekran listesinde **Duyurular** yoktu, oysa FR-06 Çekirdek | Eksik | WEB-07 eklendi (TD-12) | Kapandı (TD kabul edildi) |
| DR-09 | `domain-model.md`'de bayat ifadeler vardı: K-31 "açık soru" olarak geçiyordu; SMS "R5" yazıyordu (karar R3+); toplanma noktası ilişkisinin halkası belirtilmemişti | Tutarsızlık | Düzeltildi; karar içeriği değişmedi | Kapandı |
| DR-10 | Aydınlatma onayı için varlık tanımsızdı | Eksik | TD-22 (Genişletilmiş) | Kapandı (TD kabul edildi) |
| DR-11 | TEK'in web panelinde neyi göreceği belirsizdi | Belirsizlik | TD-14 | Kapandı (TD kabul edildi) |
| DR-12 | KOR rolündeki biri sahadan Android ile alarm gönderemez (MFA yalnızca web'de, K-19; Android oturumu kurumsal izin taşımaz) | Bilinçli sınır | Kararlarla tutarlı; KOR alarmı web panelinden gönderir. Sahadan gönderim gerekiyorsa ayrı karar gerekir | Kapandı: ADR-013 |
| DR-13 | Yeni sayısal varsayımlar: erişim belirteci 15 dk, yenileme belirteci 12 sa / 30 gün, FCM TTL 60 dk, zamanlayıcı tarama 30 sn | Varsayım | TD-04, TD-08 ve emergency-alert-design §4'te etiketli | Kapandı (TD kabul edildi) |
| DR-14 | Bildirimden alarm yanıtı (TD-21) ve ön plan servisiyle arka plan konumu (ADR-011) doğrulanmamış teknik varsayımlardır | Teknik belirsizlik | TV-01 ve TV-06 (Hafta 2), başarısızlıkta alternatifler tanımlı | Kapandı |
| DR-15 | Davet bağlantısının uygulamayı açması (özel URI şeması) cihazdaki e-posta uygulamasına bağlıdır | Teknik belirsizlik | https davet bağlantısı + statik davet sayfası + elle girilen kod (TD-02, güvenli alternatif) | Kapandı (TD kabul edildi) |
| DR-16 | `domain-model.md`'de ALERT ↔ NOTIFICATION ilişkisi 1:1 idi; iptal ve kapanış ayrı bildirimdir | Kardinalite hatası | 1:N'e düzeltildi; hatırlatmalar `DELIVERY.attempt` | Kapandı |
| DR-17 | Notification varlığında duyuru metnini tutacak içerik alanı yoktu | Eksik | `title`, `body`, `kind` eklendi | Kapandı |

## 7. Tasarım kararlarının durumu

TD-01 – TD-22 **kabul edildi** (`system-architecture.md` §9, işlendikleri yerler §9.1). TD-02 teknik gerekçeyle güvenli alternatifle uygulandı. Onay bekleyen tasarım kararı yoktur. Bir sonraki aşamanın girdileri: `api-and-data-design-basis.md`.
