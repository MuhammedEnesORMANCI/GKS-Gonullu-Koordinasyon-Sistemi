# Backend İncelemesi: PostgreSQL Fiziksel Veri Modeli (T-05)

| Alan | Bilgi |
|---|---|
| İnceleyen | `backend-architect` |
| İncelenen | `database-physical-design.md` **Taslak v1** (§1 – §13, AN-01 – AN-20) |
| Tarih | 14.11.2026 |
| Karşılaştırma tabanı | `backend-api-design.md` v1.0, `api-and-data-design-basis.md`, `domain-model.md` §5B, `emergency-alert-design.md`, LD-01 – LD-15 |
| Sonuç | **Genel olarak güçlü ve uygulanabilir.** 2 Yüksek, 7 Orta, 7 Düşük bulgu. Hiçbiri modelin yeniden yazılmasını gerektirmiyor; Yüksek bulguların ikisi de LD-05 ve alarm iletim süresi (NFR-08, AC-22.2) için **uygulama ile şema arasındaki** sınırla ilgili |

**Ciddiyet ölçütü.** **Yüksek:** bir lider kararı veya kabul kriteri bu hâliyle uygulanamaz ya da ölçülebilir hedef (≤ 5 sn) risk altında. **Orta:** API sözleşmesiyle çelişki veya yanlış davranış üreten kenar durumu; düzeltmesi küçük. **Düşük:** adlandırma, eksik enum değeri, belgeleme.

---

## 1. Olumlu tespitler (değiştirilmemeli)

| Konu | Neden iyi |
|---|---|
| Kısıt adlandırma kuralı (§1.3) ve kısıt adı → hata kodu çevirisi | Backend'in tek bir çeviriciyle çalışmasını sağlar; API tarafında benimsendi |
| `ck_tasks_capacity_not_exceeded` + `accepted_count` | K-12'nin veritabanı güvencesi; uygulama kilidi hatalı olsa bile kapasite aşılamaz (AC-14.6) |
| `ux_group_memberships_active_leader` (en fazla bir lider) + uygulamada "en az bir" | K-32'nin doğru bölünmesi |
| `ck_refresh_tokens_mfa_web_only` | ADR-013'ü veritabanı düzeyinde de imkânsız kılar |
| `refresh_tokens.family_id` (AN-06) | Çalınma tespiti tek `UPDATE`; JWT `sid` olarak benimsendi |
| `platform.idempotency_keys` deseni (§8.3) | Aynı işlemde ekleme + benzersiz anahtarda bekleme; "işleniyor" durumu gerektirmez. Kimliksiz davet kabulü için `principal_type = INVITATION` önerimden daha temiz; benimsendi |
| `ux_notifications_alert_lifecycle`, `ux_deliveries_push_unique` + `round` (AN-04) | Outbox'ın en-az-bir-kez teslimine karşı doğal idempotentlik; yeniden gönderimde teslim tekilliği korunuyor |
| `ix_alert_recipients_reminder_due` ve `ix_deliveries_queue` kısmi indeksleri | Tablo büyüse de tarama maliyeti sabit |
| Sayaç olmayan `alerts` satırı (§6.3) | 500 eşzamanlı yanıtta sıcak satır çekişmesini önler |
| `last_locations` HOT güncelleme + agresif otomatik vakum, koşullu upsert | K-16 ve TD-11 ile uyumlu; en yüksek yazma yükünü doğru ele alıyor |
| Yüksek frekanslı SSE olaylarını kalıcı outbox dışında tutma (AN-19) | API tasarımında benimsendi |
| Harmanlama uyarısı (§1.2: Türkçe `lower('I')`) | Gerçek ve sık yapılan bir hata; e-posta benzersizliğini korur |
| `audit_entries` için yalnızca `SELECT, INSERT` | NFR-05, K-26 |

---

## 2. Bulgular

### B-01 — LD-05 (konum denetimi birleştirme) yalnızca eklemeli denetim tablosuyla uygulanamıyor · **Yüksek**

