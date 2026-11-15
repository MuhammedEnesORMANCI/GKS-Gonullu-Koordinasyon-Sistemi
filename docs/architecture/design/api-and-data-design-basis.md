# Sonraki Aşama Girdileri: API ve Veri Tasarım Esasları

| Alan | Bilgi |
|---|---|
| Durum | **Kabul edildi; sonraki aşamaya hazır.** Bu doküman OpenAPI dosyası, SQL geçişi veya kod **değildir.** Hafta 3'te (KP1) üretilecek çıktıların kurallarını ve envanterini sabitler |
| Dayanak | K-01 – K-33, ADR-001 – ADR-013, TD-01 – TD-22, `domain-model.md` §5B, `authorization.md`, `communication.md` §1.5 |
| Sonraki aşamanın çıktıları | (1) OpenAPI 3 sözleşmesi, (2) PostgreSQL fiziksel şema tasarımı, (3) hata kodu kataloğu, (4) yetki matrisi dosyası (negatif testlerin kaynağı) |

## 1. API genel kuralları

| Konu | Kural | Dayanak |
|---|---|---|
| Taban yol ve sürüm | `/api/v1`. Geriye uyumsuz değişiklik yeni sürüm gerektirir; CI sözleşme farkını denetler | ADR-010, HZ-8 |
| Biçim | JSON, UTF-8. Alan adları `camelCase`. Enum değerleri `UPPER_SNAKE_CASE` ve `domain-model.md` ile aynıdır | – |
| Kimlikler | UUIDv7 (zaman sıralı, tahmin edilemez) dize olarak | HZ-8, RT-07 |
| Zaman | ISO-8601, UTC (`2026-11-14T10:00:00Z`). İstemciden gelen cihaz zamanları ayrı alanda (`measuredAt`, `respondedAtDevice`) | VS-21, AC-19.3 |
| Sayfalama | İmleç tabanlı: `?limit=50&cursor=…` (azami 100). Yanıt: `{ "items": [...], "nextCursor": "…" }` | – |
| Kimlik doğrulama | Web: HttpOnly çerez + `X-CSRF-Token` başlığı. Android: `Authorization: Bearer`. İstemci türü ve `mfaVerified` sunucudaki oturumdan okunur, istemci beyanına güvenilmez | ADR-012, ADR-013 |
| Idempotency | `Idempotency-Key` başlığı; 24 saat saklanır. Aynı anahtar + aynı gövde → ilk yanıt tekrar döner; sır içeren yanıt (davet kabulü) saklanmaz ve tekrarı `409 ALREADY_COMPLETED` alır (LD-42). Aynı anahtar + farklı gövde → `422 IDEMPOTENCY_KEY_MISMATCH`. **Zorunlu:** §3'te **I** ile işaretli tüm uçlar; tek kaynak §3'tür (LD-04). Bunlar en az alarm gönderimi, alarm yanıtı, yeniden gönderim ve iptali, görev yayını ve yanıtı, davet oluşturma ve kabulü ile duyuru gönderimini kapsar | NFR-06, AC-20.5 |
| Eşzamanlılık | Taslak alarm ve görev gövdede `version` taşır. Uyuşmazlıkta `409 VERSION_CONFLICT` | TD-05 |
| Yetki hatası | Yetki hatası her zaman `403 FORBIDDEN`. Kaynak yoksa `404 NOT_FOUND`. Kimlikler tahmin edilemez olduğundan 403'ün kaynak varlığını açık etmesi kabul edilebilir risk sayılır. Liste uçları kapsam dışı kaydı hiç döndürmez | NFR-02, AC-10.2, AC-18.2 |
| Sunucu zamanı | Her yanıtta standart HTTP `Date` başlığı bulunur; ayrı bir zaman ucu yoktur. İstemciler saat farkı denetimini (G-10, AC-19.3) buna dayandırır (LD-31) | LD-31 |
| Hız sınırı | Giriş, TOTP, davet kodu doğrulama ve parola uçlarında; `429` + `Retry-After` | GAP-12, AC-03.2 |
| Gizlilik | Yanıtlar istenen rolün görebileceği alanlarla sınırlıdır (ör. GON'a başka üyelerin yalnızca `displayName`'i) | user-roles §5, NFR-15 |
| Denetim | §3'te "D" işaretli işlemler denetim kaydı üretir. **Konum görüntüleme** kayıtları sunucuda birleştirilir: aynı kullanıcı ve operasyon için 5 dk'lık pencerede tek `LOCATION_VIEWED` kaydı oluşur, penceredeki sorgu sayısı `details` alanına yazılır (LD-05, AC-18.3) | FR-20, K-26, BR-08 |

