# Veritabanı Gözüyle Backend API Tasarımı İncelemesi

| Alan | Bilgi |
|---|---|
| Görev | T-04 |
| İnceleyen | `database-architect` |
| İncelenen | `backend-api-design.md` sürüm 0.9 (taslak, 14.11.2026), özellikle §2.2, §3.5 – §3.6, §5 – §12, §15 |
| Karşılaştırma tabanı | `database-physical-design.md` v1.1, `domain-model.md` §5B, `api-and-data-design-basis.md` §4, lider kararları LD-01 – LD-15 |
| İnceleme ölçütleri | (1) Veri bütünlüğü, (2) sorgu maliyeti, (3) sayfalama ↔ indeks uyumu, (4) işlem sınırları ve eşzamanlılık |
| Bu doküman ne değildir | Kod, SQL veya OpenAPI değildir. Kesinleşmiş kararları değiştirmez; karar gerektiren bulgular team-lead'e işaretlenmiştir |

**Ciddiyet tanımı.** **Yüksek:** olduğu gibi uygulanırsa veri bozulur, güvenlik kuralı çiğnenir veya kesinleşmiş bir kararla çelişir. **Orta:** doğruluk kenar durumda bozulur, belirgin gereksiz maliyet çıkar veya iki doküman uyumsuzdur. **Düşük:** netleştirme, belgeleme veya küçük verim iyileştirmesi.

## 1. Özet

Backend tasarımı veri tarafıyla büyük ölçüde uyumludur. Kimlik/tip eşlemesi, enum adları, idempotency tablosu, oturum (`sid`) ve kısıt adlandırma kuralı T-03'te mutabakata bağlandı (`database-physical-design.md` §12). Kapasite kilidi, tek lider kilidi ve koşullu durum geçişleri doğru kurgulanmış.

| Ciddiyet | Sayı | Bulgular |
|---|---|---|
| Yüksek | 4 | B-01 yeniden gönderimde teslim tekilliği, B-02 idempotency tablosunda belirteç saklanması, B-03 LD-05 ile çelişen denetim testi, B-04 kapasite-dolu yolunda idempotency işlem sınırı |
| Orta | 10 | B-05 – B-14 |
| Düşük | 10 | B-15 – B-24 |

En önemli dört bulgu backend-architect'e ayrıca iletildi.

---

## 2. Bulgular

### 2.1 Yüksek

#### B-01 — Yeniden gönderim teslim tekilliğini bozuyor (AL-10)