- **Durum:** LD-05, aynı kullanıcı ve operasyon için 5 dk'lık pencerede **tek** `LOCATION_VIEWED` kaydı ister; pencerede yapılan sorgu sayısı `details` alanına yazılır. Fiziksel tasarımda `audit.audit_entries`'e yalnızca `INSERT` izni var (§2.3, doğru karar). Bu yüzden ilk sorguda yazılan kaydın sayacı sonradan artırılamaz.
- **Risk:** Ya bellek içi pencere tutulur (uygulama çökerse ≤ 5 dk'lık konum erişimi denetimsiz kalır; AC-18.3 ve K-15 için kabul edilemez), ya da her sorgu ayrı kayıt olur (LD-05 ihlali).
- **Öneri:** `audit` şemasına küçük bir ara tablo: `audit.location_view_windows (actor_id uuid, operation_id uuid, window_start timestamptz, query_count integer, max_item_count integer, last_query_at timestamptz)`, PK `(actor_id, operation_id, window_start)`, indeks `(window_start)`. `app_runtime` bu tabloda `SELECT, INSERT, UPDATE, DELETE` alır (`audit_entries` kısıtı aynen kalır). LC-05 işlemi açık pencereyi koşullu upsert ile yazar veya sayacını artırır. Dakikalık iş, süresi dolan pencereleri **tek işlemde** `audit_entries`'e ekler ve ara satırı siler. Hacim: 5 aktif operasyon × birkaç KOR ⇒ en fazla birkaç düzine satır.
- **Bağlantı:** `backend-api-design.md` §10.1, §14 N-28.

### B-02 — Teslim işçisi FCM çağrısı boyunca işlemi açık tutuyor; alıcı satırı kilitleri yanıtları geciktirebilir · **Yüksek**

- **Durum:** §7.1'e göre satırlar `FOR UPDATE SKIP LOCKED` ile alınıyor. FCM çağrısı aynı işlem açıkken yapılıyor ve alıcı durumu da aynı işlemde güncelleniyor.
- **Risk:** (1) İşçi teslimle birlikte `alert_recipients` satırını da güncellediği için o satırı FCM yanıtı gelene kadar kilitli tutar. Aynı anda gelen alarm yanıtı (`UPDATE alert_recipients … WHERE id = ?`, E-11) bu kilidi bekler. FCM toplu çağrısı normalde 1 – 2 sn sürer, ama zaman aşımı ve yeniden denemelerle 10 sn'yi aşabilir. Bu durumda AC-22.2 (yanıt panelde ≤ 5 sn) ve NFR-08 riske girer. (2) Ayrılmış 2 – 4 CRITICAL bağlantısı FCM gecikmesi boyunca meşgul kalır. (3) `idle_in_transaction_session_timeout = 60 sn` FCM kesintisinde işlemi öldürür; yarım kalan partinin durumu belirsizleşir.
- **Öneri:** Yeni enum değeri gerektirmeyen **kira deseni**, `available_at` ile: **İşlem 1 (kısa):** `SKIP LOCKED` ile satırları al, `available_at = now() + 60 sn` yap (kira), commit. **İşlem dışında:** FCM çağrısı. **İşlem 2 (kısa):** sonuçları teslim satırlarına ve alıcı durumuna yaz. İşçi çökerse kira dolar ve satır yeniden alınır (en az bir kez; `collapse_key` ve teslim tekilliği yinelenmeyi zararsız kılar). Yanıt güncellemesi en fazla milisaniyeler bekler.
- **Not:** AN-04'teki "kiralama" alternatifi `SENDING` durumu istiyordu. `available_at`'i kira olarak kullanmak bu ihtiyacı ortadan kaldırır.

### B-03 — İptal veya kapanıştan sonra kuyruktaki PUSH satırları yine gönderilebilir · **Orta**

- **Durum:** E-12, iptal ve kapanışta `next_reminder_at`'i boşaltıyor. Ancak hatırlatma zamanlayıcısının **o ana kadar kuyruğa aldığı** `ALERT_SENT` denemeleri (`attempt` 1..5) ve henüz gönderilmemiş ilk gönderim satırları `QUEUED` olarak kalıyor. Hatırlatma işçisi ile iptal arasındaki yarışta (iptal, işçinin kilitlediği alıcı satırını bekler) iptal bildiriminden **sonra** bir hatırlatma push'u gidebilir.
- **Risk:** Cihazda "ALARM İPTAL EDİLDİ" bildiriminin ardından aynı `collapse_key` ile hatırlatma gelir ve bildirim yeniden alarm görünümüne döner. TD-10 durum doğrulaması bunu düzeltir, ama yalnızca ağ varsa. AC-25.1 kullanıcı deneyimi bozulur.
- **Öneri:** (a) İptal/kapanış işlemi, o alarmın gönderim bildirimine ait `QUEUED` PUSH satırlarını aynı işlemde `FAILED` + `error_code = 'ALERT_NOT_OPEN'` yapar (`ix_deliveries_notification`). (b) Savunma derinliği: teslim işçisi `ALERT_SENT` türündeki satırları göndermeden hemen önce alarmın `OPEN` olduğunu denetler. `deliveries.error_code` serbest metin olduğundan yeni enum gerekmez.

### B-04 — Her istekteki oturum iptal denetiminin sorgusu tanımlı değil · **Orta**

- **Durum:** API, AC-03.3 ve AC-06.1 ("çıkıştan sonra aynı erişim belirteci 401") için her istekte `sid = family_id` iptal denetimi yapıyor. §3.4 bunu "isteğe bağlı" olarak anıyor. Döndürmede eski belirteçler iptal edilmiyor (`replaced_by` doluyor), bu yüzden "zincirde iptal edilmiş satır var mı" sorusu yanlış sonuç verir.
- **Öneri:** Tanımı sabitleyin: *oturum geçerli ⇔ zincirde `revoked_at IS NULL AND replaced_by IS NULL AND expires_at > now()` olan bir satır var.* `ix_refresh_tokens_family` (`WHERE revoked_at IS NULL`) bunu karşılar. Uygulama her istekte DB'ye gitmez: tek örnekte bellek içi "iptal edilmiş zincirler" kümesi tutulur (iptal anında eklenir, açılışta son 12 sa / 30 gün için yüklenir). Web'deki 12 sa **mutlak** süre (önerilen, N-23) için zincirin başlangıcı `family_id` satırının `issued_at`'inden okunur. Temizlik işi bu ilk satırı 7 gün tutuyor (§9.4); web için yeterli.

### B-05 — `tasks.response_deadline` görev düzeyinde; sonradan eklenen atama anında "süresi geçmiş" görünür · **Orta**

- **Durum:** `response_deadline = published_at + 60 dk` görev başına tek değer. `overdue = PENDING AND response_deadline < now()` (§12.3).
- **Risk:** TD-16'ya göre KOR, yayından sonra elle atama ekler (TK-05) veya `REMOVED`/`CAPACITY_FULL` atamayı yeniden `PENDING` yapar (AN-09). 60 dk'dan sonra eklenen atama ilk andan itibaren gecikmiş işaretlenir (AC-16.3 yanlış sonuç).
- **Öneri:** `tasks.response_window_minutes integer NOT NULL DEFAULT 60`, `CHECK BETWEEN 5 AND 1440`. Atamanın son yanıt zamanı `assigned_at + window` olarak hesaplanır. Yeniden `PENDING` yapılan satırda `assigned_at` yenilenir. `response_deadline` kaldırılır. Sorgu: `ix_task_assignments_pending (task_id) WHERE status = 'PENDING'` yeterli. Sayım için `assigned_at` indekste olmadan da ≤ 500 satır taranır. API alanları: `TaskView.responseWindowMinutes`, `AssignmentView.respondBy`.

### B-06 — `audience_fingerprint` önizlemede güncelleniyor; API'de önizleme `GET` ve yazmıyor · **Orta**

- **Durum:** §6.3, `audience_fingerprint` için "son önizlemede çözülen alıcıların SHA-256'sı; gönderimde yeniden hesaplanır, farklıysa `AUDIENCE_CHANGED`" diyor. `GET /alerts/{id}/preview` güvenli bir istek olmalı (HTTP semantiği, önbellek ve tekrar güvenliği). Ayrıca iki sekmede veya iki KOR'da "son önizleme" kullanıcının **gördüğü** önizleme olmayabilir.
- **Öneri:** Gönderim isteği, kullanıcının onay penceresinde gördüğü `audienceHash`'i taşır (`backend-api-design.md` §11.1, N-02) ve karşılaştırma bununla yapılır. `audience_fingerprint` kolonu kalabilir: taslak oluşturma/düzenleme (`POST`/`PATCH`) ve gönderimde yazılır, `GET` önizlemede yazılmaz. `preview_recipient_count`/`previewed_at` için de aynı kural geçerli. Hesap (sıralı `user_id` listesinin SHA-256'sı, onaltılık) iki tarafta aynıdır.