## 2. Hata yanıt modeli

`application/problem+json` (RFC 9457):

| Alan | Açıklama |
|---|---|
| `type` | Hata türü URI'si (ör. `/problems/operation-not-active`) |
| `title` | Kısa Türkçe başlık |
| `status` | HTTP durum kodu |
| `code` | Makine tarafından okunan hata kodu (aşağıdaki katalog); istemci kullanıcı mesajını buna göre seçer |
| `detail` | Ayrıntı. Kişisel veri veya iç bilgi içermez |
| `errors[]` | Alan doğrulama hataları: `{ field, code, message }` |
| `traceId` | Günlük kaydıyla eşleştirme kimliği |

**Hata kodu kataloğu (taslak):**

| `code` | HTTP | Anlamı | İlgili |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | Alan doğrulama hatası | AC-04.3 |
| `AUTH_INVALID_CREDENTIALS` | 401 | Genel giriş hatası (hesap varlığı açık edilmez) | AC-03.1 |
| `AUTH_MFA_REQUIRED` | 401 | TOTP adımı gerekli; yanıtta MFA bileti | K-19 |
| `AUTH_TOKEN_EXPIRED` | 401 | Erişim belirteci süresi doldu; yenileme dene | ADR-012 |
| `AUTH_SESSION_REVOKED` | 401 | Oturum iptal edildi | AC-06.1 |
| `AUTH_PASSWORD_CHANGE_REQUIRED` | 403 | Yönetici sıfırlaması sonrası yeni parola zorunlu | AC-03.5 |
| `ACCOUNT_LOCKED` | 423 | 5 hatalı deneme → 15 dk kilit | AC-03.2 |
| `ACCOUNT_DISABLED` | 403 | Hesap devre dışı | AC-06.2 |
| `FORBIDDEN` | 403 | Yetki yok (rol, kapsam veya kaynak kuralı) | NFR-02 |
| `CLIENT_NOT_ALLOWED` | 403 | Kurumsal işlem uygun oturum dışında denendi (Android oturumu; ADM/KOR için MFA'sız web oturumu) **veya** web rolü (ADM, KOR, TEK, UYM) olmayan kullanıcı web paneline giriş yapmaya çalıştı (LD-03) | ADR-013, AC-20.9, VS-06 |
| `NOT_FOUND` | 404 | Kaynak yok | – |
| `INVITATION_INVALID` | 410 | Davet süresi dolmuş, kullanılmış veya iptal edilmiş | AC-01.2 |
| `OPERATION_NOT_ACTIVE` | 409 | İşlem yalnızca aktif operasyonda yapılabilir | K-17, AC-17.4, AC-20.6 |
| `NOT_A_PARTICIPANT` | 409 | Kişi operasyona katılmamış | AC-09.7, TD-01 |
| `LEADER_REQUIRED` | 409 | Grup lidersiz bırakılamaz / lidersiz oluşturulamaz | AC-09.5, K-32 |
| `CAPACITY_FULL` | 409 | Görev kapasitesi dolu | AC-14.5 |
| `CAPACITY_BELOW_ACCEPTED` | 409 | Kapasite mevcut kabul sayısının altına indirilemez | AC-13.7 |
| `ASSIGNMENT_NOT_PENDING` | 409 | Atama yanıt bekleme durumunda değil | AC-14.4 |
| `INVALID_STATE_TRANSITION` | 409 | Tanımsız durum geçişi | AC-08.1, AC-15.1 |
| `AUDIENCE_CHANGED` | 409 | Önizlemeden sonra hedef kitle değişti; yeni önizleme gerekli | AC-20.8, TD-05 |
| `ALERT_NOT_OPEN` | 409 | Alarm iptal edilmiş veya kapanmış | AC-22.7, TD-06 |
| `LOCATION_SHARING_STOPPED` | 409 | Paylaşım durdurulmuş; cihaz servisi durdurmalı | TD-18 |
| `VERSION_CONFLICT` | 409 | Taslak sürümü uyuşmuyor | TD-05 |
| `IDEMPOTENCY_KEY_MISMATCH` | 422 | Aynı anahtar farklı gövdeyle kullanıldı | NFR-06 |
| `AUDIENCE_TOO_LARGE` | 422 | Alıcı sayısı üst sınırı (500) aşıyor | VS-02 |
| `RATE_LIMITED` | 429 | Çok fazla istek | GAP-12 |
| `SERVICE_UNAVAILABLE` | 503 | Altyapı (veritabanı) geçici olarak erişilemiyor; istek işlenmedi, oturum sona ermiş **sayılmaz**. Geçersiz belirteç ve iptal edilmiş oturumdan (401) ayrıdır | LD-48 |

## 3. Kaynak envanteri ve yetki kuralları

Yollar **taslaktır** (`system-architecture.md` §7 ile aynı). İstemci: **W** = yalnızca MFA'lı web oturumu, **A** = Android, **W/A** = ikisi. **I** = `Idempotency-Key` zorunlu. **D** = denetim kaydı. İzin adları `authorization.md` §5'tendir.

### 3.1 Kimlik, davet, kullanıcı

| İşlem | Taslak yol | İzin / kural | İstemci | I | D | AC |
|---|---|---|---|---|---|---|
| Giriş | `POST /auth/login` | – | W/A | | D | AC-03.1 – 03.2 |
| TOTP doğrulama / kurulum | `POST /auth/mfa`, `POST /auth/mfa/enroll` | MFA bileti | W | | D | AC-07.1 |
| Belirteç yenileme | `POST /auth/refresh` | Geçerli yenileme belirteci | W/A | | | ADR-012 |
| Çıkış / tüm cihazlardan çıkış | `POST /auth/logout`, `POST /auth/logout-all` | Kendi | W/A | | D | AC-03.3, AC-06.4 |
| Parola değiştirme | `POST /me/password` | Kendi | W/A | | D | AC-03.5 |
| Kendi profili | `GET/PATCH /me` | Kendi; değiştirilemez alanlar reddedilir | W/A | | | AC-04.1 – 04.3 |
| Davet oluşturma | `POST /invitations` | `invitation.create` | W | I | D | AC-01.1 – 01.4 |
| Davet kodunu doğrulama | `POST /invitations/lookup` (kod gövdede; yol veya sorgu parametresinde taşınmaz, günlüğe düşmez) | Herkese açık, hız sınırlı | A | | | AC-01.2, TD-02, LD-37 |
| Daveti kabul (yeni kullanıcı) | `POST /invitations/accept` (kod gövdede, LD-37) | Geçerli davet | A | I | D | AC-02.1 – 02.2 |
| Operasyon davetini kabul/ret (kayıtlı kullanıcı) | `POST /invitations/{id}/accept`, `.../decline` | `invitation.respond` | A | I | D | AC-01.5, TD-01 |
| Kullanıcı listesi / detay | `GET /users`, `GET /users/{id}` | ADM kurum; KOR operasyon katılımcıları; TEK yalnızca `displayName` + `status` (TD-14) | W | | | AC-06.5 |
| Rol atama / kaldırma | `POST/DELETE /role-assignments` | `role.assign` (KOR yalnızca lider ataması grup uçlarından) | W | | D | AC-05.1 – 05.3 |
| Hesap devre dışı / oturum iptali | `POST /users/{id}/disable`, `POST /users/{id}/revoke-sessions` | `account.disable`, `session.revoke` | W | | D | AC-06.1 – 06.3 |
| Yönetici parola / MFA sıfırlama | `POST /users/{id}/reset-password`, `POST /users/{id}/reset-mfa` | ADM; `mfa.reset` | W | | D | AC-03.5, AC-07.2 |
| Cihaz (FCM token) kaydı | `PUT /devices/{installationId}` | Kendi | A | | | TD-17 |
| Kim olduğum ve kapsamım | `GET /me/context` | Kendi | W/A | | | – |

### 3.2 Operasyon, grup, toplanma noktası

| İşlem | Taslak yol | İzin / kural | İstemci | I | D | AC |
|---|---|---|---|---|---|---|
| Operasyon oluştur, KOR ata | `POST /operations` | `operation.create` (ADM) | W | | D | AC-08.4 |
| Operasyon durumu değiştir | `POST /operations/{id}/state` | `operation.manage`; geçiş tablosu | W | | D | AC-08.1 – 08.3 |
| Operasyonlar (liste/detay) | `GET /operations`, `GET /operations/{id}` | Kapsam filtresi | W/A | | | – |
| Katılımcılar | `GET /operations/{id}/participants` | KOR, ADM | W | | | TD-01 |
| Grup oluştur (lider zorunlu) | `POST /operations/{id}/groups` | `group.create.operational` | W | | D | AC-09.1, AC-09.5 |
| Üye ekle / çıkar, lider değiştir | `POST/DELETE /groups/{id}/members`, `PUT /groups/{id}/leader` | `membership.add/remove`, `membership.role.update`; katılımcı kuralı | W | | D | AC-09.2 – 09.7 |
| Grup bilgisi düzenle | `PATCH /groups/{id}` | `group.update` (KOR, LEADER) | W/A | | D | AC-09.6 |
| Toplanma noktası güncelle | `PUT /groups/{id}/meeting-point` | `meeting_point.update` (KOR, LEADER) | W/A | | D | AC-12.1 – 12.3 |
| Gruplarım / grup detayı | `GET /groups`, `GET /groups/{id}` | `group.view`; alan görünürlüğü role göre | W/A | | | AC-10.1 – 10.3 |

### 3.3 Görev

| İşlem | Taslak yol | İzin / kural | İstemci | I | D | AC |
|---|---|---|---|---|---|---|
| Taslak oluştur / düzenle | `POST /operations/{id}/tasks`, `PATCH /tasks/{id}` | `task.manage`; `version` | W | | D | AC-13.1, AC-13.5 – 13.7 |
| Yayınla | `POST /tasks/{id}/publish` | `task.manage`; anlık görüntü (TD-16) | W | I | D | AC-13.2 – 13.4 |
| Durum ilerlet / iptal | `POST /tasks/{id}/state` | `task.manage` | W | | D | AC-15.1 – 15.3 |
| Atama kaldır / elle atama ekle | `DELETE/POST /tasks/{id}/assignments` | `task.manage` | W | | D | AC-14.4, TD-16 |
| Görev ve atama listesi | `GET /operations/{id}/tasks`, `GET /tasks/{id}` | KOR; ADM salt-okur; GON/LDR yalnızca kendi atamaları | W/A | | | AC-16.1 – 16.3 |
| Kabul / ret | `POST /assignments/{id}/accept`, `.../reject` | `task.respond`; `PENDING`; kapasite | A | I | D | AC-14.1 – 14.6 |

### 3.4 Konum

| İşlem | Taslak yol | İzin / kural | İstemci | I | D | AC |
|---|---|---|---|---|---|---|
| Paylaşımı başlat | `POST /operations/{id}/location-sharing` | `location.share`; operasyon `ACTIVE`, katılımcı | A | | D | AC-17.1 – 17.4 |
| Paylaşımı durdur | `DELETE /operations/{id}/location-sharing` | Kendi | A | | D | AC-17.3 |
| Ölçüm gönder | `POST /operations/{id}/locations` | `location.share`; paylaşım `ACTIVE` değilse `409 LOCATION_SHARING_STOPPED` | A | | | AC-17.5, AC-19.3, TD-11, TD-18 |
| Konum alınamıyor bildirimi | `POST /operations/{id}/locations` gövdesinde `availability = UNAVAILABLE` (koordinatsız) | `location.share`; **yalnızca durum değişiminde bir kez**, tekrar yok, ağ yoksa gönderilmez (K-24) | A | | | AC-17.1, LD-30 |
| Paylaşım durumum | `GET /me/location-sharing` | Kendi | A | | | AC-17.1 |
| Son konumlar | `GET /operations/{id}/locations` | `location.view` (KOR, aktif operasyon) | W | | D | AC-18.1 – 18.3, AC-19.1 – 19.2 |

### 3.5 Acil alarm, duyuru, bildirim

| İşlem | Taslak yol | İzin / kural | İstemci | I | D | AC |
|---|---|---|---|---|---|---|
| Taslak + önizleme | `POST /operations/{id}/alerts` | `alert.send`; `ACTIVE`; hedef kapsamı | W | | D | AC-20.1, AC-20.3, AC-20.6, AC-20.7 |
| Önizlemeyi yenile | `GET /alerts/{id}/preview` | `alert.send` | W | | | AC-20.2 |
| Onaylı gönderim | `POST /alerts/{id}/send` | `alert.send`; `version`; kitle değişmemiş | W | I | D | AC-20.2, AC-20.5, AC-20.8 |
| Alarm listesi / detayı | `GET /operations/{id}/alerts`, `GET /alerts/{id}` | `alert.view`; alıcı kendi alarmını görür (durum doğrulama, TD-10) | W/A | | | AC-21.7 |
| Alıcı durumları | `GET /alerts/{id}/recipients` | `alert.view` | W | | | AC-23.1, AC-24.1 – 24.3 |
| Yeniden gönder | `POST /alerts/{id}/resend` | `alert.resend`; yalnızca `UNANSWERED`/`FAILED` | W | I | D | AC-23.4 |
| İptal | `POST /alerts/{id}/cancel` | `alert.cancel` | W | I | D | AC-25.1 – 25.2 |
| Kapat | `POST /alerts/{id}/close` | `alert.close` | W | | D | AC-25.3 |
| Düzeltilmiş alarm | `POST /operations/{id}/alerts` + `correctsAlertId` | `alert.send`; eski alarm `CANCELLED` | W | | D | AC-25.4 |
| Alındı / görüldü bilgisi | `POST /alert-recipients/{id}/delivered`, `.../seen` | Alıcının kendisi | A | | | AC-24.1 |
| Yanıt / yanıt değişikliği | `POST /alert-recipients/{id}/responses` | `alert.respond`; alarm `OPEN` | A | I | D | AC-22.1 – 22.7 |
| Duyuru gönder | `POST /announcements` | `announcement.send.group` / `.organization`; > 50 alıcıda `confirm=true` | W | I | D | AC-11.1 – 11.5 |
| Gelen kutusu | `GET /me/notifications`, `POST /me/notifications/{id}/read` | Kendi | A | | | AC-11.5, TD-13 |

### 3.6 Denetim ve gerçek zamanlı akış

| İşlem | Taslak yol | İzin / kural | İstemci | AC |
|---|---|---|---|---|
| Denetim kayıtları | `GET /audit-entries` (Genişletilmiş: filtre parametreleri) | `audit.view` (ADM, UYM) | W | AC-27.1 – 27.4 |
| SSE akışı | `GET /stream?topics=operation:{id},alert:{id}` | Her konu için abonelikte `can()`; yetki düşünce akış kapanır | W | ADR-009 |

## 4. PostgreSQL veri modeli esasları

### 4.1 Şemalar ve tablolar (TD-20)

| Şema | Tablolar (varlık) |
|---|---|
| `identity` | `users` (User), `invitations` (Invitation), `devices` (Device), `refresh_tokens` (RefreshToken), `consent_records`* (ConsentRecord) |
| `access` | `role_assignments` (RoleAssignment) |
| `organization` | `organizations` (Organization), `operations` (Operation) |
| `groups` | `groups` (Group, Çekirdekte toplanma noktası alanlarıyla), `group_memberships` (GroupMembership), `meeting_points`* (MeetingPoint) |
| `tasks` | `tasks` (Task), `task_assignments` (TaskAssignment) |
| `location` | `location_sharings` (LocationSharing), `last_locations` (LastLocation) |
| `alerts` | `alerts` (Alert), `alert_recipients` (AlertRecipient) |
| `notifications` | `notifications` (Notification), `deliveries` (Delivery; teslim kuyruğu olarak da kullanılır) |
| `audit` | `audit_entries` (AuditEntry) |
| `platform` | Spring Modulith olay yayın kaydı (outbox), idempotency anahtarları |

\* Genişletilmiş MVP.

### 4.2 Eşleme kuralları

| Konu | Kural |
|---|---|
| Tipler | `uuid`, `timestamptz`, `text`, `integer`, `boolean`, `double precision`, `jsonb` (yalnızca `audience_expr`, denetim `details`), `tstzrange` (görev zaman aralığı) |
| Enum'lar | `text` + `CHECK` kısıtı (PostgreSQL enum tipi değil: geçişleri kolaylaştırır) |
| Birincil anahtar | Uygulamada üretilen UUIDv7 |
| Modüller arası FK | Yalnızca kimlik bütünlüğü için (`user_id`, `operation_id`, `group_id`), `ON DELETE RESTRICT`. Kod modül sınırını aşan JOIN yapmaz; okuma açık arayüzlerden (ADR-001) |
| Silme | İş kayıtları silinmez; durum alanıyla kapatılır. Hesap kapatmada (Genişletilmiş) kişisel alanlar anonimleştirilir, denetim kayıtları korunur (AC-30.2) |
| Gizli alanlar | Parola: uyarlanabilir özet. Belirteç ve davet kodu: yalnızca özet. `totp_secret`: uygulama düzeyinde şifreli |
| Zaman | Tümü UTC `timestamptz` |
| Denetim | `audit_entries` yalnızca ekleme; uygulama kullanıcısına UPDATE/DELETE izni verilmez |

### 4.3 Kısıt ve indeks gereksinimleri (`domain-model.md` §5B'den)

| Gereksinim | Uygulanış önerisi |
|---|---|
| Grupta tek aktif lider (K-32) | Kısmi benzersiz indeks: `(group_id)` WHERE `role='LEADER' AND status='ACTIVE'` + "lidersiz bırakma" kontrolü işlem içinde |
| Tek aktif üyelik | Kısmi benzersiz indeks: `(group_id, user_id)` WHERE `status='ACTIVE'` |
| Tek aktif rol ataması | Kısmi benzersiz indeks: `(user_id, role, scope_type, scope_id)` WHERE `revoked_at IS NULL` |
| Görev kapasitesi (K-12) | Uygulamada, görev satırı `FOR UPDATE` ile kilitlenerek; ayrıca `(task_id, user_id)` benzersiz |
| Kullanıcı ve operasyon başına tek paylaşım ve tek son konum | `(user_id, operation_id)` birincil anahtar |
| Alarm alıcısı tekliği | `(alert_id, user_id)` benzersiz |
| Düzeltme tekliği | `corrects_alert_id` benzersiz (boş olabilir) |
| Hatırlatma zamanlayıcısı | İndeks: `(next_reminder_at)` WHERE `state IN ('SENT','DELIVERED','SEEN')` |
| Teslim kuyruğu | İndeks: `(status, priority, queued_at)`; işçiler `FOR UPDATE SKIP LOCKED` |
| Idempotency | `(created_by, idempotency_key)` benzersiz; anahtar tablosunda 24 saat saklama |
| FCM token | `devices.fcm_token` benzersiz |
| Davet | `token_hash`, `code_hash` benzersiz |

### 4.4 Sentetik veri (K-04)

Sabit tohumlu üretici: 1 kurum, 500 kullanıcı (ADM 2, KOR 10, TEK 1, UYM 1, geri kalanı GON), 5 operasyon (en az 2'si `ACTIVE`), operasyon başına 4–8 grup (≤ 50 üye, her birinde lider), görevler, son konumlar (operasyon bölgesine dağılmış), geçmiş alarmlar. Yük testi (k6) aynı veri setini kullanır. Rakamlar varsayımdır; Hafta 3'te sabitlenir.

## 5. İstek / yanıt şema envanteri

OpenAPI'de tanımlanacak şemaların listesi ve temel alanları. Tam alan listesi ve doğrulama kuralları Hafta 3'te yazılır.

| Şema | Temel alanlar |
|---|---|
| `LoginRequest` / `LoginResponse` | `email`, `password` / `mfaRequired`, `mfaTicket`, `mustChangePassword` |
| `MfaVerifyRequest` | `mfaTicket`, `code` |
| `MeContext` | `user` (id, displayName, fullName), `roles[]` (role, scopeType, scopeId), `operations[]`, `groups[]` (role), `pendingInvitations[]` |
| `InvitationCreateRequest` / `InvitationView` | `email`, `scopeType`, `scopeId` / `id`, `kind`, `status`, `expiresAt`, `scopeName` |
| `InvitationAcceptRequest` | `password`, `fullName`, `displayName`, `phone?` |
| `UserSummary` / `UserDetail` | role göre alt kümeler (user-roles §5); TEK için yalnızca `displayName`, `status` |
| `OperationView` / `OperationCreateRequest` / `OperationStateRequest` | `id`, `name`, `state`, `plannedEndAt`, `coordinators[]` / `name`, `description`, `plannedEndAt`, `coordinatorIds[]` / `targetState` |
| `GroupView` / `GroupCreateRequest` | `id`, `name`, `operationId`, `leader`, `members[]`, `meetingPoint` / `name`, `description`, `leaderUserId`, `memberUserIds[]` |
| `MeetingPoint` | `address`, `lat`, `lon`, `description`, `time`, `updatedBy`, `updatedAt` |
| `TaskCreateRequest` / `TaskView` | `title`, `description`, `priority`, `meetingPoint?`, `timeWindow`, `targets` (userIds, groupIds), `capacity?`, `responseDeadline?` / + `state`, `acceptedCount`, `version` |
| `AssignmentView` | `id`, `taskId`, `user`, `status`, `respondedAt`, `overdue` |
| `LocationSharingView` | `operationId`, `state`, `startedAt`, `lastSentAt` |
| `LocationMeasurementRequest` | `lat`, `lon`, `accuracy`, `measuredAt` |
| `LocationView` | `user`, `lat`, `lon`, `accuracy`, `measuredAt`, `receivedAt`, `freshness` (`LIVE`, `STALE`, `LAST_KNOWN`), `clockSkew` |
| `AlertDraftRequest` / `AlertPreview` | `message`, `requestedAction`, `priority`, `targets`, `correctsAlertId?` / `alertId`, `version`, `recipientCount`, `recipients[]` |
| `AlertSendRequest` | `version` |
| `AlertView` | `id`, `state`, `message`, `requestedAction`, `responseOptions[]`, `sentAt`, `counts` (responded, pending, unanswered, failed, needHelp) |
| `AlertRecipientView` | `user`, `group`, `state`, `responseOption`, `sentAt`, `deliveredAt`, `seenAt`, `respondedAt`, `reminderCount`, `failureReason` (bilinmeyen alanlar `null` = "bilinmiyor") |
| `AlertResponseRequest` | `option` (`RECEIVED`, `NEED_HELP`, `ON_MY_WAY`), `respondedAtDevice` |
| `AnnouncementCreateRequest` | `title`, `body`, `targets`, `confirm` |
| `NotificationInboxItem` | `id`, `category`, `title`, `body`, `createdAt`, `readAt`, `link` |
| `DeviceRegisterRequest` | `fcmToken`, `appVersion` |
| `AuditEntryView` | `actor`, `action`, `resourceType`, `resourceId`, `at` |
| `ProblemDetail` | §2 |
| `SseEvent` | `id`, `type`, `topic`, `data` (yalnızca kimlikler ve durumlar, RT-K3) |

## 6. Alarm API akışı

| # | Adım | İstek | Başarı | Olası hatalar | Yan etkiler |
|---|---|---|---|---|---|
| 1 | Taslak ve önizleme | `POST /operations/{id}/alerts` | `201` `AlertPreview` | `CLIENT_NOT_ALLOWED`, `FORBIDDEN`, `OPERATION_NOT_ACTIVE`, `AUDIENCE_TOO_LARGE`, `VALIDATION_FAILED` | Denetim: `ALERT_DRAFTED` |
| 2 | Onaylı gönderim | `POST /alerts/{id}/send` + `Idempotency-Key` | `202` `AlertView` (`OPEN`) | `AUDIENCE_CHANGED`, `VERSION_CONFLICT`, `IDEMPOTENCY_KEY_MISMATCH` | Alıcılar `QUEUED`; `CRITICAL` bildirim; outbox; denetim `ALERT_SENT`; SSE `alert.opened` |
| 3 | İletim | (sunucu içi) | – | FCM geçici/kalıcı hata | `DELIVERY` (attempt 0); alıcı `SENT`/`FAILED`; SSE `alert.recipient.changed` |
| 4 | Cihaz doğrulama | `GET /alerts/{id}` | `200` (iptal/kapalı alarmda da `200`; durum `state` alanında, LD-38/N-18) | – | Cihaz `state`'e göre iptal veya kapanış ekranını gösterir |
| 5 | Alındı / görüldü | `POST /alert-recipients/{id}/delivered`, `.../seen` | `204` | – | Alıcı `DELIVERED`/`SEEN`; SSE |
| 6 | Yanıt | `POST /alert-recipients/{id}/responses` + `Idempotency-Key` | `200` | `ALERT_NOT_OPEN`, `FORBIDDEN` | `RESPONDED`; hatırlatma iptal; denetim `ALERT_RESPONDED`; SSE ≤ 5 sn |
| 7 | Hatırlatma | (zamanlayıcı, 30 sn tarama) | – | – | `DELIVERY` attempt 1–5 (alıcının güncel turunda, `round = resend_count`, LD-43); 5'ten sonra `UNANSWERED` |
| 8 | Yeniden gönder | `POST /alerts/{id}/resend` | `202` | `ALERT_NOT_OPEN` | Seçili alıcılar `QUEUED`, sayaç sıfır, yeni tur (`round` + 1, LD-43); denetim `ALERT_RESENT` |
| 9 | İptal | `POST /alerts/{id}/cancel` | `200` | `ALERT_NOT_OPEN` | `CANCELLED`; `ALERT_CANCELLED` bildirimi tüm alıcılara; denetim |
| 10 | Kapat | `POST /alerts/{id}/close` | `200` | `ALERT_NOT_OPEN` | `CLOSED`; `ALERT_CLOSED` bildirimi; denetim |

## 7. Konum API akışı

| # | Adım | İstek | Başarı | Olası hatalar | Yan etkiler |
|---|---|---|---|---|---|
| 1 | Başlat | `POST /operations/{id}/location-sharing` | `200` `LocationSharingView` (`ACTIVE`) | `OPERATION_NOT_ACTIVE`, `NOT_A_PARTICIPANT`, `CLIENT_NOT_ALLOWED` (web'den) | Denetim; SSE `location.sharing.changed` |
| 2 | Ölçüm (60 sn) | `POST /operations/{id}/locations` | `204` | `LOCATION_SHARING_STOPPED` → cihaz servisi durdurur | `last_locations` üzerine yazılır; saat farkı işareti; SSE `location.updated` |
| 3 | Görüntüleme | `GET /operations/{id}/locations` | `200` `LocationView[]` | `FORBIDDEN`, `CLIENT_NOT_ALLOWED` | Denetim `LOCATION_VIEWED` |
| 4 | Durdur | `DELETE /operations/{id}/location-sharing` | `204` | – | `STOPPED`; denetim; SSE |
| 5 | Operasyon kapanışı | (olay) | – | – | Tüm paylaşımlar `STOPPED`; FCM `LOCATION_STOP`; ≤ 1 dk |

## 8. SSE olay sözleşmesi

| Konu | Olay türleri | Abone olabilen |
|---|---|---|
| `operation:{id}` | `task.changed`, `assignment.changed`, `location.updated`, `location.sharing.changed`, `operation.state.changed`, `group.changed` | KOR (konum olayları yalnızca `location.view` izni olana) ; ADM salt-okur (konum olayları hariç) |
| `alert:{id}` | `alert.opened`, `alert.recipient.changed`, `alert.cancelled`, `alert.closed` | `alert.view` |
| `user:{id}` | `session.revoked` | Kendisi |

Olay verisi yalnızca kimlik ve durum taşır; istemci ayrıntıyı REST'ten alır. Yeniden bağlanmada `Last-Event-ID` ile kaçırılanlar gönderilir; arabellek yetmezse istemciye `resync` olayı gider ve ilgili sorgular yenilenir.

## 9. Hazırlık kontrol listesi

| Girdi | Durum |
|---|---|
| Varlıklar, alanlar ve ilişkiler | ✓ `domain-model.md`, `diagrams/erd.md` |
| Bütünlük kuralları ve kardinaliteler | ✓ `domain-model.md` §5B, bu doküman §4.3 |
| Kaynaklar, işlemler ve yetki kuralları | ✓ §3, `authorization.md` §5 |
| İstemci kısıtı (web/Android) | ✓ ADR-013 |
| Hata modeli ve kod kataloğu | ✓ §2 |
| İstek/yanıt şema envanteri | ✓ §5 |
| Alarm, konum ve SSE akışları | ✓ §6 – §8, `communication.md` §1.5 |
| Parametre değerleri | ✓ `analysis/decision-log.md` §5 |
| Açık karar | Yok. Hafta 3'te sabitlenecek ayrıntılar: alan uzunluk sınırları, sentetik veri rakamları (§4.4), SSE arabellek boyutu |