| | |
|---|---|
| Yer | §11.1 "Yeniden gönderim (AL-10)": "Seçilenler `QUEUED`, `reminder_count=0`, yeni `DELIVERY` attempt 0 (aynı bildirim kaydı)" |
| Sorun | `domain-model.md` §5B: "Aynı (bildirim, kullanıcı, kanal, cihaz, deneme) için tek Delivery". Aynı bildirim kaydında `attempt = 0` ikinci kez kullanılırsa benzersizlik ihlal edilir. İhlal sessizce yok sayılırsa (`ON CONFLICT DO NOTHING`) **yeniden gönderim hiç gitmez**; reddedilirse AL-10 `500` döner. Sonraki hatırlatmalar da `attempt` 1 – 5'te aynı çakışmayı yaşar |
| Öneri | `deliveries.round` kolonu (0 = ilk tur, n = n'inci elle yeniden gönderim; değeri `alert_recipients.resend_count`). Tekillik `(notification_id, user_id, device_id, round, attempt)`. API değişmez. DB tasarımına işlendi (§6.6, AN-04) |
| Durum | **Çözüldü (LD-43, 15.11.2026).** `round` önerisi uygulandı; ayrıca hatırlatma zamanlayıcısı `round` değerini kilitli alıcı satırının `resend_count`'undan yazar (öneri metninde açık kalan nokta). Backend §11, DB §7.1/E-13, domain modeli §5B, ERD |

#### B-02 — Davet kabulü yanıtı belirteçleriyle idempotency tablosunda saklanıyor (ID-16)

| | |
|---|---|
| Yer | §3.6 "İlk başarılı yanıt (durum kodu + gövde) aynen döner"; ID-16 `201 LoginResponse` (**Android erişim ve yenileme belirteçleri gövdede**); `Idempotency-Key` zorunlu |
| Sorun | Yanıt gövdesi `platform.idempotency_keys.response_body`'ye düz metin yazılır. Bu, **30 günlük yenileme belirtecinin açık hâliyle veritabanında ve günlük yedeklerde** saklanması demektir. `api-and-data-design-basis.md` §4.2 ve ADR-012: belirteçler yalnızca özet olarak saklanır. Aynı risk `POST /auth/*` uçlarından biri idempotency desteklerse (§3.6 "diğer tüm POST uçları başlığı kabul eder") de doğar |
| Öneri | (1) Belirteç veya sır içeren yanıtlar (`LoginResponse`, `TokenResponse`, `MfaEnrollResponse`) **hiçbir zaman** saklanmaz; `/auth/*` uçları idempotency "desteklenen" kümesinden çıkarılır. (2) ID-16 için tekrar davranışı: saklanan kayıt belirteçsiz özet tutar (`resource_id` = yeni kullanıcı); tekrar gelen istek `200` + `mfaRequired=false`, belirteç alanları boş ve "giriş yapın" işaretiyle döner (yeni belirteç **verilmez**). Alternatif: tekrar isteğinde aynı oturum zincirinde yeni çift üretmek; bu, davet kodu ve anahtarı ele geçirene oturum verir, önerilmez. Seçim backend'indir, ilke (sır saklanmaz) değişmez |
| Durum | **Çözüldü (LD-42, 15.11.2026).** Seçenek (2)'nin "yanıt tekrar edilmez, belirteç verilmez" ilkesi `409 ALREADY_COMPLETED` ile uygulandı (`200` + boş belirteç alanı `LoginResponse` şemasını bozacağı için seçilmedi). Ayrıca istek özeti HMAC-SHA-256'ya çevrildi (gövdede parola var) ve `body_redacted` kolonu eklendi. Backend §3.6/§15.4, DB §8.3 |

#### B-03 — Denetim testi ve SSE notu LD-05 ile çelişiyor

| | |
|---|---|
| Yer | §12.2 "Konum ve denetim etkileşimi": "her çağrı `LOCATION_VIEWED` denetim kaydı üretir"; §13.4 T-LOC-03 "Her LC-05 çağrısı bir `LOCATION_VIEWED` kaydı" |
| Sorun | LD-05 (lider kararı): aynı kullanıcı + operasyon için 5 dk'lık pencerede **tek** kayıt, sorgu sayısı `details`'te; birleştirme sunucuda. Mevcut test, karar uygulanınca kırılır; karar uygulanmazsa denetim hacmi gereksiz büyür |
| Öneri | `database-physical-design.md` §6.7 deseni: `audit.location_view_windows` kalıcı ara tablosu; pencere dolunca veya 1 dk'lık kapatma işinde tek ekleme (`at` = pencere başı, `details.queryCount`). T-LOC-03 → "5 dk içinde N çağrı → tek kayıt, `queryCount = N`; 5 dk sonraki çağrı yeni pencere açar; uygulama yeniden başlasa da kayıt kaybolmaz". §12.2 notu buna göre güncellenmeli. Sayacın LC-05 işleminde mi, `LocationViewed` olayının dinleyicisinde mi güncelleneceği backend'in seçimidir (ikincisinde outbox yayını sayısı çağrı sayısı kadar olur; ilki daha ucuzdur) |
| Durum | **Çözüldü (LD-44, 15.11.2026).** Ara tablo DB §6.7 şemasıyla (PK `(actor_id, operation_id)`) hizalandı; sayaç LC-05 işleminde güncellenir (olay dinleyicisi değil); ilk ekleme yarışı `ON CONFLICT DO NOTHING` + `FOR UPDATE` + bir kez yeniden deneme ile kapandı. Backend §10.1/§15.5, DB §6.7/§7.1, T-LOC-03 – 10 |

#### B-04 — Kapasite-dolu yolunda idempotency işlem sınırı tanımsız (TK-11)

| | |
|---|---|
| Yer | §9.1 "Kapasite doluyken gelen kabul: atama `CAPACITY_FULL` yapılır, **işlem commit edilir** ve `409 CAPACITY_FULL` döner … Bu yanıt idempotency tablosuna **yazılmaz**"; §3.6 "Anahtar kaydı iş kaydıyla aynı veritabanı işleminde yazılır. Eşzamanlı ikinci istek benzersiz indekste bekler" |
| Sorun | §3.6 deseninde anahtar satırı işlemin **başında** eklenir (eşzamanlı tekrarın beklemesi buna dayanır). İşlem commit edilirse bu satır da commit olur: 409 yanıtı ya saklanır (kural ihlali) ya da yanıtsız, eksik bir satır kalır ve sonraki tekrar onu "2xx yanıt" sanıp boş gövde döndürebilir. İşlem geri alınırsa `CAPACITY_FULL` durumu kaybolur (AC-14.5 ihlali) |
| Öneri | 409 yolunda işlem, commit'ten önce kendi eklediği (başkalarına henüz görünmeyen) anahtar satırını `DELETE` eder. Bekleyen eşzamanlı tekrar çakışma görmez, kendisi işlenir ve `409 ASSIGNMENT_NOT_PENDING` alır (backend'in tarif ettiği sonuçla aynı). Kural genelleştirilmeli: "**durum değiştirip 4xx dönen her uç** anahtar satırını siler". Bugün yalnızca TK-11 bu sınıftadır; `ck_idempotency_keys_status (200..299)` kısıtı unutulan durumu yakalar |
| Durum | **Çözüldü (LD-45, LD-46, 15.11.2026).** Silme önerisi uygulandı. Ek olarak `response_status` işlem sürerken `NULL` (eski `NOT NULL` desenle çelişiyordu) ve ertelenmiş `ct_idempotency_keys_completed`; `CAPACITY_FULL` atamanın kabulü `409 CAPACITY_FULL` (diğer durumlar `ASSIGNMENT_NOT_PENDING`). Backend §3.6/§9.1/§15.3, DB §8.3/E-1 |