### B-07 — AC-15.2 görev geçmişinin denetim tablosundan okunması modül sınırını ve K-26'yı zorlar · **Orta**

- **Durum:** AN-10, görev durum geçmişini `audit_entries`'ten okumayı öneriyor ve sorunu kendisi de not ediyor: denetim kayıtlarını yalnızca ADM ve UYM okur (K-26), oysa KOR geçmişi görmeli.
- **Risk:** `tasks` modülünün `audit` tablosunu okuması ADR-001 modül sınırını aşar; ayrıca KOR için "yalnızca görev geçmişi" diye özel bir denetim okuma yolu açmak K-26'yı fiilen deler.
- **Öneri:** Küçük, modüle ait `tasks.task_state_changes (id, task_id, from_state, to_state, changed_by, changed_at)`, `ix (task_id, changed_at)`. Görev başına ≤ 5 satır (dönem boyunca ~15 000), yalnızca ekleme. `state_changed_by` kolonu gereksizleşir. Denetim kaydı ayrıca yazılmaya devam eder.

### B-08 — Kısıt → hata kodu eşlemesinde üç anlam hatası · **Orta**

| Kısıt | DB önerisi | Sorun | Öneri |
|---|---|---|---|
| `ux_group_memberships_active_leader` | `LEADER_REQUIRED` | İhlalin anlamı "lider fazla"dır, "lider yok" değil. İstemci yanlış mesaj gösterir | `409 VERSION_CONFLICT` (eşzamanlı değişiklik; istemci yeniden çeker). Grup satırı kilidiyle pratikte oluşmaz |
| `ux_alerts_corrects` | `INVALID_STATE_TRANSITION` | Durum geçişi değil, benzersizlik ihlali | `409 ALREADY_EXISTS` (`conflict = ALERT_ALREADY_CORRECTED`) |
| `fk_*` | `404` veya `500` | API referansları her zaman önce yükler (404 orada döner). FK ihlali bir programlama hatasıdır; 404'e çevirmek hatayı gizler | Her zaman `500` + alarm günlüğü |

