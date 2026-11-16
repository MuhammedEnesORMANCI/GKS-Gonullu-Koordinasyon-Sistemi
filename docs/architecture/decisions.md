# Mimari Karar Kayıtları (ADR)

> **Durum (14.11.2026):** ADR-001 – ADR-013 **Kabul edildi.** Tasarım kararları TD-01 – TD-22 kabul edildi (`design/system-architecture.md` §9). Proje sahibi tüm önerileri onayladı. Teknoloji yığını (ADR-008) VS-09 varsayımına dayanır: ekibin dil bilgisi Hafta 1'de doğrulanır, uymazsa ADR-008'deki B planı uygulanır. Gereksinim kararları: `docs/analysis/decision-log.md`.

| ADR | Konu | Durum | Sürüm |
|---|---|---|---|
| ADR-001 | Modüler monolit | Kabul edildi | MVP |
| ADR-002 | Merkezi, izin tabanlı, katmanlı yetkilendirme | Kabul edildi | MVP |
| ADR-003 | Grup ile konuşma ayrı varlıklar | Kabul edildi | R2 (MVP'de etkisi yok) |
| ADR-004 | Tek bildirim hattı, öncelik sınıfları, outbox | Kabul edildi | MVP |
| ADR-005 | Konu tabanlı gerçek zamanlı yayın; doğruluk kaynağı REST | Kabul edildi | MVP |
| ADR-006 | Ses ve görüntü için hazır WebRTC SFU | Kabul edildi (ürün seçimi R4'e ertelendi) | R4 |
| ADR-007 | Ekler için nesne depolama ve imzalı URL | Kabul edildi | R3 |
| ADR-008 | Teknoloji yığını | Kabul edildi (VS-09) | MVP |
| ADR-009 | MVP gerçek zamanlı taşıma: SSE | Kabul edildi | MVP |
| ADR-010 | OpenAPI sözleşmesi tek kaynak | Kabul edildi | MVP |
| ADR-011 | Android konum: kullanıcının başlattığı ön plan servisi | Kabul edildi (Hafta 2 keşfiyle doğrulanır) | MVP |
| ADR-012 | Kimlik doğrulama: kısa ömürlü erişim + iptal edilebilir yenileme belirteci, TOTP | Kabul edildi | MVP |
| ADR-013 | Android'den kurumsal işlem yapılmaz; kurumsal işlemler yalnızca MFA'lı web oturumunda | **Yerine geçildi (ADR-014, 15.11.2026)** | MVP |
| ADR-014 | Android yönetim oturumu: ADM/KOR için TOTP ile açılan, 12 sa mutlak, MFA'lı Android oturumu | Kabul edildi (proje sahibi isteği, 15.11.2026) | MVP |
| ADR-015 | ADM, KOR ataması olmadan tüm operasyonlarda KOR'un operasyon yönetimi izinlerine sahiptir (K-07'nin ilgili kısmının yerine) | Kabul edildi (proje sahibi isteği, 16.11.2026) | MVP |
| ADR-016 | Denetim kaydı aynı işlemde eşzamanlı yazılır; yalnızca ekleme; `actor_basis` | Kabul edildi (proje sahibi kararı, 16.11.2026) | MVP |
| ADR-017 | Gerçek zamanlı akış: tek örnek içi SSE, işlem sonrası yayın, bağlantı başına yetki | Kabul edildi (16.11.2026; proje sahibinin SSE isteği ve bağımlılık kuralı) | MVP |

---

## ADR-001 Modüler monolit

- **Bağlam:** Küçük bir ekip, MVP süresi kısıtlı, ileride mesajlaşma ve medya gibi farklı ölçek profiline sahip bileşenler gelecek.
- **Karar:** Tek dağıtılabilir uygulama sunucusu ve sınırları net modüller. Arka plan işçileri aynı uygulama içinde ayrı iş parçacığı havuzlarıyla çalışır; gerektiğinde ayrı süreç olarak başlatılabilir. Gerçek zamanlı geçit ve medya (SFU) gerektiğinde ayrı bileşen olur.
- **Sonuçlar:** (+) Düşük işletim yükü, tek veritabanı işlemi ile outbox. (−) Modül sınırlarına uyulmazsa bağımlılıklar karışır. Önlem: modül sınırları Spring Modulith doğrulama testleriyle CI'da denetlenir (ADR-008).
- **Yeniden değerlendir:** Hacim K-02'deki başlangıç kapasitesini (500 kullanıcı, 5 operasyon) belirgin biçimde aşarsa veya ekip büyürse. K-02 ve K-05 bu kararın gerekçesini güçlendiriyor.

## ADR-002 Merkezi, izin tabanlı ve katmanlı yetkilendirme

- **Bağlam:** NFR-02 (sunucu tarafı denetim). Gelecekte kullanıcıların grup kurabilmesi ve grup içi roller gelecek.
- **Karar:** Tüm kontroller `can(subject, action, resource)` ile yapılır. İzinler, kurum rol ataması (kapsamlı), grup üyelik rolü, grup politikası ve kaynak kuralları katmanlarından türer. **Kritik izinler (alarm, konum, kurum geneli duyuru, rol atama, denetim) yalnızca kurum rolünden gelir** (K-09).
- **Sonuçlar:** (+) Yeni özellik = yeni izin. Yetki testleri tek kaynaktan (yetki matrisi dosyası) üretilir. (−) MVP için biraz daha soyut; ancak statik eşleme tablosu yeterli, ek kütüphane gerekmez.
- **Reddedilen seçenek:** Rol adlarının uç noktalarda doğrudan kontrolü (`if role == COORDINATOR`). Grup rolleri geldiğinde her uç noktanın yeniden yazılmasını gerektirir.

## ADR-003 Grup ile konuşma ayrı varlıklar

- **Bağlam:** Grup içi mesajlaşma (R2, K-22), ileride bire bir sohbet ve görüşmeler.
- **Karar:** `Group` üyelik birimidir, `Conversation` iletişim kanalıdır. Grup konuşmasının üyeleri grup üyeliğinden türetilir. Görüşmeler konuşmaya bağlanır.
- **Sonuçlar:** (+) Yeni konuşma türleri grup modelini bozmadan eklenir. (−) Bir dolaylı katman daha.
- **MVP etkisi:** Yok. `Conversation` R2'de oluşturulur.

## ADR-004 Tek bildirim hattı, öncelik sınıfları ve outbox

- **Bağlam:** Alarm, görev, duyuru ve ileride sohbet/görüşme bildirimleri. Alarm sohbet hacminden etkilenmemeli.
- **Karar:** Tüm modüller bildirimleri `notifications` modülüne istek olarak bırakır. Hedef kitle anlık görüntüsü ve alıcı bazlı teslim kaydı tutulur. `CRITICAL` öncelik ayrı kuyruk ve ayrı işçi havuzunda işlenir; kullanıcı tercihleriyle kapatılamaz. Olaylar outbox ile yazılır.
- **Uygulama (ADR-008):** Outbox için Spring Modulith olay yayın kaydı (event publication registry). Bildirim teslim kuyruğu PostgreSQL tablosu üzerinde `SELECT … FOR UPDATE SKIP LOCKED` ile çalışan işçiler. Ayrı mesaj aracısı (Redis, RabbitMQ, Kafka) MVP'de yok (K-02).
- **Sonuçlar:** (+) FR-18 tek yerde çözülür; yeni kanal ve kategori kolay eklenir. (−) Alarm akışı bir kuyruk adımından geçer; gecikme hedefi (≤ 5 sn) yük testinde ölçülür.

## ADR-005 Konu tabanlı gerçek zamanlı yayın; doğruluk kaynağı REST

- **Bağlam:** Koordinatör paneli anlık güncellenmeli (MVP). Sohbet (R2) ve görüşme sinyalleşmesi (R4) gelecek.
- **Karar:** Modüller konu olayları yayınlar; abonelikte yetki denetlenir. Kaçırılan olaylar REST imleçleriyle telafi edilir. Mobilde alarm için birincil yol push'tur (FCM).
- **Taşıma:** MVP'de SSE (ADR-009); R2'de sohbetle birlikte WebSocket. Konu modeli değişmez.

## ADR-006 Ses ve görüntü için hazır WebRTC SFU *(R4)*

- **Karar:** Medya işleme kendimiz yazılmaz. SFU ve TURN kullanılır. Backend yalnızca oturum, yetki ve belirteç yönetir. Kayıt yapılmaz (K-33). Ürün seçimi (LiveKit, mediasoup, Jitsi, yönetilen servis) R4 planlamasında yapılır (Ertelendi).
- **MVP etkisi:** Android'de yerel Kotlin seçimi (ADR-008) WebRTC SDK'larıyla uyumludur; ileride bu yolu kapatmaz.

## ADR-007 Ekler için nesne depolama ve imzalı URL *(R3)*

- **Karar:** Dosyalar doğrudan S3 uyumlu depolamaya yüklenir; indirme kısa ömürlü imzalı URL ile yapılır. Tarama ve EXIF/GPS temizliği zorunlu. Veritabanında yalnızca meta veri tutulur. Demo ortamında S3 uyumlu yerel depolama (ör. MinIO) Docker Compose'a eklenebilir.
- **MVP etkisi:** Yok.

## ADR-008 Teknoloji yığını

- **Bağlam:** K-03 (Android + Web), K-05 (4 kişi, bir dönem; backend, mobil, web, test yetkinlikleri), K-02 (500 kullanıcı / 5 operasyon), K-01/K-04 (prototip, sentetik veri). Ölçütler: öğrenme maliyeti düşük, iyi belgelenmiş, birlikte çalışabilir, ADR-001 – ADR-005'i doğrudan destekleyen teknolojiler.
- **Karar:**

| Katman | Seçim | Gerekçe |
|---|---|---|
| Backend dili | **Java 21 (LTS)** | En yaygın öğretilen ve belgelenen sunucu dillerinden biri. Kotlin (Android) ile aynı JVM ekosistemi ve Gradle derleme aracı; tipler, kütüphaneler ve hata ayıklama alışkanlıkları ortak |
| Backend çatısı | **Spring Boot 4.1.x** (Spring Modulith 2.1.x, Gradle 9.8; 15.11.2026'da 3.x yerine seçildi: 3.x açık kaynak desteği bitti ve Spring Initializr artık sunmuyor) + Spring Web, Spring Security, Spring Data JPA, Bean Validation | Olgun yetkilendirme altyapısı (NFR-02), geniş belge ve örnek tabanı |
| Modüler monolit | **Spring Modulith** | ADR-001'deki modül sınırlarını testle doğrular; olay yayın kaydı ADR-004'teki outbox'ı hazır sağlar |
| Veritabanı | **PostgreSQL 16** | İlişkisel model, `SKIP LOCKED` ile kuyruk, Testcontainers desteği. PostGIS gerekmez (yalnızca son konum, K-16) |
| Şema geçişi | **Flyway** | Sürümlü ve tekrar üretilebilir şema |
| API sözleşmesi | **OpenAPI 3** (springdoc-openapi) | ADR-010 |
| Push | **Firebase Cloud Messaging** (Firebase Admin SDK, Java) | K-18; Android için standart, ücretsiz katman yeterli |
| Gerçek zamanlı | **SSE** (Spring MVC `SseEmitter`) | ADR-009 |
| Kimlik doğrulama | Spring Security; JWT erişim belirteci + veritabanında iptal edilebilir yenileme belirteci; TOTP (RFC 6238) | ADR-012, K-19, FR-22 |
| E-posta (davet) | Spring Mail; geliştirme ve demoda **Mailpit** (posta yakalayıcı) | K-06; sentetik kullanıcılar gerçek e-posta almaz (K-04) |
| Android | **Kotlin + Jetpack Compose (Material 3)**, Retrofit + OkHttp + kotlinx.serialization, WorkManager, Fused Location Provider, FCM | Alarm (bildirim kanalı, tam ekran bildirim) ve konumda (ön plan servisi) yerel API'ye en az riskle erişim (RT-01, RT-02). Resmî Android dokümantasyonu bu yığını esas alıyor |
| Android harita | **MapLibre Native Android + OpenStreetMap raster döşemeleri** (alternatif: osmdroid) | API anahtarı ve faturalandırma hesabı gerektirmez. Hafta 2 keşfinde doğrulanır |
| Web | **TypeScript + React + Vite**, MUI (Material bileşenleri), TanStack Query, React Router | En geniş belge ve topluluk desteği. MUI, Android'deki Material 3 ile görsel tutarlılık sağlar |
| Web harita | **Leaflet** (react-leaflet) + OpenStreetMap döşemeleri | Basit, ücretsiz, API anahtarı yok. Düşük hacimli prototip kullanımı OSM döşeme politikasına uygun (RT-11) |
| Backend testi | **JUnit 5**, Spring Boot Test, **Testcontainers (PostgreSQL)**, Spring Modulith modül testleri, MockMvc | Gerçek veritabanıyla entegrasyon testi; yetki matrisi dosyasından parametreli negatif testler (US-28) |
| Web testi | **Vitest** + React Testing Library; uçtan uca **Playwright** | Vite ile yerleşik uyum; Playwright tarayıcı matrisini (VS-05) kapsar |
| Android testi | JUnit, Compose UI test; VS-05 cihaz matrisinde manuel test protokolü | Alarm davranışı otomatik test edilemeyen durumlar içerir (sessiz mod, DND) |
| Yük testi | **k6** | Betik tabanlı, hafif; 500 kullanıcı / 5 operasyon senaryosu (K-02) |
| Sentetik veri | **Datafaker** (Türkçe yerel ayarı), sabit tohumlu üretici | Tekrar üretilebilir veri seti (K-04) |
| Çalışma ortamı | **Docker Compose** (PostgreSQL, backend, web için nginx, Mailpit); demoda TLS için ters vekil (ör. Caddy) | Tek komutla kurulum (VS-11). NFR-01 |
| Sürüm kontrolü ve CI | Git + GitHub Actions *(varsayım: depo GitHub'da)* | Derleme, test, modül sınırı ve OpenAPI sözleşme kontrolü her değişiklikte |
| Android dağıtımı | İmzalı APK ve/veya Firebase App Distribution | VS-12; Google Play yayını yok |

- **Sürüm sabitleme:** Kütüphane sürümleri Hafta 1'de, o tarihteki kararlı sürümlerle sabitlenir ve dönem boyunca yalnızca güvenlik düzeltmesi için güncellenir.
- **Reddedilen seçenekler:**
  - *Node.js/NestJS backend:* Web ile aynı dil olurdu, ama Android yine Kotlin gerektirir. Spring Modulith'in modül doğrulama ve outbox desteği karşılığı daha az olgun. → **B planı**: ekip TypeScript'te belirgin biçimde daha yetkinse (VS-09) NestJS + PostgreSQL kullanılır; ADR-001 – ADR-005 ve OpenAPI sözleşmesi değişmez.
  - *Flutter veya React Native:* iOS ileride değerlendirileceği için (K-03) cazip, ancak alarm (tam ekran bildirim, kanal) ve ön plan servisi konumu yerel köprüler gerektirir. En yüksek teknik risk tam bu alanlarda (RT-01, RT-02) olduğu için yerel Kotlin seçildi. iOS kararı geldiğinde (VS-13) yeniden değerlendirilir.
  - *Firebase'i backend olarak kullanmak:* Kapsamlı rol modeli (ADR-002), denetim kaydı ve outbox gereksinimleri kural diliyle zor ifade edilir; satıcı bağımlılığı.
  - *Keycloak gibi ayrı kimlik sunucusu:* Öğrenme ve işletim maliyeti bir dönem için yüksek.
  - *Redis/RabbitMQ/Kafka:* 500 kullanıcıda gerekmez (K-02); PostgreSQL kuyruğu yeterli.
- **Sonuçlar:** (+) Ekip üç dil kullanır (Java, Kotlin, TypeScript), ama ikisi aynı ekosistemde. Tüm katmanlar OpenAPI sözleşmesiyle bağlanır. (−) Spring ekosisteminin öğrenme eğrisi; önlem: Hafta 1–3'te iskelet ve örnek modül.

## ADR-009 MVP gerçek zamanlı taşıma: SSE

- **Bağlam:** MVP'de gerçek zamanlı ihtiyaç yalnızca sunucudan web paneline tek yönlü güncellemedir (alarm ≤ 5 sn, görev ≤ 10 sn). Android arka planda bağlantı tutmaz; push kullanır (ADR-005).
- **Karar:** Web paneli için **Server-Sent Events.** Konu modeli (ADR-005) korunur: istemci operasyon/alarm konusuna abone olur, abonelikte `can()` çalışır, üyelik veya rol değişince akış sunucu tarafından kapatılır. Yeniden bağlanmada `Last-Event-ID` ve REST imleçleriyle kaçırılan olaylar alınır.
- **Sonuçlar:** (+) WebSocket'ten daha basit; HTTP altyapısı ve çerez tabanlı kimlik doğrulamayla doğrudan çalışır. (−) İstemciden sunucuya kanal yok; R2'de sohbet için WebSocket eklenir.
- **Yedek:** Kapı KP2'de sorun çıkarsa 5 sn'lik yoklama (`mvp-scope.md` §6).

## ADR-010 OpenAPI sözleşmesi tek kaynak

- **Karar:** Backend uç noktaları OpenAPI 3 sözleşmesiyle belgelenir. Web istemcisi bu sözleşmeden tip üretir. Android istemcisi aynı sözleşmeye göre yazılır (isteğe bağlı kod üretimi). CI, sözleşmedeki geriye uyumsuz değişiklikleri işaretler.
- **Gerekçe:** Üç farklı dil ve üç iş akışı arasında entegrasyon hatalarını erken yakalamak (K-05). NFR-14 belgeleme gereksinimini de karşılar.

## ADR-011 Android konum: kullanıcının başlattığı ön plan servisi

- **Bağlam:** K-14 (kullanıcı onayıyla paylaşım), K-16 (geçmiş yok), RT-02 (arka plan konum kısıtları), AK-12 (mağaza yayını yok).
- **Karar:** Gönüllü paylaşımı uygulama açıkken başlatır. Uygulama, kalıcı bildirimli ve `location` türünde bir **ön plan servisi** başlatır ve 60 sn'de bir konum gönderir (VS-18). "Kullanım sırasında" konum izni yeterlidir; ayrı "her zaman" (arka plan) izni istenmez. Paylaşım kullanıcı durdurunca, operasyon kapanınca (≤ 1 dk) veya oturum sonlanınca durur.
- **Doğrulama:** Hafta 2 teknik keşfi, VS-05 cihazlarında servisin uygulama arka plandayken çalışmaya devam ettiğini ve üretici pil yönetiminin etkisini ölçer.
- **Alternatif (keşif başarısızsa):** Konum yalnızca uygulama ön plandayken gönderilir; arka planda "güncel değil" etiketi devreye girer (FR-12). Bu, K-14 ile uyumludur ve kapsamı değiştirmez.

## ADR-012 Kimlik doğrulama

- **Karar:** Kısa ömürlü JWT erişim belirteci ve veritabanında saklanan, iptal edilebilir yenileme belirteci kullanılır. FR-22 (oturum iptali) yenileme belirteçlerinin iptaliyle sağlanır; erişim belirtecinin kısa ömrü iptalin hızla etkili olmasını sağlar. Web'de belirteçler HttpOnly, Secure çerezlerde taşınır (SSE ile uyumlu, CSRF koruması etkin). Android'de şifreli yerel depoda saklanır. ADM ve KOR hesapları web paneline girişte TOTP ikinci faktörü kullanır (K-19). Parolalar güçlü ve uyarlanabilir bir özet algoritmasıyla saklanır (ör. bcrypt veya Argon2).
- **Onaylı ayrıntılar (TD-04):** Erişim belirteci 15 dk. Yenileme belirteci web'de 12 saat, Android'de 30 gün; her kullanımda döndürülür. Kullanılmış bir yenileme belirteci tekrar gelirse aynı zincirdeki tüm belirteçler iptal edilir (çalınma tespiti). **Tolerans (LD-18):** halef hiç kullanılmamışsa, istek aynı istemci türünden geliyorsa ve önceki döndürmeden bu yana en fazla 10 sn geçtiyse zincir iptal edilmez, yeni halef verilir (ağ yarışları için). Web'de yenileme tek sekmede yapılır. **Web oturumu 12 saatte mutlak olarak sona erer**, kullanımla uzamaz (LD-19). Web'de ADM/KOR oturumu TOTP doğrulandığında `mfa_verified` işaretini taşır; kurumsal izinler bu işaret olmadan kullanılamaz (ADR-013).
- **Reddedilen:** Ayrı kimlik sunucusu (ADR-008), SMS OTP (K-20).


## ADR-013 Android'den kurumsal işlem yapılmaz (DR-12)

- **Bağlam:** K-19 MFA'yı ADM ve KOR için yalnızca web panelinde zorunlu kılar. Android oturumu MFA'sızdır ve uzun ömürlüdür (30 gün, TD-04). Telefon kaybolabilir (RT-13). Acil alarm yanlış veya kötü niyetli gönderildiğinde etkisi yüksektir (RO-01, RO-02). Konum görüntüleme en riskli veri erişimidir (K-15, RT-07).
- **Karar:** Kurumsal izinler (`alert.*`, `task.manage`, `location.view`, `operation.*`, `invitation.create`, `role.assign`, `user.*` yönetimi, `audit.view`, `announcement.send.*`) yalnızca **web** oturumunda kullanılabilir; ADM veya KOR rolüne dayanan izinler için ayrıca `mfa_verified = true` gerekir (K-19). TEK ve UYM izinleri MFA gerektirmez (LD-01). Android uygulamasında bu işlemler için ekran veya eylem yoktur. Android'den gelen bu tür istekler sunucuda 403 ile reddedilir. KOR veya ADM'nin Android'deki oturumu yalnızca gönüllü/lider izinlerini kullanır.
- **MVP'deki yol:** Alarm web panelinden (WEB-09) gönderilir. Panel tarayıcı tabanlı olduğundan sahada telefon tarayıcısıyla da MFA'lı oturum açılabilir. MVP'de mobil tarayıcı yerleşimi optimize edilmez; WEB-09/WEB-10'un dar ekranda kullanılabilirliği kontrol edilir (TV-13).
- **Sonuçlar:** (+) MFA atlanamaz; kayıp telefonla alarm gönderilemez; yetki modeli tek kanalda denetlenir. (−) Sahadaki koordinatör alarm için web paneline ihtiyaç duyar (RO-13).
- **Sonraki sürüm seçenekleri (karar gerektirir, MVP'ye eklenmedi):**
  1. *Yükseltilmiş oturum (step-up MFA):* Android'de alarm işleminden hemen önce TOTP veya cihaz biyometrisiyle doğrulanan, kısa ömürlü (ör. 10 dk) ve tek işleme özel belirteç.
  2. *Cihaz bağlama + passkey/FIDO2:* KOR cihazını web'den MFA ile kaydeder; alarm işlemleri cihaz anahtarıyla imzalanır.
  3. *Talep ve onay:* Android'den yalnızca alarm *talebi* oluşturulur; gönderim web'deki MFA'lı onayla yapılır.
- **Yeniden değerlendir:** Saha kullanımında web paneli yetersiz kalırsa veya R2 planlamasında.

## ADR-014 Android yönetim oturumu (ADR-013'ün yerine geçer)

- **Bağlam:** Proje sahibi ADM ve KOR'un yönetim işlemlerini bilgisayar olmadan telefondan yapabilmesini istedi (15.11.2026). ADR-013 ve DR-12 kurumsal işlemleri MFA'lı web oturumuyla sınırlıyordu; gerekçeleri (1) Android'de MFA olmaması ve (2) uzun ömürlü (30 gün kayan) mobil oturumda cihaz kaybı riskiydi. DR-12 step-up MFA ve cihaz bağlamayı gelecek seçenek olarak not etmişti.
- **Karar:**
  1. **Yönetim oturumu = MFA doğrulanmış oturum.** ADM veya KOR rolü olan kullanıcı Android'de de web ile aynı TOTP akışından geçer (ID-02 → ID-03/ID-04). MFA bileti açılacak oturumun istemci türünü ve kurulum kimliğini taşır (V11). Bu kullanıcılara MFA'sız Android oturumu verilmez.
  2. **Ömür:** MFA'lı her oturum (web ve Android) **12 sa mutlaktır**, döndürmeyle uzamaz (LD-19). Yalnızca MFA'sız Android (gönüllü) oturumu 30 gün kayar. Veritabanı kısıtı `ck_refresh_tokens_mfa_absolute` hiçbir MFA'lı zincirin 12 saati aşmamasını zorunlu kılar (eski `ck_refresh_tokens_mfa_web_only` kaldırıldı).
  3. **Kapı (`Grants.corporate`):** Web kuralı değişmez (ADM/KOR MFA ister, TEK/UYM istemez). Android'de kurumsal izin **yalnızca MFA zorunlu rollerden (ADM/KOR) ve yalnızca `mfaVerified` oturumda** türer. TEK/UYM'nin MFA'sız izinleri mobile taşınmaz.
  4. **Uç kapısı:** `requireWeb()` yerine `requireManagementSession()` (web veya MFA'lı Android). Yetki yine her istekte `can()` ile rol, kapsam ve kaynak kuralına göre verilir; web ve Android aynı uçları ve kuralları kullanır.
  5. **İstemci önlemleri (Android):** belirteçler Keystore'da şifreli (mevcut); ekran kilidi olmayan cihazda yönetim (MFA) girişi yapılmaz; TOTP kurulumunda kimlik doğrulayıcının aynı telefonda olmasının ikinci faktörün bağımsızlığını azalttığı uyarılır.
- **Sonuçlar:** (+) ADM/KOR telefondan tam yönetim yapar; web güvenliği zayıflamaz; uzun ömürlü oturum hiçbir zaman kurumsal izin taşımaz. (−) Telefon hem parolayı (kayıtlı), hem oturumu, hem de (aynı cihazdaysa) TOTP'yi taşıyabilir; bu risk 12 sa sınırı, sunucu tarafı her istek iptal denetimi (LD-47) ve ekran kilidi şartıyla azaltılır, tamamen kaldırılmaz (kabul edilen risk). KOR+GON kullanıcısı Android'de günde bir TOTP girer.
- **Değişen kayıtlar:** ADR-013 (yerine geçildi), DR-12 (alarm gönderimi artık MFA'lı Android oturumunda da mümkün; alarm modülü yazıldığında), authorization.md §5.4 istemci kuralı, backend-api-design.md §2.4, mvp-scope.md satır 15. Ayrıntı: TASKS.md LD-61.

## ADR-015 ADM tüm operasyonlarda yönetim yetkisi (K-07'nin "ADM kendini KOR atar" kuralının yerine geçer)

- **Bağlam:** K-07 operasyonel işlemleri yalnızca o operasyonun KOR'una veriyordu; ADM bunlar için kendini kayıt altında KOR olarak atamak zorundaydı. Kodda bu, ADM'nin grup, üyelik, görev ve operasyon duyurusunda "salt-okur" kalması demekti (`RoleBasedGroupAccess`, `RoleBasedTaskAccess`, `RoleBasedAnnouncementAccess`). Proje sahibi ADM'nin ana sistem yöneticisi olarak tüm operasyonları KOR ataması olmadan yönetebilmesini istedi (16.11.2026).
- **Karar:**
  1. **Tek kural, tek yer:** `access/Grants.coordinates(operationId)` = o operasyonun KOR'u **veya** ADM. Operasyon kapsamlı yönetim kararları `Grants.coordinator(subject, grants, operationId)` ile verilir; controller'lara veya diğer modüllere rol kontrolü eklenmez.
  2. **Kapsanan izinler (ADM, her operasyon):** `operation.manage` (zaten vardı), `invitation.create` (zaten vardı), `task.manage`, `group.create.operational`, `group.view`, `group.update`, `membership.add`, `membership.remove`, `membership.role.update`, `meeting_point.update`, `announcement.send.group`. Koordinatör ekleme/kaldırma (`role.assign`) zaten yalnızca ADM'dedir.
  3. **Kapsam dışı (daha az kapsamlı seçenek):** `location.view` (K-15: konumu yalnızca KOR görür) ve `alert.send/cancel/close/resend`. Bu modüller henüz yok ve karar proje sahibine aittir; ADM'nin etkin izinlerinde de görünmezler (`EffectivePermissions.ADM_OPERATION`).
  4. **Değişmeyenler:** operasyon durum geçişleri ve kaynak kuralları (ör. KOR duyurusu için operasyon `ACTIVE`, N-19); `Grants.corporate` istemci/MFA kapısı (ADM için MFA zorunlu; Android'de yalnızca MFA'lı yönetim oturumu, ADR-014); `requireManagementSession`, `requireAndroid`; 12 sa mutlak oturum kısıtı (`ck_refresh_tokens_mfa_absolute`). Şema değişikliği yok (V12 eklenmedi).
  5. **İstemci görünürlüğü:** `GET /me/context` → `permissions.operations` ADM için kurumdaki **tüm operasyonlar** (kapanmışlar dahil) için `ADM_OPERATION` setini döndürür (`AccessDirectory.Scopes.operationIds()`, organization modülü). Web ve Android menüleri sözleşme değişmeden açılır.
- **Güvenlik notu:** ADM hesabının ele geçirilmesinin etkisi büyür: artık tek bir ADM oturumu her operasyonda görev, grup ve duyuru işlemi yapabilir. Azaltımlar: ADM için TOTP zorunlu, MFA'lı oturum 12 sa mutlak, her istekte rol ve oturum iptali veritabanından denetlenir (LD-47, LD-55). **Audit modülü yazıldığında ADM'nin KOR ataması olmadan yaptığı her operasyon işlemi (görev, grup/üyelik, toplanma noktası, duyuru, koordinatör ve davet işlemleri) mutlaka denetim kaydına yazılmalıdır**; kayıtta işlemi yapanın o operasyonda KOR olmadığı ("ADM yetkisiyle") ayırt edilebilmelidir.
- **Sonuçlar:** (+) ADM kendini KOR atamadan operasyona müdahale eder; K-07'deki "kendini KOR ata" adımı kalkar. (−) Denetim kaydı henüz olmadığı için ADM işlemleri şimdilik yalnızca kaynak tablolarındaki `created_by`/`added_by` alanlarından izlenebilir. (−) ADM'nin `/me/context` yanıtı operasyon sayısıyla büyür.
- **Değişen kayıtlar:** K-07 (ADM için "kendini KOR ata" kuralı kalktı; konum/alarm için K-07 ve K-15 aynen geçerli), authorization.md §5.1/§5.4, TASKS.md LD-62. Testler: `AccessPolicyTests.AdminCoordinatesAllOperations`, `MobileManagementSessionTests.adminWithoutCoordinatorRoleCreatesTasksAndSendsOperationAnnouncements`, `coordinatorSendsAnnouncementFromAndroidOnlyInOwnOperation`.

## ADR-016 Denetim kaydı: aynı işlemde eşzamanlı yazım, yalnızca ekleme, yetki dayanağı

- **Bağlam:** K-26 ve NFR-05 denetim kaydını zorunlu kılar; tasarım (backend §12.5) outbox üzerinden zaman uyumsuz yazımı öngörüyordu. ADR-015 ile ADM'nin KOR ataması olmadan yaptığı operasyon işlemlerinin ayırt edilerek kaydedilmesi gerekti. Proje sahibi aynı işlemde, eşzamanlı ve yeni bağımlılıksız yazımı seçti (16.11.2026).
- **Karar:**
  1. Üretici modül iş işleminin içinde `org.gks.backend.AuditTrail.record(...)` çağırır; bu, ortak çekirdekteki `AuditEvent` olayını yayınlar. audit modülü `@EventListener` + `@Transactional(MANDATORY)` ile **aynı işlemde** `audit.audit_entries` tablosuna yazar. Yazma başarısız olursa iş işlemi geri alınır; işlem dışında yayınlanan olay hata verir. Outbox ve yeni bağımlılık yok.
  2. Olay tipi ortak çekirdekte olduğundan üreticiler audit'e, audit üreticilere bağımlı değildir (ModularityTests). Okuma ucu (AU-01) yetkiyi access'ten alır (`AuditAccess`: ADM MFA'lı, UYM web).
  3. `actor_basis` kolonu: `KOR`, `ADM` (ADR-015: KOR ataması olmadan), `TEK`, `UYM`, `LEADER`, `SELF`, `SYSTEM`. Değer access'teki `ActingBasis` ile, karar kuralıyla aynı mantıkla hesaplanır.
  4. **Yalnızca ekleme:** uygulamada güncelleme/silme yolu yoktur; V12 tetikleyicileri `UPDATE`, `DELETE` ve `TRUNCATE`'i reddeder. (Ayrı, yalnızca `SELECT/INSERT` yetkili veritabanı rolü henüz yok; tek uygulama kullanıcısı tablo sahibi olduğu için tetikleyici devre dışı bırakılabilir — bu, ayrı DB rolüyle kapatılacak açık konudur.)
  5. Kayıt içeriği: mesaj başlığı/metni, parola, belirteç, e-posta, adres ve koordinat yazılmaz (AC-27.2); yalnızca tür, kimlik ve sayılar.
- **Sonuçlar:** (+) kayıt kaybı veya kayıtsız işlem yok; (+) ADM işlemleri `actor_basis = 'ADM'` ile süzülür; (−) denetim yazımı iş işleminin süresine eklenir (tek INSERT); (−) id (UUIDv7) sırası olay sırası kabul edilir, `at` ayrı tutulur.
- **Kapsanan eylemler (V12, 16.11):** `ROLE_ASSIGNED/REVOKED`, `ACCOUNT_DISABLED/ENABLED`, `SESSIONS_REVOKED`, `PASSWORD_RESET_BY_ADMIN`, `MFA_RESET`, `INVITATION_CREATED/REVOKED`, `OPERATION_CREATED/UPDATED/STATE_CHANGED`, `GROUP_CREATED/UPDATED/MEMBER_ADDED/MEMBER_REMOVED/LEADER_CHANGED`, `MEETING_POINT_UPDATED`, `TASK_CREATED/UPDATED/PUBLISHED/STATE_CHANGED`, `ASSIGNMENT_ADDED/REMOVED`, `ANNOUNCEMENT_SENT` (LD-60 (7)). Kimlik olayları (giriş, çıkış, MFA doğrulama) ve gönüllü yanıtları henüz yok.

## ADR-017 Gerçek zamanlı akış: tek örnek içi SSE, işlem sonrası yayın, bağlantı başına yetki

- **Bağlam:** ADR-009 web için SSE'yi seçti; tasarım (§12.2) olayların outbox'tan geçmesini öngörüyordu. Outbox henüz yok ve yeni bağımlılık istenmiyor (16.11.2026). Gelen kutusu ve duyuruların canlı yenilenmesi istendi.
- **Karar:**
  1. Uç `GET /api/v1/stream` (RT-01), Spring MVC `SseEmitter` (ek bağımlılık yok). Kimlik ve oturum iptali güvenlik filtresinde; **her konunun yetkisi her bağlantı açılışında** access'teki `RealtimeAccess` ile verilir; biri yetkisizse akış açılmaz (`403`, kısmi abonelik yok). Bilinmeyen konu biçimi veya 10'dan fazla konu `400`.
  2. Konular: `user:{id}` (her bağlantıya kendiliğinden eklenir), `operation:{id}` (KOR veya ADR-015 ile ADM; kurumsal oturum kapısı), `organization` (ADM). `alert:{id}` ve konum olayları ilgili modüllerle eklenecek.
  3. Üretici modüller ortak çekirdekteki `RealtimeBus` ile olay yayınlar; realtime modülü `@TransactionalEventListener(AFTER_COMMIT)` ile **işlem tamamlandıktan sonra** gönderir (geri alınan işlemin olayı gitmez). Veri yalnızca kimlik, durum ve zaman (RT-K3).
  4. Yeniden oynatma: bellek içi arabellek (5 dk / 10 000 olay), kimlik `<epochMillis>-<sıra>`. `Last-Event-ID` (başlık) veya `lastEventId` (sorgu) arabellekte yoksa önce `resync`. `retry: 3000`, 15 sn'de bir `: ping`, akış en geç 30 dk'da bir kapanır.
  5. Yetki düşmesi: rol kaldırılınca (`PERMISSION_REVOKED`) ve operasyon kapanınca (`OPERATION_CLOSED`) etkilenen konu `subscription.revoked` ile akıştan çıkarılır; oturum iptali (çıkış, tüm oturumları kapatma, hesap devre dışı, parola/MFA sıfırlama) `session.revoked` gönderip akışı kapatır.
- **Sonuçlar:** (+) bağımlılıksız, testli; (+) olay iş işlemiyle tutarlı. (−) **tek örnek:** çoklu örnekte olaylar örnekler arası kanal (PostgreSQL `LISTEN/NOTIFY` veya outbox) gerektirir; (−) sunucu yeniden başlarsa arabellek boşalır, istemci `resync` alır; (−) olaylar commit eden iş parçacığında gönderilir (yavaş istemci o gönderimi bekletir; ayrı yürütücü sonraki iş).
- **Ek olaylar (tasarımda yoktu, N-21 kapsamında):** `inbox.changed` (`user:{id}`; `change`: `CREATED`/`READ`), `announcement.sent` (`operation:{id}` veya `organization`; `announcementId`, `recipientCount`).