### 2.2 Orta

| # | Yer | Bulgu | Öneri |
|---|---|---|---|
| **B-05** | NT-04 `GET /me/notifications` "sıralama: açık alarmlar önce, sonra `createdAt` azalan" | Bileşik sıralama **iki modüle** yayılır (`deliveries` notifications'ta, alarm durumu alerts'te). İmleç sayfalamasında anahtar `(açık mı, createdAt, id)` olur; "açık mı" değeri sayfalar arasında değişebildiğinden (alarm kapanır) imleç kayar, öğe atlanır veya tekrarlanır. İndeksle desteklenemez | Gelen kutusu tek anahtarla `id DESC` (indeks `ix_deliveries_inbox (user_id, id DESC) WHERE channel = 'IN_APP'`). Açık alarmlar ayrı sorgudan (AL-08 `GET /me/alerts?state=OPEN`) gelir ve istemci üstte gösterir. Android ekranı (AND-09) zaten alarm bandını ayrı çiziyor |
| **B-06** | AL-09 `GET /alerts/{id}/recipients` "sıralama: `NEED_HELP` önce, sonra durum, sonra `displayName`", `cursor` | `displayName` identity modülündedir; alerts modülü SQL'de sıralayamaz (modül sınırı, ADR-001). İmleç bu sıralamayla tutarlı üretilemez | Alarm başına ≤ 500 alıcı ve `limit` ≤ 500 (N-16) olduğundan: tüm alıcılar tek sorguda (`uq_alert_recipients_alert_user` öneki), adlar identity'den toplu okunur, sıralama uygulamada yapılır, **imleç yok** (tek sayfa). Sözleşmede `cursor` bu uçtan kaldırılır veya "yok sayılır" yazılır |
| **B-07** | AU-01 "zaman azalan"; §3.5 "varsayılan sıralama `id` azalan" ; §15.5 indeks `(at DESC)` | Denetim kayıtları outbox ile **zaman uyumsuz** yazılır (§12.5) ve LD-05 kayıtları pencere sonunda eklenir. Kaydın `id`'si (ekleme anı) olay anından (`at`) sonra ve farklı sırada oluşur. `id` imleci zaman sırasını garanti etmez | Denetim listesi imleci `(at, id)`; indeks `ix_audit_entries_at (at DESC, id DESC)`; filtre indeksleri de `at` ile (`database-physical-design.md` §12.5). `at` = olay anı (olay yükünden), ekleme anı değil |
| **B-08** | §12.4 `NotificationKind`: `ALERT_REMINDER` | DM §2 ve `communication.md` §1.5: hatırlatmalar yeni bildirim kaydı değil, aynı gönderim bildiriminin `attempt` 1 – 5 teslim denemeleridir. `ALERT_REMINDER` türünde bir `Notification` satırı oluşturulursa 1:N kardinalitesi ve gelen kutusu (her hatırlatmada yeni IN_APP öğesi) bozulur | `ALERT_REMINDER` yalnızca FCM `type` değeri; `NotificationKind`'dan çıkarılır. Eksik tür: planlanan bitiş + 2 sa hatırlatması için `OPERATION_CLOSE_REMINDER` |
| **B-09** | AL-15 yanıt değişikliği; §3.6 "Alana özgü yedek … `response_idempotency_key` ile" | Alıcı satırında yalnızca **son** yanıtın anahtarı durur. Sırası bozuk teslim: çevrimdışı A yanıtı WorkManager'da beklerken kullanıcı bildirim düğmesiyle (TD-21) B'yi verir; B önce, A sonra (farklı anahtarla) ulaşır → eski A yeni B'nin üzerine yazılır. 24 saatten sonra gelen tekrar da genel tabloda bulunmaz | Yanıt güncellemesi cihaz zamanı sırasına koşullanır: `… WHERE id = ? AND user_id = :ben AND (responded_at_device IS NULL OR :yeni >= responded_at_device)`; 0 satır → mevcut durumla `200` (veri kaybı değil, eski yanıt). `database-physical-design.md` §8.2 E-11. Aynı cihazın saati tutarlı olduğundan sıralama güvenilirdir |
| **B-10** | AL-05, LC-01, TK-03, GR-01, NT-02: "operasyon `ACTIVE`" / `OPERATION_CLOSED` denetimleri | Denetimin **nasıl** yapıldığı yazılmamış. Kilitsiz okumada yarış: kapanış işlemi commit eder, kapanış zinciri açık alarmları kapatır; aynı anda ilerleyen gönderim işlemi eski `ACTIVE` değerini okumuş olduğundan commit eder → **kapalı operasyonda `OPEN` alarm** kalır, hatırlatmalar sürer (K-17, TD-06 ihlali). Konum başlatma için de aynı | Bu uçlar operasyon satırını aynı işlemde `FOR SHARE` ile okur (organization modülünün açık arayüzü üzerinden, ör. `requireActiveForUpdateShare(opId)`); kapanış `UPDATE` ile kilit aldığından yarış sıraya girer (`database-physical-design.md` §8.2 E-9). Konum **ölçümü** (LC-03) için kilit gerekmez (paylaşım durumu da denetlenir; en fazla bir ölçüm sızar ve zincir onu durdurur) |
| **B-11** | §5.2 "Aynı `fcmToken` başka kullanıcıyla kayıtlıysa eski kayıt pasifleşir"; §15.3 `uq_devices_fcm_token` (düz benzersiz) | Pasif satırlar silinmez çünkü `deliveries.device_id` geçmişi onlara bağlıdır. Düz benzersizlikte yeni kullanıcı aynı token'la **yeni satır açamaz**; eski satırı başka kullanıcıya "taşımak" ise geçmiş teslimlerin kullanıcı–cihaz ilişkisini bozar | Benzersizlik yalnızca aktif satırlarda (`uq_devices_fcm_token … WHERE active`), ayrıca `uq_devices_installation_active` ve `uq_devices_user_installation` (upsert hedefi). §3.3'te işlendi |
| **B-12** | §15.3 "Kapasite ≥ kabul sayısı — `ck_tasks_capacity_positive` (yalnızca > 0)" | AC-14.6 en kritik eşzamanlılık kuralıdır ama DB yedeği yok; kilit sırası bir kod değişikliğinde bozulursa kapasite sessizce aşılır | `tasks.accepted_count` + `ck_tasks_capacity_not_exceeded` (`capacity IS NULL OR accepted_count <= capacity`). Sayaç görev kilidi altında kabul, kaldırma ve yeniden atamada güncellenir; `acceptedCount` gösterimi JOIN'siz gelir. Eşleme: kabulde `CAPACITY_FULL`, düzenlemede `CAPACITY_BELOW_ACCEPTED`. §8.4 bütünlük sorgusu sayaç–gerçek farkını raporlar |
| **B-13** | §9.3 `TaskCreateRequest.meetingPoint`: `MeetingPointRequest` nesnesi (adres, koordinat, açıklama, zaman) | DM §5A `Task.meeting_point` **isteğe bağlı metin** alanıdır; ERD'de `text`. Nesne saklamak için şemada yer yok | team-lead'e iki seçenek (DB AN-21): **A** (öneri) API alanı metin olur (DM'ye sadık, AC-13.1 "yer/buluşma noktası" metinle karşılanır); **B** `tasks.mp_address, mp_lat, mp_lon, mp_description, mp_time` kolonları eklenir (fiziksel ek, mantıksal model değişikliği gerektirir) |
| **B-14** | §9.3 `TaskView.history[]` (AC-15.2); §15.5 "task_state_changes tablosu veya denetimden okuma → DB önerisi bekleniyor" | Denetimden okumak iki kuralla çelişir: denetimi yalnızca ADM/UYM okur (K-26) — KOR'a geçmiş göstermek denetim verisini sızdırır; ve tasks modülünün audit modülünü okuması modül yönünü tersine çevirir (audit tüketicidir) | Ayrı, yalnızca eklenen `tasks.task_state_changes` (`database-physical-design.md` §5.5); durum değişikliğiyle aynı işlemde yazılır |

### 2.3 Düşük

| # | Yer | Bulgu | Öneri |
|---|---|---|---|
| B-15 | §3.6 "Desteklenen uçlar: diğer tüm `POST` uçları başlığı kabul eder" | Android istemcisi başlığı her POST'a eklerse LC-03 ölçümleri (günde 108 – 216 bin, LD-15) ve AL-13/14 sinyalleri idempotency tablosuna yazılır; tablo ve vakum yükü gereksiz büyür | Saklama yalnızca §3.6 "zorunlu" listesi + açıkça işaretlenmiş uçlar için; LC-03, AL-13, AL-14, NT-05 ve `/auth/*` başlığı **yok sayar** (B-02) |
| B-16 | AL-10 / B-01, AL-06 `AlertSummary.counts`, TK-07 `pendingCount`/`acceptedCount`, NT-03 `delivery {sent, failed, read}` | Liste uçlarında öğe başına toplama yapılırsa N+1 sorgu çıkar (50 alarm × alıcı sayımı; 50 duyuru × teslim sayımı) | Sayfa için **tek gruplu sorgu**: `… WHERE alert_id IN (:sayfadakiler) GROUP BY alert_id, state`. Görevde `accepted_count` kolonu (B-12) zaten var; `pendingCount` için `ix_task_assignments_pending`. Maliyet: sayfa başına ≤ 25 000 satır taraması, < 30 ms |
| B-17 | AL-09 / `AlertRecipientView.groups[]` | Temel doküman (`api-and-data-design-basis.md` §5) `group` (tekil) diyor. DB anlık görüntüde tek `via_group_id` saklar; `groups[]` her okumada güncel üyeliklerden türetilirse geçmiş alarmlar için yanlış olur | `group` (`GroupRef`, boş olabilir) = gönderim anındaki ilk eşleşen hedef grup. Önizlemedeki `recipients[].groups[]` güncel çözümden geldiği için çoğul kalabilir |
| B-18 | ID-19 `GET /users` "sıralama `displayName`" + imleç | Sıralama anahtarı değişebilir (kullanıcı adını değiştirir) ve Türkçe harmanlama ister; KOR görünümünde katılım ↔ kullanıcı birleşimi gerekir | İmleç `(display_name, id)`; indeks `(display_name COLLATE "tr-TR-x-icu", id)`. Veritabanı varsayılan harmanlaması Türkçe **olmamalı** (`database-physical-design.md` §1.2: `lower('I')` sorunu e-posta normalizasyonunu bozar). Ad değişikliğinde imleç kaymasını kabul edilebilir say ve belgele |
| B-19 | §3.7 "kayıtlı olmayan e-postalar da 5 denemeden sonra aynı `423`" | Kayıtlı olmayan e-postanın sayacı `users` tablosunda tutulamaz | Bu sayaç hız sınırlayıcıyla aynı bellek içi yapıda (e-posta özeti anahtarlı) tutulur; kayıtlı kullanıcıda `users.failed_login_count`/`locked_until` esas. Yeniden başlatmada bellek sayacı sıfırlanır: kabul edilebilir, belgelenmeli |
| B-20 | ID-04 "`423 ACCOUNT_LOCKED` (5 hatalı kod)" | Hangi sayaçla tutulduğu belirsiz | `users.failed_login_count` ve `locked_until` ortak kullanılır (ayrı kolon gerekmez); MFA bileti (5 dk, tek kullanımlık) bellek içi kalabilir. Karar netse DB şemasında değişiklik yok. **Güncelleme (LD-57):** bilet çok örnekli çalışma için `identity.mfa_tickets`'ta (özetiyle) tutulur; sayaç `failed_login_count` ile ortak kaldı |
| B-21 | ID-06 "`logout` o oturumun cihazını pasifleştirir" | Android cihaz kaydı (ID-26) girişten **sonra** yapılır; `refresh_tokens.device_id` girişte boş kalabilir | Girişte `LoginRequest.installationId` ile `(user_id, installation_id)` cihaz satırı varsa bağlanır; yoksa ID-26 o oturumun satırlarını (`session_id`) günceller (`database-physical-design.md` §3.4) |
| B-22 | §2.2 adım 3: bellek içi iptal kümesi + açılışta DB'den yükleme | Yükleme sorgusu tanımlı değil; ayrıca çoklu örneğe geçişte küme tutarsız olur | Yükleme: `revoked_at > now() − 15 dk` olan `session_id`'ler (erişim belirteci ömrü; daha eskisi zaten geçersiz), indeks `ix_refresh_tokens_revoked_live`. Alternatif: her istekte `session_id` ile tek indeks araması (< 1 ms) — önbellek tutarlılığı derdi yok. MVP tek örnek olduğundan ikisi de uygun **Karar (LD-47, 15.11.2026):** alternatif seçildi; her istekte tek indeks araması, hesap durumuyla aynı sorguda. Bellek içi küme kaldırıldı |
| B-23 | §7.3 kapanış zinciri, AC-02 katılım kaldırma (N-10) | Tüketiciler ayrı işlemlerde ve Modulith olayları **en az bir kez** iletilir; tekrar işleme yan etkisi tanımlı değil. Katılım kaldırmada konum paylaşımı ve açık alarmlar listelenmemiş | Her tüketici durum koşullu `UPDATE` kullanır (`WHERE state = 'ACTIVE'` vb.) ve bildirim isteklerini kaynak anahtarıyla üretir (`uq_notifications_idempotency_key`) → tekrar zararsız. Katılım kaldırma zincirine konum paylaşımı `STOPPED` (`stop_reason = PARTICIPATION_REVOKED`) eklenmeli; "son lider" denetimi `revoke` işleminden **önce**, grup satırları kilitlenerek eşzamanlı yapılmalı |
| B-24 | §8.2 `MeetingPoint.status` (G): `ACTIVE`/`SUPERSEDED`/`CANCELLED`; §10.2 `stopReason` | ERD §2 `UPDATED` diyor; `stopReason`'da `PARTICIPATION_REVOKED`, `ACCOUNT_DISABLED`, `OPERATION_SUSPENDED` (G) eksik (B-23 ve §7.3 askıya alma bunları üretir) | ERD adı (`UPDATED`) kullanılır; `stopReason` enumu DB listesiyle eşitlenir (`database-physical-design.md` §12.2) |

---

## 3. Sorgu maliyeti değerlendirmesi

p95 ≤ 1 sn hedefi (NFR-08) açısından tüm uçlar rahat. Aşağıdakiler en pahalı veya en sık olanlardır.

| Uç | Sıklık (yoğun gün) | Maliyet | Not |
|---|---|---|---|
| LC-03 ölçüm | 2,5 – 5/sn (LD-15) | 3 PK araması + HOT upsert, < 5 ms | Denetim ve idempotency yazmamalı (B-15). SSE `location.updated` kalıcı outbox'tan geçmemeli (DB AN-19) |
| LC-05 konum listesi | KOR başına 10 – 15 sn'de bir (SSE sonrası debounce) | ≤ 500 satır + katılımcı birleşimi + LD-05 pencere upsert'i, < 25 ms | `notSharing[]` için katılım ↔ paylaşım farkı aynı sorguda |
| AL-05 gönderim | Günde ~10 | Hedef çözümü (≤ 50 grup × 50 üye) + 500 satır toplu ekleme + bildirim + outbox, < 150 ms | Toplu ekleme (JDBC batch / çok satırlı `INSERT`) şart; satır satır 500 ekleme ~1 sn sürer |
| AL-15 yanıt | Alarm başına ≤ 500, saniyeler içinde | Alıcı satırı güncellemesi + alarm `FOR SHARE` + idempotency + outbox, < 10 ms | Alarm satırında sayaç **tutulmamalı** (sıcak satır); `counts` alıcı tablosundan toplanır |
| AL-09 alıcı listesi / `counts` | Panel açıkken SSE başına (1 sn birleştirme) | ≤ 500 satır, < 20 ms | B-06 |
| Teslim işçisi | Alarm başına ~6 parti × 100 | `SKIP LOCKED` + FCM toplu çağrı | FCM çağrısı açık işlem içinde: `idle_in_transaction_session_timeout` 60 sn |
| Hatırlatma zamanlayıcısı | 30 sn | Kısmi indeks, ≤ 2 500 satır | `next_reminder_at` iptal/kapanış/yanıtta aynı işlemde `NULL` yapılmalı (alarm durumunu her taramada birleştirmemek için) |
| `/auth/refresh` | Aktif web oturumu başına 15 dk'da bir | 1 koşullu `UPDATE` + 1 `INSERT` | Günde ~8 000 satır; 7 günlük temizlik şart (`database-physical-design.md` §9.4) |
| Her istek: oturum + rol denetimi | Tüm istekler | Önbellekten; ıskada 2 indeks araması | B-22 |

## 4. Sayfalama ↔ indeks uyumu

| Uç | Sözleşmedeki sıralama | Destekleyen indeks | Uyum |
|---|---|---|---|
| OR-04 operasyonlar | `id` azalan | Küçük tablo (≤ 30) | ✓ |
| ID-13 davetler | `id` azalan + `scopeId?`, `status?` | `ix_invitations_scope (scope_type, scope_id, id DESC)` | ✓ |
| ID-19 kullanıcılar | `displayName` | `(display_name COLLATE tr, id)` | Koşullu ✓ (B-18) |
| OR-06 katılımcılar | belirtilmemiş | `ix_role_assignments_scope_active` | Sıralama `grantedAt`/`id` olarak yazılmalı |
| GR-02 gruplar | `id` azalan | `ix_groups_operation`, `ix_group_memberships_user_active` | ✓ |
| TK-07 görevler | `id` azalan + `state?` | `ix_tasks_operation (operation_id, id DESC)` | ✓ (durum süzgeci indeks sonrası; hacim küçük) |
| TK-09 atamalar | `id` azalan + `status?`, `overdue?` | `uq_task_assignments_task_user`, `ix_task_assignments_pending (task_id, respond_by)` | ✓ |
| TK-10 atamalarım | `id` azalan | `ix_task_assignments_user (user_id, task_id)` | ✓ (kişi başına küçük küme) |
| AL-06 alarmlar | `id` azalan + `state?` | `ix_alerts_operation (operation_id, id DESC)` | ✓ |
| AL-08 alarmlarım | `id` azalan, `state = OPEN` | `ix_alert_recipients_user (user_id, alert_id)` | ✓ |
| AL-09 alıcılar | `NEED_HELP` → durum → `displayName` | Modül dışı alan | ✗ → B-06 |
| NT-03 duyurular | `id` azalan + `operationId?` | `ix_notifications_announcements (operation_id, id DESC)` | ✓ |
| NT-04 gelen kutusu | açık alarmlar önce → `createdAt` | Modül dışı alan | ✗ → B-05 |
| AU-01 denetim | zaman azalan | `ix_audit_entries_at (at DESC, id DESC)` | ✓ imleç `(at, id)` ise (B-07) |
| GR-10 toplanma geçmişi (G) | `id` azalan | `ix_meeting_points_group (group_id, id DESC)` | ✓ |

Genel kural (§3.5) uyumlu: UUIDv7 `id` imleci oluşturma sırasını verir, ayrı `created_at` indeksi gerekmez. **İstisna:** zaman uyumsuz yazılan kayıtlar (denetim) — B-07.

## 5. İşlem sınırları

| İşlem | Tek işlem içinde olması gerekenler | Olaya (ayrı işlem) bırakılabilenler | Değerlendirme |
|---|---|---|---|
| AL-05 gönderim | idempotency satırı, operasyon `FOR SHARE`, alarm koşullu `UPDATE`, hedef çözümü + hash karşılaştırması, ≤ 500 alıcı, `ALERT_SENT` bildirimi, outbox, denetim olayı | Teslim satırları (fan-out işçisi), FCM, SSE | ✓ (B-10 kilidi eklenince) |
| AL-11/12 iptal/kapama | alarm koşullu `UPDATE`, tüm alıcılarda `next_reminder_at = NULL`, iptal/kapanış bildirimi, outbox | Teslimler, SSE | Tasarımda `next_reminder_at` temizliği açık yazılmamış → eklenmeli |
| AL-15 yanıt | idempotency, alarm `FOR SHARE`, alıcı koşullu `UPDATE` (B-09), outbox, denetim olayı | SSE | ✓ |
| AL-10 yeniden gönderim | alarm `FOR SHARE`, alıcılar koşullu `UPDATE` (`resend_count + 1`), outbox | Teslim satırları `round` ile (B-01) | ✓ (B-01 düzeltilince) |
| TK-11 kabul | görev `FOR UPDATE` → atama `FOR UPDATE` (bu sırayla), sayaç, gerekirse `PENDING → CAPACITY_FULL` toplu güncelleme, idempotency | Bildirimler, SSE | ✓ (B-04 kuralıyla) |
| TK-02 kapasite değişikliği | görev `FOR UPDATE` + `version`, eşitse `PENDING → CAPACITY_FULL` | Bildirimler | ✓ |
| GR-04/05/06 üyelik | grup `FOR UPDATE`, katılımcı denetimi, sayım (≤ 50), lider sırası (önce eski lider `MEMBER`, sonra yeni `LEADER`) | Bildirim, SSE abonelik düşürme | ✓ — lider değişiminde adım sırası sözleşme notuna yazılmalı (kısmi benzersiz indeks ara durumda ihlal edilir) |
| OR-03 kapanış | operasyon koşullu `UPDATE`, outbox | Konum durdurma, alarm kapama, grup arşivleme, rol pasifleştirme, davet `EXPIRED`, SSE | ✓ — tüketiciler idempotent olmalı (B-23) |
| LC-05 görüntüleme | okuma + LD-05 pencere upsert'i (veya `LocationViewed` olayı) | Pencere kapatma işi | ✓ (B-03) |
| ID-16 davet kabulü | davet koşullu `UPDATE` (`PENDING` ve süresi dolmamış), kullanıcı, katılım (`GON/OPERATION`), (G) onay, idempotency (belirteçsiz, B-02), oturum | E-posta yok; denetim olayı | ✓ (B-02 düzeltilince) |

## 6. Olumlu bulgular (değişiklik gerekmez)

- Roller ve izinler JWT'de taşınmıyor, her istekte hesaplanıyor (AC-05.2) — rol tablosu indeksleriyle ucuz.
- `version` + koşullu `UPDATE` iyimser kilidi, `audienceHash` ile hedef kitle değişikliğinin ayrılması (N-02) veri tarafında ek kilit gerektirmiyor.
- Yalnızca 2xx yanıt saklama ve aynı işlemde anahtar yazma kuralı, veritabanındaki benzersizlik beklemesiyle doğal olarak eşzamanlı tekrarı çözüyor.
- `GET` uçlarının durum değiştirmemesi (AL-04, N-18) önbellek ve yeniden deneme açısından doğru.
- Toplam sayı döndürülmemesi (§3.5) gereksiz `COUNT(*)` maliyetini önlüyor.
- `AlertRecipientView`'da bilinmeyen zamanların `null` yazılması, `delivered_at`/`seen_at` kolonlarının boş olabilmesiyle birebir uyumlu (AC-24.2).
- Denetim için değiştirme/silme ucu yok ve uygulama rolüne `UPDATE`/`DELETE` verilmemesi (T-AUD-02) DB yetki tasarımıyla aynı.

## 7. Takip

| Bulgu | Sahip | Beklenen |
|---|---|---|
| B-01 – B-04 | backend-architect | **Kapandı:** B-01 → LD-43, B-02 → LD-42, B-03 → LD-44, B-04 → LD-45/LD-46 |
| B-05 – B-12, B-15 – B-24 | backend-architect | Düzeltme veya gerekçeli ret (T-05 incelemesinde yanıtlanabilir) |
| B-13, B-14, B-03 | team-lead | **Karar verildi:** B-13 → LD-26 (görevde toplanma noktası metin; API `meetingPoint` `string` olmalı), B-14 → LD-24 (`task_state_changes` tablosu), B-03 → LD-22 (kalıcı ara tablo). Backend dokümanının bunlara göre güncellenmesi gerekir |
| B-02 | backend-architect | **Kapandı (LD-42).** Idempotency tablosuna sır içeren hiçbir değer yazılmaz |
| Ek: LD-18, LD-19, LD-20 | – | DB'ye işlendi: yenileme toleransı ve 12 sa mutlak web oturumu (`database-physical-design.md` §8.2 E-15); 5 aktif operasyon zorlanmaz (E-7) |