Ayrıca: `ux_group_memberships_active_member` ve `ux_role_assignments_active` için "idempotent başarı" iç akışlarda (davet kabulünde katılım oluşturma) doğrudur. Açık API çağrısında (üye ekle, rol ata) ise kullanıcı zaten var olan bir şeyi yeniden istemiştir ve `409 ALREADY_EXISTS` (`ALREADY_MEMBER` / `ROLE_ACTIVE`) beklenir (web BOŞLUK-11). Bağlama göre ayrım çeviricide değil, servis katmanında yapılır; DB tarafında değişiklik gerekmez.

### B-09 — Yeni hata kodları üç ayrı önerede dağınık · **Orta**

- **Durum:** DB `EMAIL_ALREADY_REGISTERED`, `INVITATION_ALREADY_PENDING`, `GROUP_FULL`, `ACTIVE_OPERATION_LIMIT` öneriyor (AN-18). Web `ALREADY_PARTICIPANT` ve `CONFIRMATION_REQUIRED`, backend `ALREADY_EXISTS` öneriyor.
- **Öneri (API'de uygulandı, team-lead onayı N-05):** Benzersizlik çatışmaları **tek** `409 ALREADY_EXISTS` koduyla döner; nedeni `conflict` uzantısındadır (`EMAIL_REGISTERED`, `INVITATION_PENDING`, `ALREADY_PARTICIPANT`, `ALREADY_MEMBER`, `ROLE_ACTIVE`, `ASSIGNMENT_ACTIVE`, `ALERT_ALREADY_CORRECTED`). `GROUP_FULL` adı DB'den benimsendi. `ACTIVE_OPERATION_LIMIT` benimsendi; sınırın zorlayıcı olup olmadığı team-lead kararı (N-25). §12.4 tablosunun bu adlarla güncellenmesi önerilir.

### B-10 — Rol–kapsam `CHECK`'i `GON`'a her kapsamı açıyor · **Düşük**

- `ck_role_assignments_role_scope` içindeki `OR role = 'GON'` koşulu `(GON, ORGANIZATION)`'a izin veriyor. API tasarımında `NEW_USER + ORGANIZATION` daveti rol ataması üretmiyor (N-24, AN-11). Karar A çıkarsa koşul `(role = 'GON' AND scope_type = 'OPERATION')` olarak daraltılmalı. Böylece katılım (TD-01) tek anlamlı kalır.

### B-11 — Eksik enum değerleri · **Düşük**

| Kolon | Eksik değer | Gerekçe |
|---|---|---|
| `devices.deactivation_reason` | `SESSIONS_REVOKED` | `POST /users/{id}/revoke-sessions` ve `logout-all` cihazları pasifleştiriyor (SC-07); `LOGOUT` ile karıştırılmamalı |
| `refresh_tokens.revoke_reason` | `ROTATION_RETRY` | Döndürme toleransı (API §2.2, team-lead kararı N-22). Bu nedenle iptal edilmiş bir halefin tekrar kullanımı çalınma tespitini tetikler |
| `refresh_tokens.revoke_reason` | `SESSION_EXPIRED` *(isteğe bağlı)* | Web 12 sa mutlak sürenin dolması (yalnızca denetim ayrıntısı) |
| `audit_entries.resource_type` | `ANNOUNCEMENT`, `DEVICE`, (G) `CONSENT`, (G) `MEETING_POINT` | API denetim kataloğu (§12.5) bu kaynak türlerini kullanıyor. `NOTIFICATION` duyuru için belirsiz |
| `alert_recipients.failure_reason` | – | `NO_ACTIVE_DEVICE`, `TOKEN_INVALID`, `TRANSIENT_EXHAUSTED`, `FCM_UNAVAILABLE` API'de aynen benimsendi |

### B-12 — Operasyon kapanış zinciri bekleyen davetleri kapsamıyor · **Düşük**

- Kapanmış bir operasyona ait `PENDING` davet, 72 saat dolana kadar kabul edilebilir görünür. API kabulde operasyon durumunu denetliyor (`410 INVITATION_INVALID`), ama `GET /me/context → pendingInvitations[]` bu daveti göstermeye devam eder.
- **Öneri:** Kapanış zinciri o operasyonun `PENDING` davetlerini `EXPIRED` yapar. İndeks: `ix_invitations_scope_pending (scope_id) WHERE status = 'PENDING'`.

### B-13 — Yanıt değişikliği sırasının cihaz saatine bağlanması (E-11) · **Düşük**

- E-11, gecikmeli tekrarın yeni yanıtın üzerine yazılmasını `:cihaz_zamanı >= responded_at_device` koşuluyla engelliyor. Kullanıcı iki cihazdan yanıt verirse (aynı hesap, iki telefon; `ix_devices_user_active` buna izin veriyor) ve saatler farklıysa, daha yeni yanıt reddedilebilir.
- **Değerlendirme:** Asıl korumayı zaten platform idempotency kaydı (aynı anahtar → aynı sonuç) ve Android'deki "alarm başına tek benzersiz iş" (WorkManager `REPLACE`) sağlıyor. Öneri: koşul kalsın, ama yalnızca **aynı** `response_idempotency_key` ailesinden gelen tekrarlar için değil, genel kural olarak "0 satır → mevcut durumu `200` ile dön" davranışı denetim kaydına "eski yanıt yok sayıldı" ayrıntısıyla yazılsın. Böylece kayıp sessiz kalmaz. Saat farkı (`clockSkew`) olan cihazda bu koşul atlanabilir.

### B-14 — `ck_alerts_recipient_count BETWEEN 0 AND 500` sabit değer · **Düşük**

- 500 sınırı yapılandırılabilir bir parametredir (decision-log §5, D-04). Kısıt da sabit yazılırsa yapılandırma değişince geçiş gerekir. Öneri: kısıt üst sınırı geniş tutulsun (ör. `≤ 10 000`, mantık hatasına karşı); asıl sınır uygulamada (`AUDIENCE_TOO_LARGE`) uygulansın. Aynı not `ck_alert_recipients_reminder`, `ck_deliveries_attempt` için de geçerlidir.

### B-15 — `deliveryDelayed` (LD-02) için sorgu yolu · **Düşük**

- `AlertView.deliveryDelayed` = alarmın gönderim bildirimine ait `QUEUED` PUSH satırlarında `min(queued_at) < now() − 30 sn`. `ix_deliveries_notification (notification_id)` ile ≤ ~600 satır taranır; yeni kolon gerekmez. Panel her `alert.recipient.changed` olayında `GET /alerts/{id}` çağırdığından (500 alıcılı alarmda saniyede bir) kısmi bir indeks önerilir: `ix_deliveries_notification_queued (notification_id, queued_at) WHERE status = 'QUEUED'`. Kuyruk boşalınca indeks de boşalır.

### B-16 — Metin uzunluk sınırları iki dokümanda farklıydı · **Düşük (API tarafında çözüldü)**

- API önerim (ör. duyuru gövdesi 2000, `displayName` 40) ile §1.4 farklıydı. Duyuru gövdesinde API sınırı DB'yi aşıyordu; bu, `ck_*` ihlali yoluyla `400` yerine geç bir hataya yol açardı. API sınırları §1.4 ile **hizalandı** (`backend-api-design.md` §12.4). Öneri: iki taraf sınırları tek bir yapılandırma kaynağından üretsin (Bean Validation sabitleri + Flyway `CHECK` değerleri). Kural: API sınırı hiçbir zaman DB sınırını aşamaz.

---

## 3. API tarafında yapılan uyarlamalar (bu incelemenin sonucu)

| DB tasarımından gelen | API'deki değişiklik |
|---|---|
| `family_id` | JWT `sid` = `family_id` |
| `platform.idempotency_keys` (`principal_key`) | DB §8.3 v1.2 `principal_key` (`anon:` + kod HMAC özeti) kesinleşti; API §3.6 ve §15.4 buna hizalandı (LD-42) |
| `tasks.meeting_point text` | `TaskCreateRequest.meetingPoint` nesneden metne |
| `tasks.priority` `HIGH/NORMAL/LOW`, `alerts.priority` `CRITICAL/HIGH` | API enum'ları hizalandı (`URGENT` çıkarıldı) |
| `version` varsayılan `0` | Oluşturma yanıtında `version=0` |
| `via_group_id` | `AlertRecipientView.group` (tek) |
| `sent_by`, `recipient_count` | `AlertView.sentBy`, `recipientCount` |
| `failure_reason` değerleri | API aynen benimsedi |
| `notifications.kind` listesi | API aynen benimsedi |
| `ux_groups_operation_name` | `400 VALIDATION_FAILED` (`DUPLICATE`) |
| `GROUP_FULL`, `ACTIVE_OPERATION_LIMIT` | API kataloğuna eklendi (N-05, N-25) |
| `ix_deliveries_inbox (user_id, id DESC)` | Gelen kutusu sıralaması `id` azalan; "açık alarmlar önce" sıralaması kaldırıldı (AL-08 ayrı) |
| §1.4 uzunluklar | API §12.4 hizalandı |

## 4. database-architect'ten beklenen

1. B-01 ara tablosunun (`audit.location_view_windows`) ve yetkisinin §12.5'e eklenmesi.
2. B-02 kira deseninin §7.1'e işlenmesi; `idle_in_transaction_session_timeout` notunun buna göre güncellenmesi.
3. B-05 (`response_window_minutes`) ve B-07 (`task_state_changes`) şema değişiklikleri.
4. B-08 ve B-09 için §12.4 eşleme tablosunun güncellenmesi.
5. B-03, B-04, B-11, B-12, B-15 için küçük eklemeler.
6. Mutabık olunan satırların §12'de "Mutabık" olarak işaretlenmesi (`backend-api-design.md` §15 ile aynı).

Karar gerektirenler team-lead'e iletildi: N-22 (döndürme toleransı → `ROTATION_RETRY`), N-24 (rol–kapsam, B-10), N-25 (aktif operasyon sınırı), N-28 (LD-05 ara tablosu).
