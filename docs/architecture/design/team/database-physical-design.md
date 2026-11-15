# PostgreSQL Fiziksel Veri Modeli Tasarımı

| Alan | Bilgi |
|---|---|
| Görev | T-02 (fiziksel model), T-03 (backend uyumu, §12) |
| Sahip | `database-architect` |
| Durum | **Taslak v1.2** — team-lead kararları LD-18 – LD-29 işlendi (§13 "Karar" sütunu). — backend-architect ile uyum (T-03) işlendi (§12); LD-05 (konum denetim penceresi, §6.7) ve LD-15 (iki operasyonda aynı ölçüm, §9.2) eklendi. team-lead kararı bekleyenler §13 |
| Dayanak | `domain-model.md` §2, §3, §5, §5A, §5B; `diagrams/erd.md`; `api-and-data-design-basis.md` §1, §4, §6 – §8; `emergency-alert-design.md`; `communication.md` §1; ADR-001, ADR-004, ADR-008; `decision-log.md` §5 |
| Kapsam | Çekirdek MVP tabloları (§3 – §9), Genişletilmiş MVP tabloları (§10, ayrı etiketli). R2+ varlıkları **tasarlanmaz**, yalnızca bağlanma noktası olarak anılır (§10.3) |
| Bu doküman ne değildir | SQL, DDL, Flyway geçişi veya kod değildir. Tablolar ve kolonlar Markdown tablolarıyla tarif edilir; Hafta 3'te Flyway geçişlerine bu dokümandan çevrilir |

**Okuma kılavuzu.** "Kaynak" sütunu kolonun nereden geldiğini gösterir: `DM` = `domain-model.md`, `ERD` = `erd.md`, `K-xx`/`TD-xx`/`AC-xx.y` = karar ve kabul kriterleri, **`FİZ`** = mantıksal modelde olmayan, fiziksel tasarımda eklenen teknik kolon (gerekçesi açıklamada; onay gerektirenler §13'te listelidir).

---

## 1. Genel kurallar ve adlandırma

### 1.1 Tip ve kolon kuralları (TD-20, `api-and-data-design-basis.md` §4.2)

| Konu | Kural |
|---|---|
| Birincil anahtar | `uuid`, uygulamada üretilen **UUIDv7**. Kolon varsayılanı yok (PostgreSQL 16'da yerleşik `uuidv7()` yok). Zaman sıralı olduğu için B-tree eklemeleri sağa doğru büyür, sayfa bölünmesi azdır ve `ORDER BY id DESC` oluşturma sırasına denk gelir (imleç sayfalaması §7.4) |
| Enum | `text` + `CHECK (kolon IN (...))`. Değerler `UPPER_SNAKE_CASE`, `domain-model.md` ile birebir. PostgreSQL `ENUM` tipi kullanılmaz |
| Zaman | Tümü `timestamptz`. Sunucu ve oturum `timezone = 'UTC'`. Java tarafında `Instant`/`OffsetDateTime` (VS-21) |
| Metin | `text` + uzunluk `CHECK (char_length(x) BETWEEN 1 AND N)`. `varchar(n)` kullanılmaz (sınır değişince tablo yeniden yazılmaz, yalnızca kısıt değişir). Önerilen sınırlar §1.4 |
| Koordinat | `double precision`; `CHECK` ile aralık denetimi. PostGIS yok (ADR-008, K-16) |
| `jsonb` | Yalnızca hedef kitle ifadeleri ve denetim ayrıntısı: `notifications.audience_expr`, `alerts.target_expr`, `tasks.target_expr` (**LD-27** ile listeye eklendi; `api-and-data-design-basis.md` §4.2 buna göre genişler), `audit_entries.details` |
| Aralık | `tstzrange` yalnızca `tasks.time_window` |
| Boolean | `boolean NOT NULL DEFAULT false/true` (üç değerli mantık istenmeyen yerlerde `NULL` yasak) |
| Ortak kolonlar | Değişebilen tablolarda `created_at timestamptz NOT NULL DEFAULT now()` ve `updated_at timestamptz NOT NULL DEFAULT now()` (uygulama günceller; tetikleyici kullanılmaz) |
| Silme | İş kayıtları silinmez, durumla kapatılır. İstisnalar: `DRAFT` alarm (durum diyagramı §1.1), teknik tablolardaki süresi dolan satırlar (§8.2) |

### 1.2 Harmanlama (collation) uyarısı

Veritabanı varsayılan harmanlaması **Türkçe olmayan** bir yerel ayar olmalıdır (`C.UTF-8` veya `und-x-icu`). Türkçe yerel ayarda `lower('I') = 'ı'` olur ve e-posta normalizasyonu (`ck_users_email_lower`) bozulur. Türkçe sıralama gereken yerde (ad listeleri) sorguda açık harmanlama kullanılır: `ORDER BY display_name COLLATE "tr-TR-x-icu"`. E-posta küçük harfe uygulamada `Locale.ROOT` ile çevrilir.

### 1.3 Kısıt ve indeks adlandırma

Backend, kısıt ihlallerini **kısıt adına** göre hata koduna çevirir (§12.4). Bu yüzden adlar sabittir ve sözleşmenin parçasıdır.

| Önek | Anlamı | Örnek |
|---|---|---|
| `pk_` | Birincil anahtar | `pk_alerts` |
| `fk_` | Yabancı anahtar | `fk_alert_recipients_alert` |
| `uq_` | Benzersizlik kısıtı (tam) | `uq_devices_user_installation` |
| `ux_` | Benzersiz **kısmi** indeks (backend v1.0 §15.3 ile ortak kural) | `ux_group_memberships_active_leader` |
| `ck_` | `CHECK` kısıtı | `ck_tasks_capacity_not_exceeded` |
| `ix_` | Benzersiz olmayan indeks | `ix_alert_recipients_reminder_due` |
| `ct_` | Ertelenmiş kısıt tetikleyicisi (`CREATE CONSTRAINT TRIGGER … DEFERRABLE INITIALLY DEFERRED`) | `ct_idempotency_keys_completed` |

### 1.4 Metin uzunluk sınırları (backend §12.4 ile mutabık; Hafta 3'te sabitlenecek)

DB `CHECK` sınırları API doğrulamasıyla **aynıdır**; DB yalnızca son güvencedir (ihlal = `500`, §12.4).

| Alan | Sınır (karakter) | Gerekçe |
|---|---|---|
| `users.email` | 3 – 254 | RFC 5321 |
| `full_name` / `display_name` | 2 – 100 / 2 – 40 | Kilit ekranı ve liste görünümü |
| `phone` | E.164 `^\+[1-9][0-9]{7,14}$` | Backend `MeProfileUpdate` |
| `operations.name` / `groups.name` | 3 – 120 / 2 – 80 | |
| `description` alanları | ≤ 2000 (grup ≤ 1000) | |
| `tasks.title` | 3 – 120 | |
| `tasks.meeting_point` | ≤ 300 | Metin alanı (**LD-26**) |
| `alerts.message` / `requested_action` | 1 – 500 / ≤ 200 | Kilit ekranında kısa metin (TD-08, LD-12) |
| `notifications.title` / `body` | 3 – 120 / ≤ 2000 | Duyuru metni `body`'de; sistem bildirimlerinin başlıkları da bu sınırda |
| `groups.mp_address` / `mp_description` | ≤ 300 / ≤ 500 | |
| `idempotency_keys.idem_key` | 36 (UUID biçimi) | Backend §3.6; DB `CHECK` 8 – 64 geniş tutuldu |

---

## 2. Şemalar ve modüller arası yabancı anahtar politikası

### 2.1 Şema listesi (TD-20)

| Şema | Modül | Tablolar | Tahmini satır (dönem sonu, §9) |
|---|---|---|---|
| `identity` | identity | `users`, `invitations`, `devices`, `refresh_tokens`, `consent_records`* | ~ 600 / 300 / 700 / ≤ 30 000 (temizlikle) / 500 |
| `access` | access | `role_assignments` | ~ 3 000 |
| `organization` | organization | `organizations`, `operations` | 1 / ≤ 30 |
| `groups` | groups | `groups`, `group_memberships`, `meeting_points`* | ~ 150 / ~ 5 000 / ~ 500 |
| `tasks` | tasks | `tasks`, `task_assignments`, `task_state_changes` | ~ 3 000 / ~ 60 000 / ~ 10 000 |
| `location` | location | `location_sharings`, `last_locations` | ≤ 2 500 / ≤ 750 (canlı) |
| `alerts` | alerts | `alerts`, `alert_recipients` | ~ 500 / ~ 100 000 |
| `notifications` | notifications | `notifications`, `deliveries` | ~ 10 000 / ~ 1 000 000 |
| `audit` | audit | `audit_entries`, `location_view_windows` (LD-05 ara tablosu) | ~ 400 000 / ≤ 50 |
| `platform` | (altyapı) | `event_publication` (Spring Modulith), `idempotency_keys` | Temizlikle küçük |

\* Genişletilmiş MVP (§10).

### 2.2 FK politikası

| Kural | Uygulama |
|---|---|
| **Modül içi FK** | Serbest. Varsayılan `ON DELETE RESTRICT`. İstisnalar tablo bölümlerinde gerekçesiyle yazılır (yalnızca `refresh_tokens.replaced_by` → `SET NULL`) |
| **Modüller arası FK** | Yalnızca kimlik bütünlüğü için ve yalnızca şu hedeflere: `identity.users(id)`, `organization.operations(id)`, `organization.organizations(id)`, `groups.groups(id)`, `identity.devices(id)`. Her zaman `ON DELETE RESTRICT`, `ON UPDATE` yok (birincil anahtarlar değişmez) |
| **Çok biçimli referanslar** | FK **yok**: `role_assignments.scope_id`, `invitations.scope_id`, `notifications.source_id`, `audit_entries.resource_id`. Bütünlük uygulamada; ayrıca haftalık "yetim kayıt" denetim sorgusu (§8.4) |
| **Kod sınırı** | FK olması, modül kodunun başka modülün tablosuna JOIN yapabileceği anlamına gelmez (ADR-001). Okuma, modülün açık arayüzünden yapılır. Tek istisna §6.1'deki **kilit amaçlı** satır okumasıdır (operasyonun `FOR SHARE` ile kilitlenmesi); bu da organization modülünün açık arayüzünden çağrılır |
| **Ertelenebilir kısıtlar** | Kullanılmaz. Sıra bağımlılığı olan işlemler (lider değişimi) adım sırasıyla çözülür (§6.2) |

### 2.3 Veritabanı rolleri ve yetkiler

| Rol | Kullanım | Yetki |
|---|---|---|
| `app_migrator` | Flyway | Şemaların sahibi; DDL |
| `app_runtime` | Spring Boot uygulaması | Tüm şemalarda `SELECT, INSERT, UPDATE, DELETE`; **istisna `audit.audit_entries`: yalnızca `SELECT, INSERT`** (NFR-05, K-26). `TRUNCATE` yok |
| `app_backup` | `pg_dump` servisi (TD-15) | Salt okuma |

Denetim tablosunda savunma derinliği için ek olarak `UPDATE`/`DELETE`/`TRUNCATE`'i reddeden bir satır tetikleyicisi önerilir (sahip rolüyle yapılan yanlışlıklara karşı). Bu bir öneridir; zorunlu koşul yetkinin verilmemesidir.

---

## 3. identity şeması

### 3.1 `identity.users` (User)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | UUIDv7 | DM |
| `email` | text | Hayır | – | Giriş kimliği; küçük harfe normalize | DM, K-10 |
| `password_hash` | text | Hayır | – | Argon2id/bcrypt çıktısı (algoritma ön ekiyle) | ADR-012 |
| `full_name` | text | Hayır | – | Ad-soyad. Hesap kapatmada anonimleşir | DM, K-10 |
| `display_name` | text | Hayır | – | Görünen ad (GON'a gösterilen tek alan) | DM |
| `phone` | text | Evet | – | İsteğe bağlı | K-10 |
| `status` | text | Hayır | `'ACTIVE'` | `ACTIVE`, `DISABLED`, `CLOSED` (`CLOSED` Genişletilmiş, US-30) | DM |
| `totp_secret` | text | Evet | – | Uygulama düzeyinde şifreli (Base64 şifreli metin + anahtar sürümü) | K-19 |
| `totp_enrolled_at` | timestamptz | Evet | – | | DM |
| `totp_last_used_step` | bigint | Evet | – | Son kabul edilen TOTP adımı (`epoch/30`); aynı veya önceki adımın kodu reddedilir (tekrar oynatma koruması, LD-57, V8) | FİZ |
| `must_change_password` | boolean | Hayır | `false` | ADM sıfırlamasından sonra `true` | AC-03.5 |
| `failed_login_count` | integer | Hayır | `0` | | AC-03.2 |
| `locked_until` | timestamptz | Evet | – | 5 hata → 15 dk | AC-03.2 |
| `password_changed_at` | timestamptz | Evet | – | Parola değişince eski erişim belirteçlerini geçersiz saymak için | FİZ |
| `disabled_at` | timestamptz | Evet | – | | FİZ (AC-06.2 denetimi kolaylaştırır) |
| `closed_at` | timestamptz | Evet | – | Genişletilmiş (US-30) | FİZ |
| `closure_requested_at` | timestamptz | Evet | – | Genişletilmiş: gönüllünün kapatma talebi (AC-30.1, backend ID-30). Doluyken ikinci talep `409 ALREADY_EXISTS` | FİZ |
| `created_at`, `updated_at` | timestamptz | Hayır | `now()` | | DM |

| Kısıt | Tanım |
|---|---|
| `pk_users` | `(id)` |
| `uq_users_email` | `(email)` benzersiz |
| `ck_users_email_lower` | `email = lower(email)` (§1.2 harmanlama uyarısı) |
| `ck_users_status` | `status IN ('ACTIVE','DISABLED','CLOSED')` |
| `ck_users_failed_login` | `failed_login_count >= 0` |
| `ck_users_closed_at` | `(status = 'CLOSED') = (closed_at IS NOT NULL)` |
| `ck_users_lengths` | §1.4 sınırları |

İndeks: ayrı indeks gerekmez (`uq_users_email` girişte kullanılır). Kullanıcı listesi sıralaması için §7.4.

### 3.2 `identity.invitations` (Invitation)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `kind` | text | Hayır | – | `NEW_USER`, `OPERATION` | DM, TD-01 |
| `email` | text | Hayır | – | Küçük harf | DM |
| `user_id` | uuid | Evet | – | `OPERATION` davetinde zorunlu; `NEW_USER`'da kabulde dolar | DM |
| `scope_type` | text | Hayır | – | `ORGANIZATION`, `OPERATION` | DM |
| `scope_id` | uuid | Hayır | – | Kurum veya operasyon (FK yok, §2.2) | DM |
| `token_hash` | text | Hayır | – | Bağlantı belirtecinin **anahtarlı özeti** (HMAC-SHA-256, sunucu sırrı) | DM, TD-02 |
| `code_hash` | text | Hayır | – | Elle girilen kodun anahtarlı özeti | DM, TD-02 |
| `expires_at` | timestamptz | Hayır | – | Oluşturma + 72 sa | K-06 |
| `status` | text | Hayır | `'PENDING'` | `PENDING`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `REVOKED` | DM |
| `used_at` | timestamptz | Evet | – | Kabul/ret zamanı | DM |
| `created_by` | uuid | Hayır | – | | DM |
| `created_at` | timestamptz | Hayır | `now()` | | FİZ |

**Neden anahtarlı özet:** Davet kodu kısadır (ör. 8 karakter). Tuzlu, yavaş özet (bcrypt) ile saklanırsa `GET /invitations/by-code/{code}` için arama yapılamaz. Bu yüzden deterministik **HMAC** kullanılır; kaba kuvvete karşı koruma hız sınırıdır (GAP-12) ve 72 saatlik ömürdür.

| Kısıt | Tanım |
|---|---|
| `pk_invitations` | `(id)` |
| `fk_invitations_user` | `user_id → identity.users(id)` RESTRICT |
| `fk_invitations_created_by` | `created_by → identity.users(id)` RESTRICT |
| `uq_invitations_token_hash`, `uq_invitations_code_hash` | Benzersiz (DM §5B) |
| `ck_invitations_kind`, `ck_invitations_status`, `ck_invitations_scope_type` | Enum |
| `ck_invitations_operation_kind` | `kind <> 'OPERATION' OR (scope_type = 'OPERATION' AND user_id IS NOT NULL)` |
| `ck_invitations_used` | `status NOT IN ('ACCEPTED','DECLINED') OR used_at IS NOT NULL` |
| `ux_invitations_pending_email_scope` | `(email, scope_type, scope_id)` WHERE `status = 'PENDING'` — aynı kişiye aynı kapsamda iki bekleyen davet olmaz; yeniden gönderim = eskisini `REVOKED` yapıp (backend ID-14) yenisini oluşturmak. Backend ile **mutabık** → `409 ALREADY_EXISTS`. Not: süresi dolmuş ama henüz `EXPIRED`'a çevrilmemiş davet de çakışır; davet oluşturma işlemi önce o kaydı `EXPIRED` yapmalıdır |

| İndeks | Hizmet ettiği sorgu |
|---|---|
| `ix_invitations_user_pending` | `(user_id)` WHERE `status = 'PENDING'` — `GET /me/context` → `pendingInvitations[]` |
| `ix_invitations_expiry` | `(expires_at)` WHERE `status = 'PENDING'` — süresi dolanları `EXPIRED` yapan iş |
| `ix_invitations_scope` | `(scope_type, scope_id, id DESC)` — davet listesi (backend ID-13) imleç sayfalaması; kapanışta operasyonun `PENDING` davetlerini `EXPIRED` yapma (backend §7.3) |

Geçerlilik okunurken her zaman `status = 'PENDING' AND expires_at > now()` birlikte denetlenir; `EXPIRED`'a geçiren iş yalnızca görünürlük içindir.

### 3.3 `identity.devices` (Device)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | Sunucu kimliği | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `installation_id` | text | Hayır | – | `PUT /devices/{installationId}` yol parametresi; uygulama kurulumu başına | FİZ (API §3.1, TD-17) |
| `fcm_token` | text | Hayır | – | | DM |
| `platform` | text | Hayır | `'ANDROID'` | | DM |
| `app_version` | text | Evet | – | | DM |
| `active` | boolean | Hayır | `true` | Kalıcı FCM hatasında, çıkışta veya kullanıcı değişiminde `false` | DM |
| `deactivated_at` | timestamptz | Evet | – | | FİZ |
| `deactivation_reason` | text | Evet | – | `FCM_UNREGISTERED`, `FCM_INVALID_TOKEN`, `LOGOUT`, `USER_SWITCH`, `TOKEN_REPLACED`, `ACCOUNT_DISABLED` | FİZ (AC-24.3 hata türü) |
| `registered_at` | timestamptz | Hayır | `now()` | | DM |
| `last_seen_at` | timestamptz | Evet | – | | DM |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_devices` | `(id)` | |
| `fk_devices_user` | → `identity.users` RESTRICT | |
| `uq_devices_user_installation` | `(user_id, installation_id)` | `PUT /devices/{installationId}` upsert hedefi; aynı kullanıcının yeniden kaydı aynı satırı günceller (backend §15.5 "kullanıcı başına benzersiz") |
| `ux_devices_fcm_token_active` | `(fcm_token)` WHERE `active` | DM §5B "FCM token benzersiz" kuralının fiziksel hâli. Pasif satırlar geçmiş teslim kayıtlarının (`deliveries.device_id`) referansı olarak kalır, bu yüzden benzersizlik **yalnızca aktif** satırlardadır. Aynı telefonda başka kullanıcı oturum açınca eski satır `USER_SWITCH` ile pasifleşir, yeni satır aynı token'la açılır (§13 AN-05) |
| `ux_devices_installation_active` | `(installation_id)` WHERE `active` | Kurulum başına tek aktif kullanıcı: el değiştiren telefona önceki kullanıcının alarmı gitmez (token yenilenmiş olsa bile) |
| `ix_devices_user_active` | `(user_id)` WHERE `active` | Alarm fan-out: alıcının aktif cihazları (500 alıcı × indeks araması) |
| `ck_devices_platform` | `platform IN ('ANDROID')` | |
| `ck_devices_active_reason` | `active = (deactivated_at IS NULL)` | |

### 3.4 `identity.refresh_tokens` (RefreshToken, TD-04)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `device_id` | uuid | Evet | – | Web'de boş. Android'de girişte `LoginRequest.installationId` ile `(user_id, installation_id)` cihaz satırı varsa dolar; yoksa ilk `PUT /devices/{installationId}` çağrısı bu oturumun satırlarını (`family_id`) günceller. Çıkışta "o oturumun cihazını pasifleştir" kuralı bu bağa dayanır | DM |
| `family_id` | uuid | Hayır | – | Oturum = döndürme zincirinin kimliği (ilk belirtecin `id`'si); JWT `sid` claim'i (backend §2.2). Çalınma tespitinde zincirin tamamını tek `UPDATE` ile iptal eder; istek başı iptal denetiminin anahtarıdır (AC-03.3, AC-06.1) | FİZ |
| `client` | text | Hayır | – | `WEB`, `ANDROID` | DM |
| `token_hash` | text | Hayır | – | SHA-256 (belirteç yüksek entropili olduğundan anahtarsız özet yeterli) | DM |
| `mfa_verified` | boolean | Hayır | `false` | | DM, ADR-013 |
| `issued_at` | timestamptz | Hayır | `now()` | | DM |
| `expires_at` | timestamptz | Hayır | – | Web 12 sa, Android 30 gün | TD-04 |
| `last_used_at` | timestamptz | Evet | – | | DM |
| `replaced_by` | uuid | Evet | – | Zincirdeki sonraki belirteç | DM |
| `revoked_at` | timestamptz | Evet | – | | DM |
| `revoke_reason` | text | Evet | – | `LOGOUT`, `LOGOUT_ALL`, `ADMIN_REVOKE`, `REUSE_DETECTED`, `ACCOUNT_DISABLED`, `PASSWORD_CHANGED`, `MFA_RESET` | FİZ (AC-06.3 denetim ayrıntısı) |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_refresh_tokens` | `(id)` | |
| `fk_refresh_tokens_user` | → `identity.users` RESTRICT | |
| `fk_refresh_tokens_device` | → `identity.devices` RESTRICT | |
| `fk_refresh_tokens_replaced_by` | → `identity.refresh_tokens(id)` **ON DELETE SET NULL** | Temizlik işinin zinciri sırasız silebilmesi için (teknik tablo, iş kaydı değil) |
| `uq_refresh_tokens_hash` | `(token_hash)` | `POST /auth/refresh` araması |
| `ck_refresh_tokens_client` | Enum | |
| `ck_refresh_tokens_mfa_web_only` | `client = 'WEB' OR mfa_verified = false` | ADR-013: Android oturumu kurumsal izin taşıyamaz — **veritabanı düzeyinde** de imkânsız |
| `ck_refresh_tokens_web_no_device` | `client = 'ANDROID' OR device_id IS NULL` | |
| `ix_refresh_tokens_family` | `(family_id)` | Her istekteki oturum geçerlilik denetimi (LD-47, backend §2.2 adım 3); çalınma tespitinde zincir iptali; çıkışta oturumun cihazını bulma |
| `ix_refresh_tokens_revoked_live` | `(revoked_at)` WHERE `revoked_at IS NOT NULL` | **LD-47 ile kullanılmıyor:** bellek içi iptal kümesinin açılış yüklemesi içindi; oturum denetimi artık her istekte `ix_refresh_tokens_family` ile yapılır. Kaldırılması ayrı bir migration kararıdır (şema V3'te mevcut) |
| `ix_refresh_tokens_user_active` | `(user_id)` WHERE `revoked_at IS NULL` | FR-22 tüm oturumları iptal, `logout-all` |
| `ix_refresh_tokens_expires` | `(expires_at)` | Temizlik işi (§8.2) |

### 3.5 Hacim notu

Web'de erişim belirteci 15 dk olduğundan aktif bir web oturumu saatte ~4 yeni satır üretir. 150 eşzamanlı oturumla günde **~7 000 – 10 000 satır** (§9.2). Süresi dolmuş satırlar 7 gün sonra silinir (§8.2); aksi hâlde tablo dönem sonunda ~1 milyon satıra ulaşır.

### 3.6 `identity.mfa_tickets` (MFA bileti, LD-57, V8)

ADM/KOR web girişinde parola doğrulandıktan sonra verilen, TOTP adımını bekleyen bilet. Ham bilet saklanmaz.

| Kolon | Tip | NULL | Varsayılan | Açıklama |
|---|---|---|---|---|
| `id` | uuid | Hayır | – | UUIDv7 |
| `user_id` | uuid | Hayır | – | FK `identity.users` |
| `token_hash` | text | Hayır | – | Biletin SHA-256 özeti; `uq_mfa_tickets_hash` |
| `created_at` | timestamptz | Hayır | – | |
| `expires_at` | timestamptz | Hayır | – | `created_at + 5 dk` |
| `attempts` | integer | Hayır | `0` | Hatalı kod sayısı; `ck` 0..5, 5'te bilet geçersiz |
| `used_at` | timestamptz | Evet | – | Doğru koddan sonra dolar; tek kullanım |

İndeks: `ix_mfa_tickets_expires (expires_at)` (temizlik). Süresi dolmuş biletlerin silinmesi temizlik işine eklenecektir (§8.2; henüz uygulanmadı).

---

## 4. access ve organization şemaları

### 4.1 `access.role_assignments` (RoleAssignment, K-07, TD-01)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `role` | text | Hayır | – | `ADM`, `KOR`, `GON`, `TEK`, `UYM` | DM |
| `scope_type` | text | Hayır | – | `ORGANIZATION`, `OPERATION` | DM |
| `scope_id` | uuid | Hayır | – | Kurum veya operasyon kimliği (çok biçimli, FK yok) | DM |
| `granted_by` | uuid | Evet | – | İlk ADM tohum verisiyle oluşur (boş); diğer tüm atamalarda dolu | DM |
| `granted_at` | timestamptz | Hayır | `now()` | | DM |
| `revoked_at` | timestamptz | Evet | – | | DM |
| `revoked_by` | uuid | Evet | – | Otomatik pasifleştirmede boş | FİZ (AC-05.3) |
| `revoke_reason` | text | Evet | – | `MANUAL`, `OPERATION_CLOSED`, `ACCOUNT_CLOSED`, `INVITATION_DECLINED`* (`ck_role_assignments_revoke_reason`, V7) | FİZ |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_role_assignments` | `(id)` | |
| `fk_role_assignments_user`, `fk_role_assignments_granted_by`, `fk_role_assignments_revoked_by` | → `identity.users` RESTRICT | |
| `ck_role_assignments_role`, `ck_role_assignments_scope_type` | Enum | |
| `ck_role_assignments_role_scope` | `(role IN ('ADM','TEK','UYM') AND scope_type = 'ORGANIZATION') OR (role IN ('KOR','GON') AND scope_type = 'OPERATION')` | backend §6 geçerli çiftlerle **mutabık**: kurum düzeyi `GON` yoktur (**LD-23**: kurum daveti rol üretmez); kurumda kayıtlı olmak = `users` satırı. API karşılığı `400 NOT_ALLOWED_VALUE` (Bean Validation önce yakalar) |
| `ck_role_assignments_revoked` | `revoked_at IS NOT NULL OR (revoked_by IS NULL AND revoke_reason IS NULL)` | |
| `ux_role_assignments_active` | `(user_id, role, scope_type, scope_id)` WHERE `revoked_at IS NULL` | DM §5B tek aktif atama |
| `ix_role_assignments_user_active` | `(user_id)` WHERE `revoked_at IS NULL` | Her istekte `can()` için kullanıcının rolleri, `GET /me/context`. Önbelleklenebilir; sorgu ≤ 10 satır |
| `ix_role_assignments_scope_active` | `(scope_type, scope_id, role)` WHERE `revoked_at IS NULL` | Katılımcı listesi (`GET /operations/{id}/participants`), `{operation: op}` hedef kitle çözümü, kapanışta toplu pasifleştirme |

"Kullanıcı X operasyon Y'nin katılımcısı mı" (her istekte, AC-09.7) sorusu dört kolonda da eşitlik olduğundan `ux_role_assignments_active` ile tek indeks aramasıyla yanıtlanır; backend §15.5'te önerilen ayrı `(scope_id, user_id) WHERE role = 'GON'` indeksi gerekmez.

\* `INVITATION_DECLINED` yalnızca katılım davetten önce oluşturuluyorsa gerekir; TD-01'e göre katılım kabulde oluştuğundan büyük olasılıkla kullanılmaz (T-03'te netleşecek).

**Çok biçimli kapsam yerine iki FK kolonu (değerlendirildi, seçilmedi):** `organization_id` ve `operation_id` ayrı nullable kolonlar + "tam biri dolu" `CHECK` gerçek FK sağlardı. Ancak mantıksal model ve API (`roles[]: role, scopeType, scopeId`) çok biçimli yapıdadır ve operasyon tek bir kurumdadır. Yetim kayıt riski haftalık denetim sorgusuyla (§8.4) karşılanır.

### 4.2 `organization.organizations`

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | ERD |
| `name` | text | Hayır | – | Kurgusal senaryo kurumu | VS-01 |
| `created_at` | timestamptz | Hayır | `now()` | | FİZ |

`pk_organizations (id)`. MVP'de tek satır (A-01). "5 aktif operasyon" bir kapasite hedefidir, zorlanmaz (LD-20); bu satırda kilit gerekmez.

### 4.3 `organization.operations` (Operation)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `organization_id` | uuid | Hayır | – | | DM |
| `name` | text | Hayır | – | | DM |
| `description` | text | Evet | – | | DM |
| `state` | text | Hayır | `'PLANNED'` | `PLANNED`, `ACTIVE`, `SUSPENDED`*, `COMPLETED`, `CANCELLED` | DM, AC-08.1 |
| `planned_end_at` | timestamptz | Evet | – | | DM |
| `activated_at` | timestamptz | Evet | – | İlk etkinleştirme | DM |
| `closed_at` | timestamptz | Evet | – | `COMPLETED`/`CANCELLED` zamanı | DM |
| `closed_by` | uuid | Evet | – | | FİZ (AC-08.4) |
| `close_reminder_sent_at` | timestamptz | Evet | – | "Planlanan bitiş + 2 sa" hatırlatmasının bir kez gitmesi için | FİZ (§5 parametresi) |
| `created_by` | uuid | Hayır | – | | DM |
| `created_at`, `updated_at` | timestamptz | Hayır | `now()` | | FİZ |

\* `SUSPENDED` Genişletilmiş MVP'dir. Değer `CHECK` listesinde baştan bulunur (sonradan kısıt değiştirmemek için); geçişe uygulama Çekirdek'te izin vermez.

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_operations` | `(id)` | |
| `fk_operations_organization` | → `organization.organizations` RESTRICT | |
| `fk_operations_created_by`, `fk_operations_closed_by` | → `identity.users` RESTRICT | |
| `ck_operations_state` | Enum | |
| `ck_operations_closed` | `(state IN ('COMPLETED','CANCELLED')) = (closed_at IS NOT NULL)` | |
| `ck_operations_activated` | `state NOT IN ('ACTIVE','SUSPENDED','COMPLETED') OR activated_at IS NOT NULL` | |
| `ix_operations_org_state` | `(organization_id, state)` | Operasyon listesi, aktif operasyon sayımı |
| `ix_operations_close_reminder` | `(planned_end_at)` WHERE `state = 'ACTIVE' AND close_reminder_sent_at IS NULL` | Kapatılmamış operasyon hatırlatma işi |

Durum geçiş tablosu (AC-08.1) uygulamadadır; `UPDATE … WHERE id = ? AND state = :beklenen` biçimiyle yazılır, etkilenen satır 0 ise `INVALID_STATE_TRANSITION`.

---

## 5. groups ve tasks şemaları

### 5.1 `groups.groups` (Group; Çekirdekte toplanma noktası alanlarıyla)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `organization_id` | uuid | Hayır | – | | DM |
| `operation_id` | uuid | Evet | – | `OPERATIONAL`'da zorunlu (MVP'de hep dolu). R3 `COMMUNITY` için nullable | DM §3.2 |
| `name` | text | Hayır | – | | DM |
| `description` | text | Evet | – | | DM |
| `type` | text | Hayır | `'OPERATIONAL'` | MVP: yalnızca `OPERATIONAL` | DM, HZ-2 |
| `join_policy` | text | Hayır | `'MANAGED'` | MVP: yalnızca `MANAGED` | DM |
| `mp_address` | text | Evet | – | Toplanma noktası adresi | ERD, AC-12.1 |
| `mp_lat`, `mp_lon` | double precision | Evet | – | | ERD |
| `mp_description` | text | Evet | – | | ERD |
| `mp_time` | timestamptz | Evet | – | Toplanma zamanı | ERD |
| `mp_updated_by` | uuid | Evet | – | | ERD, AC-12.3 |
| `mp_updated_at` | timestamptz | Evet | – | | ERD, AC-12.3 |
| `created_by` | uuid | Hayır | – | | DM |
| `created_at`, `updated_at` | timestamptz | Hayır | `now()` | | FİZ |
| `archived_at` | timestamptz | Evet | – | Operasyon kapanınca dolar (K-11) | DM |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_groups` | `(id)` | |
| `fk_groups_organization` | → `organization.organizations` RESTRICT | |
| `fk_groups_operation` | → `organization.operations` RESTRICT | |
| `fk_groups_created_by`, `fk_groups_mp_updated_by` | → `identity.users` RESTRICT | |
| `ck_groups_type` | `type IN ('OPERATIONAL')` (R2/R3'te genişler) | |
| `ck_groups_join_policy` | `join_policy IN ('MANAGED')` | |
| `ck_groups_operational_has_operation` | `type <> 'OPERATIONAL' OR operation_id IS NOT NULL` | |
| `ck_groups_mp_coords` | `(mp_lat IS NULL) = (mp_lon IS NULL)` ve `mp_lat BETWEEN -90 AND 90`, `mp_lon BETWEEN -180 AND 180` | |
| `ck_groups_mp_audit` | `(mp_updated_at IS NULL) = (mp_updated_by IS NULL)` | |
| `ux_groups_operation_name` *(öneri)* | `(operation_id, lower(name))` WHERE `archived_at IS NULL` | Aynı operasyonda iki aynı adlı grup karışıklığını önler (§12.4) |
| `ix_groups_operation` | `(operation_id)` WHERE `archived_at IS NULL` | Operasyonun grupları, kapanışta arşivleme |

**Grup satırı kilit noktasıdır:** Üye ekleme/çıkarma ve lider değişikliği bu satırı `FOR UPDATE` ile kilitler (§6.2). Toplanma noktası güncellemesi de satırı günceller; son yazan kazanır (MVP'de sürüm denetimi yok, her güncelleme bildirim ve denetim üretir).

### 5.2 `groups.group_memberships` (GroupMembership)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | Aynı (grup, kullanıcı) ikilisinin zaman içinde birden çok üyeliği olabildiği için vekil anahtar | FİZ (DM §3.3 geçmişin korunması) |
| `group_id` | uuid | Hayır | – | | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `role` | text | Hayır | `'MEMBER'` | `LEADER`, `MEMBER` | DM |
| `status` | text | Hayır | `'ACTIVE'` | `ACTIVE`, `REMOVED` | DM |
| `added_by` | uuid | Hayır | – | | DM |
| `joined_at` | timestamptz | Hayır | `now()` | | DM |
| `ended_at` | timestamptz | Evet | – | | DM |
| `ended_by` | uuid | Evet | – | Otomatik sonlanmada boş | FİZ (AC-09.4) |
| `end_reason` | text | Evet | – | `REMOVED_BY_COORDINATOR`, `PARTICIPATION_REVOKED`, `ACCOUNT_CLOSED` | DM |
| `updated_at` | timestamptz | Hayır | `now()` | Rol değişikliğinde de güncellenir | FİZ |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_group_memberships` | `(id)` | |
| `fk_group_memberships_group` | → `groups.groups` RESTRICT | |
| `fk_group_memberships_user`, `fk_group_memberships_added_by`, `fk_group_memberships_ended_by` | → `identity.users` RESTRICT | |
| `ck_group_memberships_role`, `ck_group_memberships_status`, `ck_group_memberships_end_reason` | Enum | |
| `ck_group_memberships_ended` | `(status = 'ACTIVE') = (ended_at IS NULL)` | |
| `ux_group_memberships_active_member` | `(group_id, user_id)` WHERE `status = 'ACTIVE'` | DM §5B tek aktif üyelik; grup üyeleri listesi |
| `ux_group_memberships_active_leader` | `(group_id)` WHERE `role = 'LEADER' AND status = 'ACTIVE'` | K-32: **en fazla** bir aktif lider (veritabanı garantisi). **En az** bir lider kuralı uygulamada (§6.2) |
| `ix_group_memberships_user_active` | `(user_id)` WHERE `status = 'ACTIVE'` | "Gruplarım" (`GET /groups`, AC-10.1), `can()` grup katmanı, `GET /me/context` |

**Arşivlenen grup:** Operasyon kapanınca grup `archived_at` alır; üyelik satırları **değiştirilmez** (500'e kadar gereksiz güncelleme yapılmaz, "kim üyeydi" bilgisi korunur). Erişim kontrolü ve listeler `groups.archived_at IS NULL` koşulunu da uygular. Böylece "her aktif grupta tam bir lider" kuralı arşivli grupta anlamını yitirir (§13 AN-15).

**Katılımcı kuralı (AC-09.7, TD-01):** "Gruba yalnızca operasyon katılımcısı eklenebilir" kuralı modüller arası olduğundan kısıtla ifade edilemez. Uygulama, ekleme işleminde `access` modülünün "katılımcı mı" sorgusunu (`ix_role_assignments_scope_active`) aynı işlemde çağırır. Katılım iptali (operasyondan çıkarma) ile eşzamanlı ekleme yarışı MVP'de kabul edilebilir risktir; katılım iptali olayı aktif üyelikleri `PARTICIPATION_REVOKED` ile sonlandırır.

### 5.3 `tasks.tasks` (Task)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `operation_id` | uuid | Hayır | – | | DM |
| `title` | text | Hayır | – | | DM, AC-13.1 |
| `description` | text | Evet | – | | DM |
| `priority` | text | Hayır | `'NORMAL'` | `LOW`, `NORMAL`, `HIGH`, `URGENT` (backend önerisiyle mutabık; team-lead onayı §13 AN-08) | DM |
| `meeting_point` | text | Evet | – | Serbest metin yer/buluşma noktası | DM |
| `time_window` | tstzrange | Evet | – | `[başlangıç, bitiş)` | DM, ERD |
| `capacity` | integer | Evet | – | Boş = sınırsız | K-12, AC-13.6 |
| `accepted_count` | integer | Hayır | `0` | `ACCEPTED` atama sayısı. Kapasite kilidi altında güncellenir; listede "3/5" gösterimi için JOIN/COUNT gerekmez | FİZ (K-12, AC-13.6) |
| `response_window_minutes` | integer | Hayır | `60` | DM'deki `response_deadline`'ın fiziksel karşılığı: süre görevde, son an atamada (`task_assignments.respond_by`). Elle eklenen atama da kendi süresini alır. Backend N-17 ile mutabık | DM, K-12, AC-16.3 |
| `target_expr` | jsonb | Evet | – | Taslağın hedefleri `{users:[…], groups:[…]}`. Yayında atamalara dönüşür (TD-16); sonra yalnızca bilgi amaçlı | FİZ (§13 AN-01) |
| `state` | text | Hayır | `'DRAFT'` | `DRAFT`, `PUBLISHED`, `IN_PROGRESS`, `DONE`, `CANCELLED` | DM |
| `version` | integer | Hayır | `1` | İyimser kilit; oluşturmada 1 (backend TK-01), her düzenlemede +1 | TD-05, API §1 |
| `created_by` | uuid | Hayır | – | | DM, AC-13.3 |
| `created_at`, `updated_at` | timestamptz | Hayır | `now()` | | AC-13.3 |
| `published_at` | timestamptz | Evet | – | | FİZ |
| `completed_at`, `cancelled_at` | timestamptz | Evet | – | | FİZ |
| `state_changed_by` | uuid | Evet | – | Son durum değişikliğini yapan; tam geçmiş `tasks.task_state_changes` tablosunda (§5.5) | FİZ |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_tasks` | `(id)` | |
| `fk_tasks_operation` | → `organization.operations` RESTRICT | |
| `fk_tasks_created_by`, `fk_tasks_state_changed_by` | → `identity.users` RESTRICT | |
| `ck_tasks_state`, `ck_tasks_priority` | Enum | |
| `ck_tasks_capacity_positive` | `capacity IS NULL OR capacity > 0` | |
| **`ck_tasks_capacity_not_exceeded`** | `capacity IS NULL OR accepted_count <= capacity` | **K-12'nin veritabanı güvencesi.** Uygulama kilidi hatalı olsa bile kapasite aşılamaz. Kapasite düşürme isteğinde aynı kısıt `CAPACITY_BELOW_ACCEPTED` olarak döner (AC-13.7) |
| `ck_tasks_accepted_nonneg` | `accepted_count >= 0` | |
| `ck_tasks_response_window` | `response_window_minutes BETWEEN 5 AND 1440` | Backend §9.3 doğrulamasıyla aynı |
| `ck_tasks_time_window` | `time_window IS NULL OR (NOT isempty(time_window) AND NOT lower_inf(time_window))` | Başlangıçsız aralık yoktur: API yalnızca `end` gelirse `400 timeWindow.start REQUIRED` döner (LD-53) |
| `ck_tasks_published` | `state IN ('DRAFT', 'CANCELLED') OR published_at IS NOT NULL` | Taslak yayınlanmadan iptal edilebilir (backend §9.2 `DRAFT → CANCELLED`, BOŞLUK-14; LD-53). V6 migration'ı bu tanımı uygular |
| `ck_tasks_target_expr` | `target_expr IS NULL OR jsonb_typeof(target_expr) = 'object'` | |
| `ix_tasks_operation` | `(operation_id, id DESC)` | `GET /operations/{id}/tasks` imleç sayfalaması (en yeni önce) |
| `ix_tasks_operation_open` | `(operation_id)` WHERE `state IN ('PUBLISHED','IN_PROGRESS')` | Açık görev panosu, kapanışta toplu işlem |

### 5.4 `tasks.task_assignments` (TaskAssignment)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | `/assignments/{id}` | FİZ (API §3.3) |
| `task_id` | uuid | Hayır | – | | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `via_group_id` | uuid | Evet | – | Atamanın geldiği grup (yayın anı anlık görüntüsü); kişi hedefinde ve elle atamada boş | FİZ (§13 AN-02) |
| `source` | text | Hayır | `'SNAPSHOT'` | `SNAPSHOT` (yayın), `MANUAL` (KOR elle ekledi, TD-16) | FİZ |
| `status` | text | Hayır | `'PENDING'` | `PENDING`, `ACCEPTED`, `REJECTED`, `CAPACITY_FULL`, `REMOVED` | DM |
| `assigned_at` | timestamptz | Hayır | `now()` | | FİZ |
| `assigned_by` | uuid | Hayır | – | Yayınlayan veya elle ekleyen KOR | FİZ (AC-13.3) |
| `respond_by` | timestamptz | Hayır | – | `assigned_at + tasks.response_window_minutes`; yeniden `PENDING`'e alınınca yeniden hesaplanır. `overdue` = `status = 'PENDING' AND respond_by < now()` | FİZ (backend N-17, AC-16.3) |
| `responded_at` | timestamptz | Evet | – | Sunucu zamanı | DM, AC-14.1 |
| `removed_at` | timestamptz | Evet | – | | FİZ |
| `removed_by` | uuid | Evet | – | | DM |
| `updated_at` | timestamptz | Hayır | `now()` | SSE telafi / yoklama deltası | FİZ |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_task_assignments` | `(id)` | |
| `fk_task_assignments_task` | → `tasks.tasks` RESTRICT | |
| `fk_task_assignments_user`, `..._assigned_by`, `..._removed_by` | → `identity.users` RESTRICT | |
| `fk_task_assignments_via_group` | → `groups.groups` RESTRICT | |
| `uq_task_assignments_task_user` | `(task_id, user_id)` | DM §5B tek atama; atama tablosu (görev detayı), kapasite dolunca `PENDING` toplu güncellemesi |
| `ck_task_assignments_status`, `ck_task_assignments_source` | Enum | |
| `ck_task_assignments_responded` | `status NOT IN ('ACCEPTED','REJECTED') OR responded_at IS NOT NULL` | |
| `ck_task_assignments_removed` | `(status = 'REMOVED') = (removed_at IS NOT NULL)` | |
| `ix_task_assignments_user` | `(user_id, task_id)` | GON/LDR "görevlerim" (AC-16.1), kendi atamaları; `can(task.respond)` |
| `ix_task_assignments_pending` | `(task_id, respond_by)` WHERE `status = 'PENDING'` | `TaskSummary.pendingCount`, Genişletilmiş AC-16.2 filtresi, `overdue?` filtresi (backend TK-09) |

**Yeniden atama (backend ile mutabık):** Aynı kişinin tekrar atanması (TK-05) `uq_task_assignments_task_user` nedeniyle yeni satırla değil, mevcut `REMOVED`/`REJECTED`/`CAPACITY_FULL` satırının `UPDATE` ile `PENDING`'e döndürülmesiyle yapılır: `source = 'MANUAL'`, `assigned_at`, `assigned_by`, `respond_by` yenilenir; `responded_at`, `removed_at`, `removed_by` temizlenir. Önceki durum denetim kaydındadır. Bu geçiş domain durum makinesinde tanımlı değildir (§13 AN-09).

### 5.5 `tasks.task_state_changes` (görev durum geçmişi, AC-15.2) — *FİZ, **LD-24** ile kabul*

Backend `TaskView.history[]` (`state`, `changedBy`, `changedAt`) KOR ve ADM'ye gösterilir. Bu geçmiş denetim kaydından okunamaz: denetimi yalnızca ADM/UYM okur (K-26) ve `tasks` modülünün `audit` modülünün verisini okuması modül yönünü tersine çevirir (ADR-001; audit tüketicidir). Bu yüzden görev modülüne ait küçük, yalnızca eklenen bir tablo önerilir.

| Kolon | Tip | NULL | Varsayılan | Açıklama |
|---|---|---|---|---|
| `id` | uuid | Hayır | – | UUIDv7 |
| `task_id` | uuid | Hayır | – | |
| `from_state` | text | Evet | – | Oluşturmada boş |
| `to_state` | text | Hayır | – | `TaskState` değerleri |
| `changed_by` | uuid | Hayır | – | |
| `changed_at` | timestamptz | Hayır | `now()` | |

Kısıtlar: `pk_task_state_changes`, `fk_task_state_changes_task` (RESTRICT), `fk_task_state_changes_changed_by` (→ `identity.users`, RESTRICT), `ck_task_state_changes_states` (her iki kolon `TaskState` listesinde). İndeks: `ix_task_state_changes_task (task_id, id)`. Durum değişikliğiyle **aynı işlemde** eklenir. Hacim: görev başına ≤ 5 satır.

---

## 6. location, alerts ve notifications şemaları

### 6.1 `location.location_sharings` (LocationSharing)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `user_id` | uuid | Hayır | – | | DM |
| `operation_id` | uuid | Hayır | – | | DM |
| `state` | text | Hayır | `'OFF'` | `OFF`, `ACTIVE`, `UNAVAILABLE`, `STOPPED` | DM, AC-17.1 |
| `started_at` | timestamptz | Evet | – | Son başlatma | DM |
| `stopped_at` | timestamptz | Evet | – | Son durdurma | DM |
| `availability_reason` | text | Evet | – | `UNAVAILABLE` iken cihazın bildirdiği neden: `PERMISSION_DENIED`, `GPS_OFF`, `NO_FIX` (backend LC-01 gövdesi, N-07) | FİZ |
| `stop_reason` | text | Evet | – | `USER`, `OPERATION_CLOSED`, `OPERATION_SUSPENDED` (G), `PARTICIPATION_REVOKED`, `SESSION_ENDED`, `ACCOUNT_DISABLED`. Backend `stopReason` enumuna son üç değerin eklenmesi önerildi (§12.2) | FİZ (TD-18, denetim) |
| `updated_at` | timestamptz | Hayır | `now()` | | FİZ |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_location_sharings` | `(user_id, operation_id)` | DM §5B tek paylaşım; `GET /me/location-sharing` |
| `fk_location_sharings_user` / `_operation` | → `identity.users` / `organization.operations` RESTRICT | |
| `ck_location_sharings_state`, `ck_location_sharings_stop_reason` | Enum | |
| `ck_location_sharings_started` | `state <> 'ACTIVE' OR started_at IS NOT NULL` | |
| `ck_location_sharings_availability` | `(state = 'UNAVAILABLE') = (availability_reason IS NOT NULL)` | |
| `ix_location_sharings_operation_active` | `(operation_id)` WHERE `state = 'ACTIVE'` | Kapanışta "tüm paylaşımları durdur" (≤ 1 dk, TD-18), KOR görünümünde paylaşım durumu |

"`ACTIVE` yalnızca operasyon `ACTIVE` iken" (DM §5B) modüller arası bir kuraldır: başlatma işlemi operasyon satırını `FOR SHARE` ile okur (§7.3); kapanış zinciri paylaşımları `STOPPED` yapar. Ölçüm kabulünde operasyon durumu **ve** paylaşım durumu birlikte denetlenir, bu yüzden kapanışın ardından gelen ölçüm zincir tamamlanmadan da reddedilir.

### 6.2 `location.last_locations` (LastLocation, K-16)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `user_id` | uuid | Hayır | – | | DM |
| `operation_id` | uuid | Hayır | – | | DM |
| `lat` | double precision | Hayır | – | | DM |
| `lon` | double precision | Hayır | – | | DM |
| `accuracy` | double precision | Evet | – | Metre | DM |
| `measured_at` | timestamptz | Hayır | – | Cihaz zamanı; güncellik buna göre (2/15 dk) | DM, AC-19.3 |
| `received_at` | timestamptz | Hayır | `now()` | Sunucu zamanı | DM |
| `clock_skew_flag` | boolean | Hayır | `false` | `abs(received_at − measured_at) > 2 dk` | DM, AC-19.3 |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_last_locations` | `(user_id, operation_id)` | DM §5B tek son konum; üzerine yazma (upsert) |
| `fk_last_locations_user` / `_operation` | RESTRICT | |
| `ck_last_locations_coords` | `lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180` | |
| `ck_last_locations_accuracy` | `accuracy IS NULL OR accuracy >= 0` | |
| `ix_last_locations_operation` | `(operation_id)` | `GET /operations/{id}/locations` (KOR haritası, ≤ 500 satır) |

**Yazma biçimi:** Tek ifadeli upsert: "ekle; çakışırsa yalnızca gelen `measured_at` mevcut olandan yeniyse güncelle". Böylece gecikmeli gelen eski ölçüm yeni ölçümün üzerine yazılmaz (TD-11 ile uyumlu). Saklama ayarları:
- `fillfactor = 70`: Güncellenen kolonların hiçbiri indekste olmadığından güncellemeler **HOT** olur; indeks şişmesi ve yazma maliyeti düşer.
- Tablo bazında otomatik vakum: `autovacuum_vacuum_scale_factor = 0`, `autovacuum_vacuum_threshold = 1000`. 150 paylaşımcı × 60 sn = dakikada ~150 güncelleme; **LD-15** gereği iki aktif operasyonda paylaşım yapan kişi aynı ölçümü her operasyona ayrı gönderdiğinden en kötü durumda dakikada ~300 güncelleme (her biri kendi `(user_id, operation_id)` satırına). Ölü satırlar 3 – 7 dk'da bir temizlenir.
- LD-15'in veritabanına başka etkisi yoktur: her operasyonun satırı bağımsızdır, operasyon kapanışı yalnızca kendi satırlarını etkiler.

**Kapanışta silme (LD-25):** Operasyon `COMPLETED`/`CANCELLED` olunca o operasyonun satırları kapanış zincirinde silinir (§9.4).

**Geçmiş yok (K-16):** Tablo yalnızca son konumu tutar; tetikleyici, günlük tablosu veya CDC tanımlanmaz. Konum sorgularının denetimi (AC-18.3) `audit_entries`'e koordinat **içermeden** yazılır.

### 6.3 `alerts.alerts` (Alert)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `operation_id` | uuid | Hayır | – | | DM |
| `message` | text | Hayır | – | | DM, AC-20.1 |
| `requested_action` | text | Evet | – | | DM |
| `priority` | text | Hayır | `'CRITICAL'` | `HIGH`, `CRITICAL` (backend ile mutabık, §13 AN-08). Bildirim önceliği bundan bağımsız olarak her zaman `CRITICAL` (ADR-004) | DM |
| `state` | text | Hayır | `'DRAFT'` | `DRAFT`, `SENT`, `OPEN`, `CANCELLED`, `CLOSED` | DM |
| `version` | integer | Hayır | `1` | Taslak sürümü (oluşturmada 1, backend AL-01) | TD-05 |
| `target_expr` | jsonb | Hayır | – | Seçilen hedefler `{users:[…], groups:[…]}`; gönderimde yeniden çözülür | FİZ (§13 AN-01) |
| `audience_fingerprint` | text | Evet | – | Son önizlemede çözülen alıcı `userId` listesinin sıralı SHA-256 hex değeri (backend `audienceHash`, N-02). Gönderim kararı istemcinin getirdiği değerle yeniden hesaplanan değerin karşılaştırılmasına dayanır; kolon izlenebilirlik ve denetim ayrıntısı içindir | FİZ (TD-05, AC-20.8) |
| `preview_recipient_count` | integer | Evet | – | Son önizlemedeki alıcı sayısı (≤ 500) | FİZ |
| `previewed_at` | timestamptz | Evet | – | | FİZ |
| `recipient_count` | integer | Evet | – | Gönderimde sabitlenen alıcı sayısı (değişmez) | FİZ (AC-20.4) |
| `reminder_interval_seconds` | integer | Hayır | `120` | `reminder_policy` "2 dk × 5"in fiziksel hâli | DM, K-13 |
| `reminder_max` | integer | Hayır | `5` | | DM, K-13 |
| `corrects_alert_id` | uuid | Evet | – | Düzeltilen (iptal edilmiş) alarm | DM, TD-09 |
| `idempotency_key` | text | Evet | – | Gönderim isteğinin anahtarı | DM, AC-20.5 |
| `created_by` | uuid | Hayır | – | Taslağı oluşturan | DM |
| `created_at`, `updated_at` | timestamptz | Hayır | `now()` | | FİZ |
| `sent_by` | uuid | Evet | – | Onaylayıp gönderen (taslağı başka bir KOR göndermiş olabilir) | FİZ (AC-20.4) |
| `sent_at` | timestamptz | Evet | – | | DM |
| `cancelled_by`, `cancelled_at` | uuid / timestamptz | Evet | – | | DM, AC-25.2 |
| `closed_by` | uuid | Evet | – | Otomatik kapanışta boş | DM |
| `closed_at` | timestamptz | Evet | – | | DM |
| `close_reason` | text | Evet | – | `MANUAL`, `OPERATION_CLOSED` | DM, TD-06 |

`response_options` saklanmaz: K-13 gereği sabit üç seçenektir ve `alert_recipients.response_option` kısıtında tanımlıdır; API sabit listeyi döner (§13 AN-07). R2 şablonları geldiğinde kolon eklenir.

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_alerts` | `(id)` | |
| `fk_alerts_operation` | → `organization.operations` RESTRICT | |
| `fk_alerts_created_by`, `_sent_by`, `_cancelled_by`, `_closed_by` | → `identity.users` RESTRICT | |
| `fk_alerts_corrects` | → `alerts.alerts(id)` RESTRICT | |
| `ux_alerts_corrects` | `(corrects_alert_id)` WHERE `corrects_alert_id IS NOT NULL` | TD-09: bir alarm en fazla bir kez düzeltilir |
| `ck_alerts_not_self_correct` | `corrects_alert_id IS NULL OR corrects_alert_id <> id` | |
| – | `idempotency_key` için benzersizlik **yok** (backend ile mutabık): tekrar koruması `platform.idempotency_keys` ve `DRAFT → OPEN` geçişinin doğal idempotentliğidir (24 sa sonra gelen tekrar `409 INVALID_STATE_TRANSITION` alır). Kolon izlenebilirlik içindir | |
| `ck_alerts_state`, `ck_alerts_priority`, `ck_alerts_close_reason` | Enum | |
| `ck_alerts_sent_fields` | `(state = 'DRAFT') = (sent_at IS NULL)` ve `state = 'DRAFT' OR (sent_by IS NOT NULL AND recipient_count IS NOT NULL)` | |
| `ck_alerts_cancelled` | `(state = 'CANCELLED') = (cancelled_at IS NOT NULL)` | |
| `ck_alerts_closed` | `(state = 'CLOSED') = (closed_at IS NOT NULL AND close_reason IS NOT NULL)` | |
| `ck_alerts_manual_close_actor` | `close_reason IS DISTINCT FROM 'MANUAL' OR closed_by IS NOT NULL` | |
| `ck_alerts_recipient_count` | `recipient_count IS NULL OR recipient_count BETWEEN 0 AND 500` | VS-02 (yapılandırılabilir üst sınır değişirse kısıt da değişir) |
| `ck_alerts_reminder` | `reminder_interval_seconds > 0 AND reminder_max BETWEEN 0 AND 10` | |
| `ix_alerts_operation` | `(operation_id, id DESC)` | `GET /operations/{id}/alerts` imleç sayfalaması |
| `ix_alerts_operation_open` | `(operation_id)` WHERE `state = 'OPEN'` | Operasyon kapanışında açık alarmları kapatma (TD-06), panel "açık alarmlar" |

**Sayaç yok:** `AlertView.counts` alıcı tablosundan toplama ile hesaplanır (≤ 500 satır, §7.2). Alarm satırında sayaç tutulmaz; aksi hâlde 500 eşzamanlı yanıt aynı satırı güncellemek için sıraya girer (sıcak satır çekişmesi).

### 6.4 `alerts.alert_recipients` (AlertRecipient, TD-03)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | `/alert-recipients/{id}`; FCM mesajındaki `recipient_id` | DM |
| `alert_id` | uuid | Hayır | – | | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `via_group_id` | uuid | Evet | – | Alıcının geldiği grup (panelde "grup" sütunu, `AlertRecipientView.group`) | FİZ (§13 AN-02) |
| `state` | text | Hayır | `'QUEUED'` | `QUEUED`, `SENT`, `DELIVERED`, `SEEN`, `RESPONDED`, `UNANSWERED`, `FAILED` | DM |
| `response_option` | text | Evet | – | `RECEIVED`, `NEED_HELP`, `ON_MY_WAY` (son yanıt) | DM, K-13 |
| `responded_at_device` | timestamptz | Evet | – | Son yanıtın cihaz zamanı | DM, AC-19.3 benzeri |
| `responded_at_server` | timestamptz | Evet | – | Son yanıtın sunucu zamanı | DM, AC-22.2 |
| `first_responded_at` | timestamptz | Evet | – | İlk yanıtın sunucu zamanı (hatırlatma bu anda durur; yanıt süresi ölçümü) | FİZ (TD-07) |
| `response_idempotency_key` | text | Evet | – | Son işlenen yanıtın anahtarı | DM |
| `queued_at` | timestamptz | Hayır | `now()` | Anlık görüntü zamanı | FİZ |
| `sent_at` | timestamptz | Evet | – | İlk başarılı FCM kabulü ("Gönderildi" sütunu) | FİZ (AC-24.1, `AlertRecipientView.sentAt`) |
| `delivered_at` | timestamptz | Evet | – | | DM |
| `seen_at` | timestamptz | Evet | – | | DM |
| `reminder_count` | integer | Hayır | `0` | | DM |
| `next_reminder_at` | timestamptz | Evet | – | Gönderim + 2 dk; yanıt, iptal, kapanış, `FAILED`/`UNANSWERED` geçişinde `NULL` | DM |
| `resend_count` | integer | Hayır | `0` | KOR'un elle yeniden gönderim sayısı; teslim kaydında `round` olarak kullanılır (§6.6) | FİZ (AC-23.4) |
| `failure_reason` | text | Evet | – | `NO_DEVICE`, `TOKEN_INVALID`, `FCM_UNAVAILABLE` (geçici hata denemeleri tükendi veya servis erişilemez), `FCM_REJECTED` (backend enumuyla mutabık) | DM, AC-24.3 |
| `updated_at` | timestamptz | Hayır | `now()` | Yoklama/telafi deltası | FİZ |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_alert_recipients` | `(id)` | |
| `fk_alert_recipients_alert` | → `alerts.alerts` RESTRICT | |
| `fk_alert_recipients_user` | → `identity.users` RESTRICT | |
| `fk_alert_recipients_via_group` | → `groups.groups` RESTRICT | |
| `uq_alert_recipients_alert_user` | `(alert_id, user_id)` | DM §5B tekillik; alıcı listesi ve sayımlar (`alert_id` öneki), teslim işçisinin (alarm, kullanıcı) → alıcı araması |
| `ck_alert_recipients_state`, `ck_alert_recipients_response_option`, `ck_alert_recipients_failure_reason` | Enum | |
| `ck_alert_recipients_response` | `(state = 'RESPONDED') = (response_option IS NOT NULL)` | Yanıtlı satır başka duruma geçemez (durum makinesi §1.2) |
| `ck_alert_recipients_response_times` | `response_option IS NULL OR (responded_at_server IS NOT NULL AND first_responded_at IS NOT NULL)` | |
| `ck_alert_recipients_failed` | `(state = 'FAILED') = (failure_reason IS NOT NULL)` — yeniden gönderimde `failure_reason` temizlenir | |
| `ck_alert_recipients_reminder` | `reminder_count BETWEEN 0 AND 10` | |
| **`ix_alert_recipients_reminder_due`** | `(next_reminder_at)` WHERE `state IN ('SENT','DELIVERED','SEEN') AND next_reminder_at IS NOT NULL` | **Hatırlatma zamanlayıcısı** (30 sn tarama, §7.1). Kısmi olduğu için yalnızca bekleyen alıcıları içerir: en kötü durumda 5 × 500 = 2 500 satır |
| `ix_alert_recipients_user` | `(user_id, alert_id)` | Android açılışında "açık alarmlarım" (TD-10), `GET /alerts/{id}` alıcı yetkisi |

Gönderenin alıcı olmaması (DM §5B) tablo arası bir kuraldır; hedef kitle çözümünde uygulanır ve testle güvenceye alınır.

### 6.5 `notifications.notifications` (Notification)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `category` | text | Hayır | – | `ALERT`, `TASK`, `ANNOUNCEMENT`, `MEETING_POINT`, `OPERATION_INVITE`, `SYSTEM` | DM |
| `kind` | text | Hayır | – | Önerilen liste §12.2 | DM |
| `priority` | text | Hayır | – | `CRITICAL`, `HIGH`, `NORMAL`, `LOW` | DM |
| `title` | text | Hayır | – | Kısa (kilit ekranı) | DM |
| `body` | text | Evet | – | Duyuru metni | DM |
| `source_type` | text | Evet | – | `ALERT`, `TASK`, `TASK_ASSIGNMENT`, `GROUP`, `OPERATION`, `INVITATION`; duyuruda boş | DM |
| `source_id` | uuid | Evet | – | Çok biçimli, FK yok | DM |
| `operation_id` | uuid | Evet | – | İlgili operasyon; kurum geneli duyuruda boş | FİZ (WEB-07 duyuru listesi, §13 AN-13) |
| `audience_expr` | jsonb | Hayır | – | `{users:[…]}`, `{groups:[…]}`, `{operation: …}`, `{organization: true}` | DM §5 |
| `recipient_count` | integer | Evet | – | Fan-out sonrası sabit alıcı sayısı | FİZ |
| `created_by` | uuid | Evet | – | Sistem bildiriminde boş | DM |
| `created_at` | timestamptz | Hayır | `now()` | | DM |
| `idempotency_key` | text | Hayır | – | **Kaynak olay anahtarı** (backend §15.4 ile mutabık): outbox tüketicisinin en-az-bir-kez teslimine karşı, ör. `alert:{id}:SENT`, `alert:{id}:CANCELLED`, `task:{id}:PUBLISHED`, `assignment:{id}:ADDED:{n}`, `announcement:{istekAnahtarı}`. HTTP `Idempotency-Key`'i ayrıca `platform.idempotency_keys`'tedir | DM |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_notifications` | `(id)` | |
| `fk_notifications_created_by` | → `identity.users` RESTRICT | |
| `fk_notifications_operation` | → `organization.operations` RESTRICT | |
| `ck_notifications_category`, `ck_notifications_kind`, `ck_notifications_priority`, `ck_notifications_source_type` | Enum | |
| `ck_notifications_source_pair` | `(source_type IS NULL) = (source_id IS NULL)` | |
| `ck_notifications_alert_critical` | `kind NOT IN ('ALERT_SENT','ALERT_CANCELLED') OR priority = 'CRITICAL'` | ADR-004, `communication.md` §1.2 |
| `ck_notifications_audience` | `jsonb_typeof(audience_expr) = 'object'` | |
| `uq_notifications_idempotency_key` | `(idempotency_key)` | DM §5B'nin fiziksel hâli: anahtar kaynak olaydan türediği için gönderen başına değil **genel** benzersiz; sistem bildirimlerinde (`created_by` boş) de çalışır. İhlal = olay tekrarı → sessizce yok sayılır |
| `ux_notifications_alert_lifecycle` | `(source_id, kind)` WHERE `kind IN ('ALERT_SENT','ALERT_CANCELLED','ALERT_CLOSED')` | Alarm başına tek gönderim, tek iptal, tek kapanış bildirimi (DM §2 1:N kardinalitesi). Anahtar biçimi yanlış üretilse bile kardinaliteyi korur. `ALERT_REMINDER` bir bildirim türü **değildir** (hatırlatma = teslim denemesi, §6.6) |
| `ix_notifications_source` | `(source_type, source_id)` | Alarm → bildirimleri; teslim işçisinin alarm bağlamını bulması |
| `ix_notifications_announcements` | `(operation_id, id DESC)` WHERE `category = 'ANNOUNCEMENT'` | WEB-07 duyuru listesi |

### 6.6 `notifications.deliveries` (Delivery; teslim kuyruğu)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | DM |
| `notification_id` | uuid | Hayır | – | | DM |
| `user_id` | uuid | Hayır | – | | DM |
| `device_id` | uuid | Evet | – | `PUSH`'ta zorunlu, `IN_APP`'te boş | DM |
| `channel` | text | Hayır | – | `PUSH`, `IN_APP` | DM |
| `attempt` | integer | Hayır | `0` | 0 = ilk gönderim, 1 – 5 = hatırlatma | DM, K-13 |
| `round` | integer | Hayır | `0` | 0 = ilk tur, 1.. = KOR'un elle yeniden gönderimi (`alert_recipients.resend_count`). Yeniden gönderimde `attempt` 0'dan başladığı için tekillik anahtarının parçasıdır | FİZ (§13 AN-04) |
| `queue_class` | text | Hayır | – | `CRITICAL`, `STANDARD`. Bildirimin önceliğinden türetilir; ayrı işçi havuzunun seçim ölçütü | FİZ (ADR-004) |
| `status` | text | Hayır | `'QUEUED'` | `QUEUED`, `SENT`, `FAILED` (`IN_APP` satırları `SENT` olarak oluşur) | DM |
| `send_tries` | integer | Hayır | `0` | FCM iletim denemesi (geçici hatada en fazla 3, VS-14). `attempt`'ten farklıdır | FİZ |
| `available_at` | timestamptz | Hayır | `now()` | İşçinin satırı alabileceği en erken an (üstel bekleme) | FİZ |
| `fcm_message_id` | text | Evet | – | | DM |
| `error_code` | text | Evet | – | FCM hata kodu (`UNREGISTERED`, `INVALID_ARGUMENT`, `UNAVAILABLE`…) | DM |
| `queued_at` | timestamptz | Hayır | `now()` | | DM |
| `sent_at` | timestamptz | Evet | – | | DM |
| `failed_at` | timestamptz | Evet | – | | FİZ |
| `read_at` | timestamptz | Evet | – | Yalnızca `IN_APP` | DM, TD-13 |

| Kısıt / indeks | Tanım | Hizmet |
|---|---|---|
| `pk_deliveries` | `(id)` | |
| `fk_deliveries_notification` | → `notifications.notifications` RESTRICT | |
| `fk_deliveries_user` / `_device` | → `identity.users` / `identity.devices` RESTRICT | |
| `ck_deliveries_channel`, `ck_deliveries_status`, `ck_deliveries_queue_class` | Enum | |
| `ck_deliveries_channel_device` | `(channel = 'PUSH') = (device_id IS NOT NULL)` | |
| `ck_deliveries_in_app_shape` | `channel = 'PUSH' OR (attempt = 0 AND round = 0)` | Gelen kutusunda bildirim başına tek satır; hatırlatma gelen kutusunu çoğaltmaz |
| `ck_deliveries_read_in_app` | `read_at IS NULL OR channel = 'IN_APP'` | |
| `ck_deliveries_attempt` | `attempt BETWEEN 0 AND 10 AND round >= 0 AND send_tries BETWEEN 0 AND 10` | |
| `ck_deliveries_sent` | `status <> 'SENT' OR sent_at IS NOT NULL` | |
| `ux_deliveries_push_unique` | `(notification_id, user_id, device_id, round, attempt)` WHERE `channel = 'PUSH'` | DM §5B / ADR-004: aynı deneme iki kez kuyruğa girmez; işçi tekrarında idempotent ekleme |
| `ux_deliveries_in_app_unique` | `(notification_id, user_id)` WHERE `channel = 'IN_APP'` | |
| **`ix_deliveries_queue`** | `(queue_class, available_at)` WHERE `status = 'QUEUED'` | **Teslim kuyruğu.** Kısmi olduğu için kuyruk boşken indeks de neredeyse boştur; tablo büyüse de işçi taraması sabit maliyetlidir (§7.1) |
| `ix_deliveries_inbox` | `(user_id, id DESC)` WHERE `channel = 'IN_APP'` | `GET /me/notifications` imleç sayfalaması |
| `ix_deliveries_unread` | `(user_id)` WHERE `channel = 'IN_APP' AND read_at IS NULL` | Okunmamış sayısı |
| `ix_deliveries_notification` | `(notification_id)` | Alarm detayında hata türleri, iptal bildirimi fan-out denetimi, dönem raporu |

**`api-and-data-design-basis.md` §4.3'ten sapma:** Orada kuyruk indeksi `(status, priority, queued_at)` olarak önerilmişti. Ayrı `CRITICAL` işçi havuzu (ADR-004) her zaman tek sınıfı sorguladığı ve yeniden deneme bekleme süresi gerektiği için `(queue_class, available_at) WHERE status = 'QUEUED'` kısmi indeksi seçildi. Anlam aynı, maliyet daha düşük.

---

### 6.7 `audit.location_view_windows` (LD-05 birleştirme ara tablosu) — *FİZ*

**Karar (LD-05):** Aynı kullanıcı + operasyon için 5 dk'lık pencerede tek `LOCATION_VIEWED` denetim kaydı oluşur; penceredeki sorgu sayısı `details`'e yazılır; birleştirme sunucudadır. `audit_entries` yalnızca ekleme kuralı korunur.

**Değerlendirilen seçenekler**

| Seçenek | Açıklama | Değerlendirme |
|---|---|---|
| A. Bellek içi sayaç, pencere sonunda tek ekleme | Uygulama sayacı tutar | **Reddedildi:** uygulama çökerse veya yeniden başlarsa konum **erişiminin kaydı tamamen kaybolur** (AC-18.3 ihlali) |
| B. İlk sorguda denetim kaydı ekle, sayacı ayrı tabloda tut | Kayıt hemen oluşur | **Reddedildi:** sayı denetim kaydına hiç yazılamaz (yalnızca ekleme), LD-05 "sayı `details`'te" koşulu sağlanmaz |
| **C. Kalıcı ara tablo + pencere kapanınca tek ekleme** | Sayaç veritabanında, pencere kapanınca denetime tek satır eklenir ve ara satır silinir | **Seçildi:** çökme güvenli (ara satır işlemle yazılır ve yedeğe girer), denetim yalnızca ekleme, sayı doğru |

| Kolon | Tip | NULL | Varsayılan | Açıklama |
|---|---|---|---|---|
| `actor_id` | uuid | Hayır | – | Görüntüleyen KOR |
| `operation_id` | uuid | Hayır | – | |
| `window_start` | timestamptz | Hayır | – | Penceredeki ilk sorgu zamanı; denetim kaydının `at` değeri olur |
| `last_viewed_at` | timestamptz | Hayır | – | |
| `query_count` | integer | Hayır | `1` | |
| `client` | text | Hayır | `'WEB'` | Denetim kaydına taşınır |
| `filters_used` | text | Evet | – | Penceredeki filtrelerin özet kümesi (ör. `groupId,freshness`); kimlik/koordinat yok |

Kısıtlar: `pk_location_view_windows (actor_id, operation_id)` (kişi × operasyon başına en fazla **bir açık pencere**), `fk_…_actor` → `identity.users`, `fk_…_operation` → `organization.operations` (RESTRICT), `ck_location_view_windows_count (query_count >= 1)`. İndeks: `ix_location_view_windows_start (window_start)` (kapatma işi).

**İşlem deseni (LD-44):** Adımlar **LC-05 isteğinin kendi işleminde**, konumlar okunduktan sonra ve commit'ten önce çalışır. Konum modülü bunu audit modülünün açık arayüzüyle çağırır (ADR-001); olay dinleyicisi kullanılmaz. Yetki veya operasyon durumu hatası alan istek (403/409) pencereye dokunmaz. Commit başarısızsa konum verisi yanıtlanmaz. `:now` uygulamanın `Clock`'undan gelir (backend §12); pencere ölçütü `:now < window_start + 5 dk`.
1. `INSERT INTO audit.location_view_windows (actor_id, operation_id, window_start, last_viewed_at, query_count, client, filters_used) VALUES (?, ?, :now, :now, 1, ?, ?) ON CONFLICT (actor_id, operation_id) DO NOTHING`. 1 satır eklendiyse bitti. Eşzamanlı iki ilk sorguda ikincisi birincinin commit'ini bekler, sonra çakışma görür; birincil anahtar ihlali (`500`) oluşmaz.
2. 0 satır → `SELECT … FROM audit.location_view_windows WHERE actor_id = ? AND operation_id = ? FOR UPDATE`.
   - 0 satır dönerse kapatma işi satırı 1. adımla bu adım arasında silmiştir. 1. adım **bir kez** yeniden denenir; ikinci deneme de başarısızsa `500` + alarm (beklenmeyen durum).
3. Satır var ve `:now < window_start + 5 dk` → `UPDATE … SET query_count = query_count + 1, last_viewed_at = :now, filters_used = <birleşim>`.
4. Satır var ve pencere dolmuş (`:now >= window_start + 5 dk`) → **aynı işlemde** `audit_entries`'e tek `LOCATION_VIEWED` satırı ekle (`at = window_start`, `details = {queryCount, windowStart, windowEnd = window_start + 5 dk, lastViewedAt, filtersUsed}`, `client` kolonu satırdan), ardından ara satırı yeni pencereye sıfırla (`window_start = last_viewed_at = :now`, `query_count = 1`).
5. **Kapatma işi** (her 1 dk): `window_start <= :now − 5 dk` satırlarını `ORDER BY window_start LIMIT 200 FOR UPDATE SKIP LOCKED` ile alır. Her biri için 4. adımdaki denetim satırını ekler ve ara satırı **siler**; hepsi tek işlemdir. LC-05'in kilitlediği satır atlanır; o satırı LC-05 4. adımda kendisi kapatır. Kilitsiz satırı sildiğinde, bekleyen LC-05 2. adımda 0 satır görür ve 1. adıma döner.
6. Bir pencere ya LC-05 (4. adım) ya kapatma işi tarafından, satır kilidi altında **tam bir kez** denetime yazılır. Bu yüzden kayıtlardaki `queryCount` toplamı başarılı LC-05 çağrı sayısına eşittir.

**Sonuçlar:** Denetim kaydı en geç pencere bitişinden ~1 dk sonra görünür (≤ 6 dk). Kaydın `at`'i ilk erişim anıdır, bu yüzden zaman filtreli denetim sorguları doğru sonuç verir. Ara tablo en fazla (KOR sayısı × aktif operasyon) ≈ 50 satırdır. Ara tablo `audit` şemasındadır ama denetim kaydı **değildir**; `app_runtime` burada `UPDATE`/`DELETE` yapabilir. Backend testi T-LOC-03 "her çağrı bir kayıt" yerine "5 dk'da N çağrı → 1 kayıt, `queryCount = N`" olmalıdır.

## 7. Sorgu desenleri ve indeks eşlemesi

### 7.1 Arka plan işçileri

| İşçi | Sorgu deseni | İndeks | Sıklık / hacim |
|---|---|---|---|
| **CRITICAL teslim işçisi** | `queue_class = 'CRITICAL' AND status = 'QUEUED' AND available_at <= now()` → `ORDER BY available_at LIMIT 100 FOR UPDATE SKIP LOCKED` → FCM toplu gönderim → satırları ve alıcı durumunu güncelle → commit | `ix_deliveries_queue` | Sürekli (boşta 0,5 – 1 sn uyku veya olay tetiklemesi). 500 alıcılı alarm ≈ 600 PUSH satırı = 6 parti |
| **STANDARD teslim işçisi** | Aynısı, `queue_class = 'STANDARD'` | `ix_deliveries_queue` | Ayrı iş parçacığı havuzu; CRITICAL'ı asla beklemez |
| **Hatırlatma zamanlayıcısı** | `next_reminder_at <= now() AND state IN ('SENT','DELIVERED','SEEN')` → `ORDER BY next_reminder_at LIMIT 200 FOR UPDATE SKIP LOCKED` → `reminder_count < reminder_max` ise yeni PUSH satırları: `round = resend_count`, `attempt = reminder_count + 1`. İkisi de **bu işlemde `FOR UPDATE` ile kilitlenen** alıcı satırından okunur; ekleme `ux_deliveries_push_unique` üzerinde `ON CONFLICT DO NOTHING` ile idempotenttir (LD-43). `round` yazılmazsa varsayılan 0 olur ve 1. turdan sonraki hatırlatmalar 0. turun satırlarıyla çakışıp sessizce düşer (B-01) ve `next_reminder_at += 2 dk`; değilse `UNANSWERED`, `next_reminder_at = NULL` | `ix_alert_recipients_reminder_due` | 30 sn. Alarmın `OPEN` olduğu ayrıca denetlenmez çünkü iptal/kapanış aynı işlemde `next_reminder_at`'i boşaltır (§7.3) |
| **Outbox yeniden yayını** | Spring Modulith tamamlanmamış yayınlar (`completion_date IS NULL`) | Modulith tablosu + §8.3 indeksi | Başlangıçta ve periyodik |
| **Davet süresi işi** | `status = 'PENDING' AND expires_at < now()` → `EXPIRED` | `ix_invitations_expiry` | 5 dk |
| **Operasyon kapanış hatırlatması** | `state = 'ACTIVE' AND close_reminder_sent_at IS NULL AND planned_end_at < now() − 2 sa` | `ix_operations_close_reminder` | 5 dk |
| **Konum denetim penceresi kapatma (LD-05)** | `window_start <= :now − 5 dk` → `FOR UPDATE SKIP LOCKED` → `LOCATION_VIEWED` ekle + ara satırı sil (tek işlem; §6.7 adım 5–6, LD-44) | `ix_location_view_windows_start` | 1 dk |
| **Temizlik işleri** | §8.2 | İlgili `expires_at`/tarih indeksleri | Saatlik / günlük |

**Teslim işçisinde işlem sınırı:** Satırlar `FOR UPDATE SKIP LOCKED` ile alınır, FCM çağrısı **aynı işlem açıkken** yapılır, sonuç yazılıp commit edilir. Parti küçük tutulursa (≤ 100) işlem süresi ≈ FCM toplu çağrı süresidir (< 1 – 2 sn). Bu süre içinde aynı satırlar başka işçiye görünmez; işçi çökerse kilit düşer ve satır `QUEUED` olarak kalır (en az bir kez iletim; FCM `collapse_key` cihazdaki yinelenmeyi zararsız kılar). Alternatif "kiralama" deseni (`status = 'SENDING'` + `lease_until`) bağlantıyı FCM süresince tutmaz ama domain enum'una yeni değer ekler; 500 kullanıcı ölçeğinde gerekmez (§13 AN-04).

### 7.2 Kullanıcı istekleri (p95 ≤ 1 sn hedefi)

| Uç nokta | Sorgu | İndeks | Beklenen maliyet |
|---|---|---|---|
| `GET /me/context` | Rollerim, gruplarım, bekleyen davetlerim | `ix_role_assignments_user_active`, `ix_group_memberships_user_active`, `ix_invitations_user_pending` | 3 indeks araması, < 5 ms |
| `GET /groups` (gruplarım) | Aktif üyelikler ⋈ arşivlenmemiş gruplar | `ix_group_memberships_user_active` | < 5 ms |
| `GET /groups/{id}` | Grup + aktif üyeler (≤ 50) | `pk_groups`, `ux_group_memberships_active_member` | < 5 ms |
| `GET /operations/{id}/tasks` | `operation_id = ? AND id < :cursor ORDER BY id DESC LIMIT 50` | `ix_tasks_operation` | < 5 ms |
| `GET /tasks/{id}` (KOR) | Görev + atamalar (≤ 500) | `uq_task_assignments_task_user` | < 20 ms |
| Görevlerim (GON) | `task_assignments WHERE user_id = ?` ⋈ `tasks` | `ix_task_assignments_user` | < 10 ms |
| `GET /operations/{id}/locations` | `last_locations WHERE operation_id = ?` (≤ 500) ⋈ paylaşım durumu | `ix_last_locations_operation`, `pk_location_sharings` | < 20 ms |
| `GET /alerts/{id}` (`counts`) | `alert_recipients WHERE alert_id = ? GROUP BY state` + `NEED_HELP` sayımı | `uq_alert_recipients_alert_user` (önek) | ≤ 500 satır, < 10 ms. 5 sn yoklama yedeğinde (RT-K5) bile önemsiz |
| `GET /alerts/{id}/recipients` | 500 alıcı + kullanıcı adları (identity açık arayüzünden toplu okuma) | Aynı | < 50 ms |
| `GET /me/notifications` | `user_id = ? AND channel = 'IN_APP' AND id < :cursor ORDER BY id DESC LIMIT 50` | `ix_deliveries_inbox` | < 5 ms |
| `GET /audit-entries` | `id < :cursor ORDER BY id DESC LIMIT 50` (+ Genişletilmiş filtreler) | `pk_audit_entries` (geri tarama), §8 filtre indeksleri | < 20 ms |
| `POST /operations/{id}/locations` | Paylaşım + operasyon durumu denetimi + upsert | `pk_location_sharings`, `pk_operations`, `pk_last_locations` | < 5 ms; ~2,5 – 5 istek/sn (LD-15) |
| `GET /operations/{id}/locations` denetimi | LD-05 pencere upsert'i (§6.7) | `pk_location_view_windows` | < 2 ms |

### 7.3 SSE telafi ve yoklama yedeği

SSE doğruluk kaynağı değildir (RT-K2). `Last-Event-ID` arabelleği yetmezse istemci `resync` alır ve ilgili listeyi yeniden çeker. Listeler zaten kapsamlı ve küçüktür (operasyon başına ≤ 500 satır). 5 sn yoklama yedeğinde (RT-K5) delta sorgusu kullanılabilir:

| Konu | Delta sorgusu | İndeks |
|---|---|---|
| `alert:{id}` | `alert_recipients WHERE alert_id = ? AND updated_at > :since` | `ix_alert_recipients_alert_updated (alert_id, updated_at)` — **yalnızca yoklama yedeği devreye girerse** eklenir |
| `operation:{id}` görevler | `task_assignments ⋈ tasks WHERE operation_id = ? AND updated_at > :since` | Gerekirse `ix_task_assignments_task_updated (task_id, updated_at)` |
| `operation:{id}` konum | `last_locations WHERE operation_id = ? AND received_at > :since` | `ix_last_locations_operation` yeterli (≤ 500 satır) |

`updated_at` kolonları baştan vardır; delta indeksleri gerektiğinde eklenir (yazma maliyeti boşuna ödenmez).

### 7.4 Sayfalama ve sıralama

- **İmleç = son satırın `id`'si** (UUIDv7, oluşturma sırasıyla uyumlu). `WHERE … AND id < :cursor ORDER BY id DESC LIMIT :limit + 1`. Ek bir `created_at` indeksi gerekmez.
- İmleç istemciye opak (Base64) verilir; içerik `id`'dir. Farklı sıralama gerekirse imleç `(sıralama_değeri, id)` çifti olur ve indeks de aynı sırada tanımlanır.
- Kullanıcı listesi (`GET /users`): Ada göre sıralama isteniyorsa `(lower(display_name), id)` indeksi + Türkçe harmanlama (§1.2). Öneri: MVP'de `id` sırası + arama filtresi; ad sıralaması istemcide (ADM için 500 satır).

---

## 8. Eşzamanlılık ve işlem sınırları

Varsayılan yalıtım düzeyi **READ COMMITTED**. Doğruluk açık satır kilitleri, koşullu `UPDATE` ve benzersiz indekslerle sağlanır; `SERIALIZABLE` kullanılmaz.

### 8.1 Kilit sırası (kilitlenme önleme)

Birden çok satır kilitleyen tüm işlemler şu sırayı izler: **organizasyon → operasyon → grup → görev / alarm → alt satırlar (üyelik, atama, alıcı) → kullanıcı satırları.** Alt satırlar birden çok kilitlenecekse `ORDER BY id` ile kilitlenir.

### 8.2 Kurallar ve uygulanışları

| # | Kural | Uygulanış | Yedek güvence | Hata kodu |
|---|---|---|---|---|
| E-1 | **Görev kapasitesi** (K-12, AC-14.6) | Kabul işlemi: (1) görev satırı `FOR UPDATE`; durum `PUBLISHED`/`IN_PROGRESS` mi; (2) atama satırı `FOR UPDATE`; bu kullanıcının mı; durum `CAPACITY_FULL` ise **durum değiştirmeden** `CAPACITY_FULL` (`acceptedCount`, `capacity` kilitli görev satırından), `ACCEPTED`/`REJECTED`/`REMOVED` ise `ASSIGNMENT_NOT_PENDING`, `PENDING` ise devam (LD-46); (3) `accepted_count < capacity` mi; (4) atama `ACCEPTED`, `accepted_count + 1`; (5) kapasite dolduysa aynı işlemde `UPDATE task_assignments SET status = 'CAPACITY_FULL' WHERE task_id = ? AND status = 'PENDING'` | `ck_tasks_capacity_not_exceeded` | `CAPACITY_FULL`, `ASSIGNMENT_NOT_PENDING`, `INVALID_STATE_TRANSITION` |
| E-2 | Kapasite düşürme (AC-13.7) | Görev `FOR UPDATE` + `version` denetimi; `capacity` güncellemesi | Aynı `CHECK` | `CAPACITY_BELOW_ACCEPTED`, `VERSION_CONFLICT` |
| E-3 | Kabul edilmiş atamanın kaldırılması | Görev `FOR UPDATE`; atama `REMOVED`; `accepted_count − 1` (aynı işlem) | `ck_tasks_accepted_nonneg` | – |
| E-4 | Ret | Atama satırı `UPDATE … WHERE id = ? AND status = 'PENDING'`; 0 satır → hata. Görev kilidi gerekmez (kapasiteyi etkilemez); kilit sırası korunur | – | `ASSIGNMENT_NOT_PENDING` |
| E-5 | **Tek lider** (K-32) | Tüm üyelik değişiklikleri grup satırını `FOR UPDATE` ile kilitler. Lider değişimi **sırayla**: önce eski lider `MEMBER`, sonra yeni lider `LEADER` (aksi hâlde kısmi benzersiz indeks ara durumda ihlal edilir). Lider çıkarma isteği, yerine lider belirtilmemişse reddedilir. Grup oluşturma lideri aynı işlemde ekler | `ux_group_memberships_active_leader` (en fazla bir) | `LEADER_REQUIRED` |
| E-6 | Grup başına ≤ 50 üye (D-04) | Grup kilidi altında aktif üye sayımı | – | `GROUP_FULL` (LD-36; `memberCount`, `limit`) |
| E-7 | ≤ 5 aktif operasyon (K-02) | **Zorlanmaz (LD-20):** 5 aktif operasyon kapasite ve yük testi hedefidir, sınır değildir; kilit ve sayım yapılmaz | – | – |
| E-8 | **Alarm gönderimi** (TD-05) | Tek işlem: (1) platform idempotency kaydı (§8.3); (2) operasyon `FOR SHARE` → `ACTIVE` mi; (3) `UPDATE alerts SET state = 'OPEN', … WHERE id = ? AND state = 'DRAFT' AND version = :v` → 0 satır ise `VERSION_CONFLICT`/`INVALID_STATE_TRANSITION`; (4) hedef kitleyi yeniden çöz, istemcinin `audienceHash`'iyle karşılaştır (farklıysa işlem geri alınır); (5) ≤ 500 `alert_recipients` toplu ekleme; (6) `ALERT_SENT` bildirimi; (7) outbox; (8) denetim | `platform.idempotency_keys` PK, `uq_alert_recipients_alert_user`, `ux_notifications_alert_lifecycle` | `OPERATION_NOT_ACTIVE`, `AUDIENCE_CHANGED`, `VERSION_CONFLICT`, `AUDIENCE_TOO_LARGE` |
| E-9 | Operasyon kapanışı ile yarış | Kapanış operasyon satırını günceller (satırı `FOR NO KEY UPDATE` ile kilitler); E-8, konum başlatma ve görev yayını operasyonu `FOR SHARE` ile okur → çakışan istekler sıraya girer. Kapanış sonrası zincir (paylaşım durdurma, alarm kapama, grup arşivleme, rol pasifleştirme) olay dinleyicilerinde ayrı işlemlerdedir; operasyon durumu zaten `ACTIVE` olmadığından yeni yazmalar hemen reddedilir | – | `OPERATION_NOT_ACTIVE` |
| E-10 | **Yanıt yalnızca `OPEN` alarmda** (TD-06) | Yanıt işlemi alarm satırını `FOR SHARE` ile okur (500 eşzamanlı paylaşımlı kilit çakışmaz); iptal/kapama `UPDATE` ile kilit alır ve bekleyen yanıtlar ya önce ya sonra işlenir — iptalden **sonra** yanıt kabul edilmez | – | `ALERT_NOT_OPEN` |
| E-11 | Yanıt değişikliği sırası (TD-07) | Alıcı satırı `UPDATE … WHERE id = ? AND user_id = :ben AND (responded_at_device IS NULL OR :cihaz_zamanı >= responded_at_device)`. Aynı cihazın gecikmeli tekrarı (WorkManager) yeni yanıtın üzerine eski yanıtı yazamaz | Platform idempotency (aynı anahtar) | – (0 satır → mevcut durumu 200 ile dön) |
| E-12 | İptal / kapama | Alarm `UPDATE … WHERE state = 'OPEN'`; aynı işlemde `UPDATE alert_recipients SET next_reminder_at = NULL WHERE alert_id = ? AND next_reminder_at IS NOT NULL`; `ALERT_CANCELLED`/`ALERT_CLOSED` bildirimi | `ux_notifications_alert_lifecycle` | `ALERT_NOT_OPEN` |
| E-13 | Elle yeniden gönderim (AC-23.4) | Alarm `FOR SHARE` (`OPEN`); seçili alıcılar `UPDATE … WHERE state IN ('UNANSWERED','FAILED')` → `QUEUED`, `reminder_count = 0`, `resend_count + 1`, `next_reminder_at = now() + 2 dk`, `failure_reason = NULL`; yeni PUSH satırları `round` = aynı `UPDATE … RETURNING resend_count` ile dönen yeni değer, `attempt = 0`. Sonraki hatırlatmalar aynı turu hatırlatma zamanlayıcısından alır (LD-43) | `ux_deliveries_push_unique` | `ALERT_NOT_OPEN` |
| E-14 | Kuyruk ve zamanlayıcı işçileri | `FOR UPDATE SKIP LOCKED`; birden çok iş parçacığı/örnek güvenle çalışır | Benzersiz teslim indeksleri | – |
| E-15 | Yenileme belirteci döndürme (TD-04) | `UPDATE refresh_tokens SET replaced_by = :yeni, last_used_at = now() WHERE token_hash = ? AND revoked_at IS NULL AND replaced_by IS NULL AND expires_at > now()`; 0 satır ve belirteç varsa → **yeniden kullanım**: `UPDATE … SET revoked_at = now(), revoke_reason = 'REUSE_DETECTED' WHERE family_id = ?` **LD-18 toleransı:** 0 satır döndüğünde belirtecin halefi (`replaced_by`) hiç kullanılmamışsa (`last_used_at IS NULL`), aynı `client` ise ve halef ≤ 10 sn önce verildiyse zincir iptal edilmez, yeni halef verilir (eşzamanlı iki yenileme isteği). **LD-19:** web oturumu 12 sa **mutlaktır**; döndürmede yeni satırın `expires_at`'i zincirin ilk belirtecinin `expires_at`'inden kopyalanır, uzatılmaz (Android'de de aynı kural 30 gün için önerilir; backend kararı). **Atomiklik (LD-47):** halefin eklenmesi ve eski satırın `replaced_by` ile güncellenmesi aynı işlemdedir; her istekteki oturum denetimi zinciri hiçbir an canlı belirteçsiz görmez | `uq_refresh_tokens_hash` | `AUTH_SESSION_REVOKED` |
| E-16 | Başarısız giriş sayacı | Tek atomik `UPDATE users SET failed_login_count = failed_login_count + 1, locked_until = CASE WHEN failed_login_count + 1 >= 5 THEN now() + 15 dk END` | – | `ACCOUNT_LOCKED` |
| E-17 | Rol / katılım tekilliği | Ekleme; `ux_role_assignments_active` ihlali → zaten var, idempotent başarı | Kısmi benzersiz indeks | – |
| E-18 | Davet kabulü (tek kullanımlık) | `UPDATE invitations SET status = 'ACCEPTED', used_at = now() WHERE id = ? AND status = 'PENDING' AND expires_at > now()`; 0 satır → geçersiz | – | `INVITATION_INVALID` |
| E-19 | Konum ölçümü | Paylaşım satırı ve operasyon durumu okunur (kilitsiz yeterli: kapanış sonrası en fazla bir ölçüm sızabilir ve zincir onu siler/durdurur), ardından koşullu upsert (§6.2) | – | `LOCATION_SHARING_STOPPED`, `OPERATION_NOT_ACTIVE` |

### 8.3 Idempotency saklama (NFR-06, AC-20.5) — backend ile mutabık

İki katmanlı:

1. **HTTP katmanı — `platform.idempotency_keys`** (24 saat; `Idempotency-Key` zorunlu ve desteklenen tüm uçlar, LD-04): aynı anahtar + aynı özet → ilk yanıt (`Idempotent-Replayed: true`); farklı özet → `422 IDEMPOTENCY_KEY_MISMATCH`.
2. **Alan katmanı:** `notifications.idempotency_key` (kaynak olay anahtarı, genel benzersiz; outbox tekrarına karşı), durum geçişlerinin doğal idempotentliği (`DRAFT → OPEN`, `PENDING → ACCEPTED`, `PENDING → ACCEPTED/DECLINED` davet) ve teslim benzersizlikleri (`ux_deliveries_*`). `alerts.idempotency_key` ve `alert_recipients.response_idempotency_key` yalnızca izlenebilirlik kolonudur, benzersizlik taşımaz.

**`platform.idempotency_keys`**

| Kolon | Tip | NULL | Varsayılan | Açıklama |
|---|---|---|---|---|
| `principal_key` | text | Hayır | – | Kimlikli istekte kullanıcı `id`'si (metin); davet koduyla kimliksiz kabulde `anon:` + davet kodunun HMAC özeti (backend §3.6) |
| `idem_key` | text | Hayır | – | `Idempotency-Key` (UUID biçimi, 36 karakter) |
| `method` | text | Hayır | – | `POST` vb. |
| `route_template` | text | Hayır | – | ör. `/alerts/{id}/send` |
| `request_hash` | text | Hayır | – | **HMAC-SHA-256** hex, sunucu sırrıyla (yöntem + yol şablonu + yol parametreleri + kanonik gövde). Anahtarsız özet, parola içeren ID-16 gövdesini yedekten çevrimdışı denemeye açardı (LD-42) |
| `hash_key_version` | smallint | Hayır | – | `request_hash`'i üreten HMAC anahtarının sürümü. Tekrar bu sürümün anahtarıyla doğrulanır; döndürme ve eski anahtarın kaldırılması backend §3.6 "Özet anahtarı döndürme" kuralına göredir (LD-42) |
| `response_status` | integer | Evet | – | İşlem sürerken `NULL`; commit'ten önce 2xx yazılır. Commit anında `NULL` kalırsa `ct_idempotency_keys_completed` commit'i reddeder. Satır işlem başında eklendiği için yanıt kodu o anda bilinmez; `NOT NULL` desenle çelişiyordu (LD-45) |
| `response_body` | text | Evet | – | Yanıt gövdesi **metin** olarak (backend `jsonb` önermişti; §12.6 gerekçe). Sır içeren yanıtta `NULL` |
| `body_redacted` | boolean | Hayır | `false` | `true`: yanıt sır içerdiği için saklanmadı; tekrar `409 ALREADY_COMPLETED` alır (backend §3.6, LD-42). Gövdesiz `204` yanıttan ayırmak için gerekir |
| `resource_type` | text | Evet | – | Oluşan/değişen kaynak türü |
| `resource_id` | uuid | Evet | – | |
| `created_at` | timestamptz | Hayır | `now()` | |
| `expires_at` | timestamptz | Hayır | – | `created_at + 24 sa` |

Kısıtlar: `pk_idempotency_keys (principal_key, idem_key)` — backend'in `uq_idempotency_keys_principal_key` benzersizliği birincil anahtar olarak; ayrı vekil `id` gerekmez (tablo hiçbir yerden referans almaz). `ck_idempotency_keys_status (response_status BETWEEN 200 AND 299)` (`NULL` yalnızca işlem sürerken geçerli), `ck_idempotency_keys_key_len (char_length(idem_key) BETWEEN 8 AND 64)`, `ck_idempotency_keys_redacted (NOT body_redacted OR response_body IS NULL)`, `ck_idempotency_keys_hash_key_version (hash_key_version > 0)`. İndeks: `ix_idempotency_keys_expires (expires_at)`. Hata kodu eşlemesinde `pk_idempotency_keys` ihlali beklenmez (desen aşağıda); olursa yeniden dene.

**`ct_idempotency_keys_completed` (LD-45):** `CREATE CONSTRAINT TRIGGER ct_idempotency_keys_completed AFTER INSERT OR UPDATE OF response_status ON platform.idempotency_keys DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION platform.idempotency_keys_completed_check()`. İşlev `NEW`'e güvenmez, çünkü ekleme olayındaki `NEW.response_status` her zaman `NULL`'dur. Satırı `(principal_key, idem_key)` ile **commit anındaki hâliyle** yeniden okur:
- **Satır yok:** Aynı işlemde silinmiştir; durum değiştirip 4xx dönen yol (TK-11 savunma yolu) ya da sonradan geri alınan bir ekleme. Geçer.
- **Satır var, `response_status` 2xx:** Geçer.
- **Satır var, `response_status IS NULL`:** `RAISE EXCEPTION` (`ct_idempotency_keys_completed`). Commit başarısız olur ve **işlemin tamamı**, iş değişiklikleri dahil, geri alınır. API `500` + alarm döner (backend §15.3). Böylece "silmeyi unutma" hatası eksik bir satırı kalıcı yapmaz.
- **İşlem geri alındı:** Ertelenmiş tetikleyici olayları atılır; tetikleyici hiç çalışmaz, iz kalmaz. Satır geri alınan bir savepoint içinde eklendiyse onun olayları da atılır.
- **Aynı işlemde sil + yeniden ekle** (LD-42 (7) süresi dolmuş satır): Yeniden okuma yeni satırı görür ve onun durumunu denetler.
- Uygulama `SET CONSTRAINTS … IMMEDIATE` kullanmaz; denetim yalnızca commit anında anlamlıdır.

**İşlem deseni:** Anahtar satırı iş işleminin **başında, aynı işlem içinde** eklenir (`ON CONFLICT DO NOTHING`), yanıt işlemin sonunda aynı satıra yazılır, hepsi tek commit'tir. Eklemeden önce aynı işlemde `DELETE … WHERE principal_key = ? AND idem_key = ? AND expires_at <= now()` çalışır. Böylece süresi geçmiş satır temizlik işini beklemeden yok sayılır; sürüm denetimine girmez (LD-42 (7)).
- Eşzamanlı ikinci istek aynı birincil anahtara ekleme yaparken birinci işlemin bitmesini bekler. Birinci commit ederse çakışma görür ve kayıtlı yanıtı okur (özet eşitse tekrar döner, değilse 422). Birinci geri alınırsa (4xx/5xx) ikinci normal ilerler — 4xx'in saklanmaması bunun doğal sonucudur.
- "İşleniyor" ara durumu dışarıya görünmez; ayrı durum kolonu gerekmez.
- **İstisna — durum değiştirip 4xx dönen uç (LD-45, LD-46; yalnızca TK-11 savunma yolu: kapasite doluyken `PENDING` atama; atama zaten `CAPACITY_FULL` ise durum değişmez ve işlem normal geri alınır):** TK-11 kapasite doluyken atamayı `CAPACITY_FULL` yapıp **commit eder** ve `409` döner. Bu durumda anahtar satırı kalmamalı (yanıt 2xx değil) ama iş değişikliği kalmalıdır: 409 yolunda işlem, commit'ten önce kendi eklediği (henüz görünmeyen) anahtar satırını `DELETE` eder. Bekleyen eşzamanlı tekrar bu durumda çakışma görmez, kendisi işlenir ve atamayı `CAPACITY_FULL` bulduğu için `409 CAPACITY_FULL` alır (LD-46) (backend §9.1 ile aynı sonuç). Backend'e iletildi (§12.6).
- Uyarı: Uzun süren işlem (alarm gönderimi ~100 ms) boyunca ikinci istek bağlantı tutarak bekler; kabul edilebilir.
- **Sır içeren değer saklanmaz (LD-42, B-02):** Tablo yedeğe girer; bu yüzden belirteç, MFA bileti, TOTP anahtarı, parola veya davet kodu hiçbir kolona yazılmaz. ID-16'nın `LoginResponse`'u gövdesiz saklanır (`response_body = NULL`, `body_redacted = true`, `resource_id` = kullanıcı); tekrarı yanıtı döndürmez, `409 ALREADY_COMPLETED` alır. İstek yalnızca anahtarlı ve sürümlü özetle (`request_hash`, `hash_key_version`; anahtar DB'de ve yedekte yok; döndürmede eski anahtar 25 saat doğrulama-yalnız kalır, sonra satır sayımı 0 doğrulanıp silinir; acil döndürmede o sürümün satırları silinir; yedekten geri yükleme §9.5), kimliksiz kapsam yalnızca kodun HMAC özetiyle (`principal_key`) tutulur. `/auth/*`, ID-03 ve ID-32 başlığı yok sayar. Yüksek hacimli uçlar (konum ölçümü, alındı/görüldü sinyalleri) da başlığı yok sayar (B-15).

### 8.4 Bütünlük denetim sorguları (haftalık, CI'da da)

Kısıtla ifade edilemeyen kuralların ihlalini **raporlayan** salt-okur sorgular (Testcontainers entegrasyon testlerinde ve demo ortamında haftalık):
- Aktif ve arşivlenmemiş olup aktif lideri olmayan grup (K-32 "en az bir").
- Grubun operasyonunda aktif `GON` ataması olmayan aktif üye (TD-01).
- `accepted_count` ≠ gerçek `ACCEPTED` sayısı olan görev.
- Göndereni alıcı listesinde olan alarm.
- `ACTIVE` paylaşımı olup operasyonu `ACTIVE` olmayan satır.
- Çok biçimli `scope_id`/`source_id`/`resource_id` değeri karşılık gelen tabloda olmayan kayıt.

---

## 9. Hacim, performans, bakım ve yedekleme

### 9.1 Varsayımlar

500 kayıtlı kullanıcı, 150 eşzamanlı oturum, 5 eşzamanlı aktif operasyon (VS-03), grup ≤ 50 üye, alarm ≤ 500 alıcı; konum 60 sn'de bir; ortalama 1,2 aktif cihaz/kullanıcı. "Yoğun gün" = 12 saatlik aktif tatbikat/yük testi günü. Dönem: 14 hafta (VS-07), bunun ~20 günü yoğun kullanım/test varsayılır.

### 9.2 Günlük satır büyümesi (yoğun gün)

| Tablo | Hesap | Satır/gün (eklenen) | Güncelleme/gün | Ort. satır boyutu |
|---|---|---|---|---|
| `last_locations` | 150 paylaşımcı × 60/sa × 12 sa; LD-15 ile iki operasyonda paylaşanlar ×2 | 0 (yerinde) | **~108 000 – 216 000** | ~120 B |
| `location_sharings` | başlat/durdur | ~300 | ~300 | ~100 B |
| `refresh_tokens` | 150 web × 4/sa × 12 sa + Android | **~8 000** | ~8 000 | ~250 B |
| `tasks` | 5 operasyon × 20 | 100 | ~400 | ~600 B |
| `task_assignments` | 100 görev × ~25 | 2 500 | ~3 000 | ~200 B |
| `task_state_changes` | 100 görev × ~3 geçiş | ~300 | 0 | ~100 B |
| `alerts` | 10 alarm | 10 | ~40 | ~800 B |
| `alert_recipients` | 10 × 500 (en kötü) | 5 000 | ~20 000 (durum + hatırlatma) | ~250 B |
| `notifications` | görev, alarm (×3), duyuru, toplanma | ~250 | ~250 | ~600 B |
| `deliveries` | Alarm: 500 × (1,2 PUSH × (1 + ≤ 5 hatırlatma) + 1 IN_APP) + iptal/kapanış 500 × 2,2 ≈ 5 000 – 5 600 / alarm → ×10; görev 2 500 × 2,2 | **~60 000** | ~60 000 | ~220 B |
| `audit_entries` | alarm yanıtları 5 000 + görev yanıtları 2 500 + girişler ~1 000 + diğer işlemler; `LOCATION_VIEWED` LD-05 ile KOR × operasyon × 5 dk'da en fazla 1 (10 KOR × 12 sa ≈ en fazla 1 440) | **~10 000 – 11 000** | 0 | ~300 B |
| `location_view_windows` | Ara tablo (§6.7) | ≤ 50 canlı satır | ~3 000 (her LC-05 çağrısı) | ~120 B |
| `idempotency_keys` | zorunlu uçlar | ~8 000 (24 sa sonra silinir) | – | ~1 KB |
| `event_publication` | iş olayı × dinleyici | ~30 000 (temizlenir) | ~30 000 | ~500 B |

**Dönem toplamı (20 yoğun gün + sakin günler):** `deliveries` ≈ 1,2 M satır (~300 MB indeksleriyle), `audit_entries` ≈ 0,2 – 0,4 M (~120 MB), `alert_recipients` ≈ 0,1 M, diğerleri ihmal edilebilir. **Toplam veritabanı < 2 GB.** Tek PostgreSQL örneği (2 vCPU, 4 GB RAM, `shared_buffers = 1 GB`) yeterlidir; sıcak veri belleğe sığar.

### 9.3 Performans notları

- **En yüksek yazma yükü konum güncellemesidir** (~2,5/sn sürekli, LD-15 en kötü durumda ~5/sn): HOT güncelleme + agresif otomatik vakum (§6.2) ile sorun değildir.
- **Alarm patlaması:** Gönderim işleminde 500 alıcı satırı toplu ekleme (`INSERT … VALUES` çok satırlı veya JDBC batch) < 100 ms. Fan-out işçisi 600 PUSH + 500 IN_APP satırını toplu ekler. FCM'e iletim p95 ≤ 5 sn hedefi (NFR-08) veritabanı tarafında ~0,5 sn harcar; geri kalanı FCM gecikmesidir. Yük testinde ölçülür.
- **500 eşzamanlı yanıt:** Her yanıt tek alıcı satırını günceller; alarm satırında yalnızca paylaşımlı kilit (E-10). Sıcak satır yoktur.
- **Bağlantı havuzu:** HikariCP ~20 bağlantı; CRITICAL işçi havuzu için ayrılmış 2 – 4 bağlantı (diğer işçiler havuzu tüketse bile alarm iletimi bağlantı bulur). SSE bağlantıları veritabanı bağlantısı **tutmamalıdır** (`spring.jpa.open-in-view = false`).
- `statement_timeout` uygulama rolü için 5 sn, işçiler için 30 sn; `idle_in_transaction_session_timeout` 60 sn (FCM çağrısı sırasında açık işlemin üst sınırı).
- **`AlertView.deliveryDelayed` (LD-02):** alarm başına "bu alarmın bildirimlerinde 30 sn'den uzun bekleyen `CRITICAL` teslim var mı" sorgusu `ix_deliveries_notification` + `status = 'QUEUED'` süzgeciyle ≤ 600 satır tarar; genel sağlık için aşağıdaki kuyruk sorgusu yeterlidir.
- İzleme: `pg_stat_statements`, 200 ms'den yavaş sorgu günlüğü; CRITICAL kuyruk gecikmesi sorgusu: `min(available_at) WHERE status = 'QUEUED' AND queue_class = 'CRITICAL'` → 30 sn'yi aşarsa sağlık ucu "bozulmuş" (VS-14, GAP-11).

### 9.4 Bakım ve saklama

| İş | Ne yapar | Sıklık | Dayanak |
|---|---|---|---|
| Idempotency temizliği | `expires_at < now()` satırları sil (parti 5 000) | Saatlik | API §1 (24 sa) |
| Yenileme belirteci temizliği | `expires_at < now() − 7 gün` veya `revoked_at < now() − 7 gün` sil | Günlük | §3.5 |
| Outbox temizliği | Tamamlanmış yayınlar 7 günden eskiyse sil (Modulith `completion-mode` ayarı veya iş) | Günlük | ADR-004 |
| Davet süresi | `PENDING` → `EXPIRED` | 5 dk | K-06 |
| Son konum silme (**LD-25**) | Operasyon kapanış zincirinde (konum durdurma tüketicisi, ayrı işlem) o operasyonun `last_locations` satırları silinir; `location_sharings` satırları `STOPPED` olarak kalır (durum geçmişi değil, son durum). Tüketici idempotenttir: `DELETE … WHERE operation_id = ?` tekrarında etki yok | Kapanışta (≤ 1 dk) | K-15, K-16 |
| Hesap kapatma *(Genişletilmiş)* | `full_name`, `display_name`, `phone`, `email` anonimleştirilir (`email` → `closed+<id>@invalid`), `password_hash`/`totp_secret` silinir, `last_locations` silinir, cihazlar pasif, belirteçler iptal; denetim kayıtları korunur | Talepte | AC-30.2 |
| `deliveries`, `alert_recipients`, `audit_entries` | **Silinmez**; dönem sonuna kadar saklanır (test raporu ve denetim kanıtı) | – | VS-22, AC-21.4 raporu |
| Otomatik vakum ayarı | `last_locations` (§6.2); `deliveries` ve `alert_recipients` için `autovacuum_vacuum_scale_factor = 0.05`; `idempotency_keys`, `refresh_tokens`, `event_publication` için `0.02` | Kalıcı ayar | Kuyruk/çalkantılı tablolar |
| İstatistik | Toplu sentetik veri yüklemesinden sonra `ANALYZE` | Yüklemede | |

**Dönem sonu temizliği (VS-08, VS-22, K-04):**
1. Son tam yedek alınır, şifrelenir ve akademik değerlendirme için arşivlenir (danışmanla belirlenen süre).
2. Demo veritabanı ve Docker birimleri silinir; FCM hizmet hesabı anahtarı ve TOTP şifreleme anahtarı iptal edilir.
3. Veri sentetik olduğundan (K-04) ayrıca kişisel veri imhası prosedürü gerekmez; işlem yine de kayıt altına alınır.

### 9.5 Yedekleme ve geri yükleme (RPO 24 sa / RTO 4 sa, VS-04, TD-15)

| Konu | Karar |
|---|---|
| Yöntem | Docker Compose içinde zamanlanmış `pg_dump -Fc` servisi, `app_backup` rolüyle, **ayrı birime** (TD-15). Günlük 03:00 (Europe/Istanbul) |
| RPO | 24 sa: günlük döküm yeterli. WAL arşivleme / PITR gerekmez (prototip, K-01) |
| RTO | 4 sa: < 2 GB veri için geri yükleme (`pg_restore -j 4`) dakikalar mertebesidir; asıl süre ortamı yeniden kurmaktır. Prosedür yazılı adımlarla belgelenir |
| Saklama | 7 günlük + 4 haftalık döküm |
| Şifreleme | Döküm dosyası simetrik şifreyle (ör. `age`/`gpg`) şifrelenir; anahtar depo dışında. Disk şifrelemesi NFR-04 kararına bağlıdır (AC-29.3) |
| Doğrulama | Dönemde en az bir kez boş bir örneğe geri yükleme + §8.4 bütünlük sorguları + sayım karşılaştırması (VS-04) |
| Hariç tutma | Yok. `idempotency_keys` ve `event_publication` da yedeğe girer (geri yüklemede tamamlanmamış outbox olayları yeniden yayımlanır; FCM `collapse_key` ve teslim benzersizlikleri çift bildirimi sınırlar) |
| Geri yükleme sonrası idempotency (LD-42 (7)) | Döküm bir HMAC anahtarı silindikten sonra geri yüklenebilir. Bu yüzden uygulama trafiğe açılmadan (readiness kapalı) şu adımlar sırayla uygulanır: **(1) Süresi dolmuşları temizle:** `DELETE FROM platform.idempotency_keys WHERE expires_at <= now()`. Olağan döndürmede bir anahtar, kendi sürümündeki son satırın süresi dolduktan en az 1 saat sonra silinir (backend §3.6). Bu yüzden bu adımdan sonra silinmiş bir anahtara bağlı satır kalmaz. Haftalık ya da 24 saatten eski bir dökümde tablo tamamen boşalır. **(2) Sürümleri karşılaştır:** `SELECT hash_key_version, count(*), max(expires_at) … GROUP BY hash_key_version` sonucu, sır deposundaki sürümlerle karşılaştırılır. **(3) Eksik sürümü sınıflandır:** (a) Sürüm sızmış olarak işaretliyse (acil döndürme) anahtar **geri yüklenmez**, o sürümün satırları silinir; acil döndürmeyle aynı kural ve aynı kabul edilmiş risk geçerlidir. (b) Sızmamışsa ve anahtar sır deposunun yedeğinde varsa yalnızca **doğrulama-yalnız** olarak geri yüklenir; hiçbir zaman etkin yapılmaz. O sürümün `max(expires_at)` değeri + 1 saat sonra olağan kurala göre yeniden silinir. (c) Sızmamış ama kurtarılamıyorsa satırlar **silinmez**. Bu satırların tekrarları süreleri dolana kadar (en çok 24 saat) `500` + alarm alır ve yeni istek olarak işlenmez (backend §3.6 eksik anahtar kuralı). Durum olay kaydına yazılır. **(4) Yeni etkin anahtar gerekiyorsa** sürüm numarası, veritabanındaki ve sır deposundaki en büyük sürümden büyük seçilir; numaralar yeniden kullanılmaz. **(5) Uygulama açılışta aynı karşılaştırmayı yapar:** eksik sürümleri satır sayısıyla alarm olarak raporlar, ama readiness'ı düşürmez. Etki yalnızca o satırların anahtarlarıyla sınırlıdır. **Sır deposunun yedeği** veritabanı dökümünden ayrı ortamda ve ayrı erişimle tutulur; yalnızca o anda yüklü anahtarları içerir. Bir anahtar yalnızca (3b) için ve yalnızca ilgili sürümün süresi dolmamış satırı varken bu yedekten alınabilir |

---

## 10. Genişletilmiş MVP tabloları **(Genişletilmiş — yalnızca kapı KP3'te zaman kalırsa)**

### 10.1 `groups.meeting_points` (MeetingPoint) — *Genişletilmiş*

Çekirdekteki `groups.mp_*` alanlarının yerini alır (ERD §2). Geçişte mevcut `mp_*` değerleri `ACTIVE` satır olarak taşınır, `mp_*` kolonları bir sonraki sürümde kaldırılır.

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | ERD §2 |
| `group_id` | uuid | Hayır | – | | ERD §2 |
| `address` | text | Evet | – | | ERD §2 |
| `lat`, `lon` | double precision | Evet | – | | ERD §2 |
| `description` | text | Evet | – | | ERD §2 |
| `time` | timestamptz | Evet | – | Toplanma zamanı (kolon adı ayrılmış sözcük değildir ama ORM eşlemesinde `meeting_time` önerilir) | ERD §2 |
| `status` | text | Hayır | `'ACTIVE'` | `ACTIVE`, `UPDATED`, `CANCELLED` | ERD §2, AC-12.2 |
| `superseded_by` | uuid | Evet | – | `UPDATED` satırın yerini alan satır | FİZ |
| `updated_by` | uuid | Hayır | – | | ERD §2 |
| `updated_at` | timestamptz | Hayır | `now()` | | ERD §2 |

Kısıtlar: `pk_meeting_points`, `fk_meeting_points_group` (RESTRICT), `fk_meeting_points_updated_by` (RESTRICT, identity), `fk_meeting_points_superseded_by` (RESTRICT), `ck_meeting_points_status`, koordinat `CHECK`'leri, `ux_meeting_points_active (group_id) WHERE status = 'ACTIVE'` (grup başına tek geçerli nokta). İndeks: `ix_meeting_points_group (group_id, id DESC)` (geçmiş listesi).

### 10.2 `identity.consent_records` (ConsentRecord, TD-22) — *Genişletilmiş*

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | | FİZ (ERD'de bileşik anahtar yerine) |
| `user_id` | uuid | Hayır | – | | ERD §2 |
| `text_version` | text | Hayır | – | Aydınlatma metni sürümü (ör. `2026-11-v1`) | AC-02.2 |
| `accepted_at` | timestamptz | Hayır | `now()` | | AC-02.2 |

Kısıtlar: `pk_consent_records`, `fk_consent_records_user` (RESTRICT), `uq_consent_records_user_version (user_id, text_version)` (aynı sürüm iki kez onaylanmaz; tekrar = idempotent başarı). Bu tablo da yalnızca eklenir.

### 10.3 Genişletilmiş kolon ve tablo ekleri — *Genişletilmiş*

**`identity.devices` ek kolonları (LD-29, AC-31.3)** — yalnızca Genişletilmiş halkada eklenir; Çekirdek şemada yoktur.

| Kolon | Tip | NULL | Varsayılan | Açıklama |
|---|---|---|---|---|
| `notification_permission` | text | Evet | – | `GRANTED`, `DENIED`; boş = bilinmiyor (`ParticipantView.notificationPermission = UNKNOWN`) |
| `alarm_channel_enabled` | boolean | Evet | – | `alarm` kanalı açık mı (LD-13); boş = bilinmiyor |
| `permission_reported_at` | timestamptz | Evet | – | Cihazın son bildirimi (`DeviceRegisterRequest.notificationsEnabled`) |

Kısıt: `ck_devices_notification_permission`. Yeni indeks gerekmez (KOR görünümü katılımcıların aktif cihazlarını `ix_devices_user_active` ile okur; kişinin birden çok cihazında en kötü değer gösterilir). Konum bilgisi içermez (K-15).

**`identity.password_reset_tokens` (AN-16, AC-03.4)** — *Genişletilmiş öneri, karar bekliyor*: `id`, `user_id` (FK RESTRICT), `token_hash` (benzersiz, SHA-256), `expires_at` (+30 dk), `used_at`, `created_at`; kısmi indeks `(user_id) WHERE used_at IS NULL`. Yeni istek kullanıcının önceki kullanılmamış belirteçlerini geçersiz kılar. Saklama: 7 gün sonra silinir.

### 10.4 R2+ bağlanma noktaları (tablo **oluşturulmaz**)

| Gelecek varlık | Bağlanacağı mevcut tablo/kolon | MVP'de yapılan hazırlık |
|---|---|---|
| JOIN_REQUEST (R2) | `groups.groups.id`, `identity.users.id` | `join_policy` kolonu ve `CHECK` genişletilebilir |
| CONVERSATION / CONVERSATION_MEMBER (R2) | `groups.groups.id` (grup başına ≤ 1) | `groups` şemasına dokunmadan ayrı `messaging` şeması |
| MESSAGE (R2), ATTACHMENT (R3) | CONVERSATION | UUIDv7 + `seq`; idempotency deseni §8.3 ile aynı |
| CALL_SESSION / CALL_PARTICIPANT (R4) | CONVERSATION | – |
| NOTIFICATION_PREFERENCE (R2) | `identity.users.id`; `notifications.category` | `queue_class` ve `priority` ayrımı hazır; `CRITICAL` tercihle kapatılamaz |
| Grup rol/durum genişlemesi (R2/R3) | `group_memberships.role/status` | Enum `CHECK`'leri genişletilir; `ux_group_memberships_active_leader` R3'te `OWNER`'ı da kapsayacak biçimde yeniden tanımlanır |

---

## 11. Sentetik veri seti (K-04, `api-and-data-design-basis.md` §4.4)

Sabit tohumlu Datafaker (tr) üreticisi. İki profil: **Demo** (işlevsel senaryolar) ve **Yük** (VS-03 en kötü durum: 5 operasyonun tamamı `ACTIVE`).

| Tablo | Demo | Yük | Dağılım ve bilinçli kenar durumları |
|---|---|---|---|
| `organizations` | 1 | 1 | Kurgusal kurum (VS-01) |
| `users` | 500 | 500 | ADM 2, KOR 10, TEK 1, UYM 1, GON 486. 5 `DISABLED`, 2 `must_change_password`, 1 kilitli. ADM/KOR'un tamamı TOTP kayıtlı |
| `role_assignments` | ~1 300 | ~1 400 | Kurum rolleri 4 + KOR (operasyon başına 2 – 3, bir ADM kendini KOR atamış) + katılım `GON` (her gönüllü 1 – 2 operasyon) + ~100 `revoked` geçmiş satır |
| `operations` | 5 (2 `ACTIVE`, 1 `PLANNED`, 1 `COMPLETED`, 1 `CANCELLED`) | 5 `ACTIVE` | Biri planlanan bitişi geçmiş (kapanış hatırlatması testi) |
| `groups` | 30 (operasyon başına 4 – 8) | 35 | Kapanmış operasyonun grupları arşivli |
| `group_memberships` | ~900 | ~1 200 | Her grupta 1 lider; 2 grup tam 50 üye (sınır testi); ~50 `REMOVED`; çoklu üyelik örnekleri |
| `invitations` | 60 | 20 | Her durumdan örnek; 3 süresi dolmuş, 2 iptal |
| `devices` | ~560 aktif + 40 pasif | ~600 | %5 kullanıcının cihazı yok (`FAILED` senaryosu), %15'inin 2 cihazı, 10 geçersiz token (kalıcı hata senaryosu, sahte FCM'de) |
| `refresh_tokens` | ~300 | ~600 | Bir döndürme zinciri yeniden kullanım testi için |
| `tasks` | 80 | 150 | Her durumdan; 10'unda kapasite (biri dolu), 5 süresi geçmiş yanıt |
| `task_assignments` | ~1 800 | ~3 500 | Durum karışımı; biri `REMOVED`, biri yeniden `PENDING`'e alınmış; %10'u `respond_by` geçmiş |
| `task_state_changes` | ~200 | ~400 | Görevlerin durumlarıyla tutarlı geçiş zinciri |
| `location_sharings` | ~250 | ~600 | Durum karışımı; kapanmış operasyonda tümü `STOPPED` |
| `last_locations` | ~150 (yalnızca `ACTIVE` operasyonlar) | ~500 | Operasyon bölgesine dağılmış; %20'si > 2 dk, %10'u > 15 dk eski; 5'inde `clock_skew_flag` |
| `alerts` | 25 | 50 | 1 tam 500 alıcılı, 1 iptal + düzeltme çifti, 1 operasyon kapanışıyla `CLOSED`, 2 `DRAFT` |
| `alert_recipients` | ~3 500 | ~15 000 | Tüm durumlar; `NEED_HELP` yanıtlar; yanıt değişikliği örnekleri |
| `notifications` | ~250 | ~500 | Her kategori; 1 kurum geneli duyuru (> 50 alıcı) |
| `deliveries` | ~12 000 | ~40 000 | Hatırlatma denemeleri, `round = 1` yeniden gönderim, okunmuş/okunmamış IN_APP |
| `audit_entries` | ~6 000 | ~20 000 | Tutarlı eylem dizileri (üretici olay akışından yazar, rastgele değil); `LOCATION_VIEWED` kayıtları `queryCount` > 1 örnekleriyle (LD-05) |
| `location_view_windows` | 0 – 3 | 0 | Açık pencere örneği (kapatma işi testi) |
| `consent_records`* | 500 | 500 | Genişletilmiş; 20 kullanıcı eski sürümde |
| `meeting_points`* | 60 | 70 | Genişletilmiş; `UPDATED`/`CANCELLED` geçmişi |

**Üretim kuralları:** Veri üretici **uygulamanın servis katmanından** değil, doğrudan toplu yükleme ile yazılır, ama tüm kısıtlar açıkken (kısıt ihlali = üretici hatası). Yükleme sonrası §8.4 bütünlük sorguları sıfır satır döndürmelidir. UUIDv7 değerleri senaryo zamanına göre üretilir (geçmiş kayıtlar geçmiş zaman damgalı kimlik alır) ki imleç sırası tutarlı olsun. Telefon ve e-postalar `@example.test` gibi ayrılmış alan adlarıyla (K-04).

---

## 12. Uyum (T-03) — backend-architect ile

> **Durum:** backend-architect'in erken uyum önerileri ve `backend-api-design.md` 0.9 §15 ile karşılaştırıldı. "Mutabık" = iki doküman aynı şeyi söylüyor veya backend önerisi bu dokümana işlendi; "Önerildi" = DB tarafı önerisi backend'e iletildi, yanıt bekleniyor; "team-lead" = karar §13'te.

### 12.1 Kimlik ve tip eşlemesi

| Veritabanı | Java (JPA) | API (JSON) | Durum |
|---|---|---|---|
| `uuid` (UUIDv7) | `java.util.UUID`, uygulamada üretilir (ör. `uuid-creator` veya Hibernate'in zaman sıralı üreticisi — sürüm 7 ürettiği doğrulanmalı) | `string`, `format: uuid` | Mutabık |
| `timestamptz` | `Instant` | ISO-8601 UTC, `Z` ekli | Mutabık |
| `tstzrange` (`time_window`) | Özel tip eşlemesi (ör. hypersistence-utils `Range<Instant>`) | `TimeWindow { start, end }`; `[start, end)`, boş uç = sınırsız | Mutabık |
| `jsonb` (`target_expr`, `audience_expr`, `details`) | `@JdbcTypeCode(SqlTypes.JSON)` + `record` | `AlertTargets` / `TaskTargets` `{ userIds[], groupIds[] }`; jsonb içinde `{users:[…], groups:[…]}` | Önerildi — backend §15.1 "Açık" satırına yanıt: **görev ve alarm hedefleri `jsonb` `target_expr`**, ayrı tablo değil (team-lead AN-01) |
| `text` + `CHECK` | Java `enum`, `@Enumerated(EnumType.STRING)` | `UPPER_SNAKE_CASE` | Mutabık |
| `integer` `version` | `int`; başlangıç **1** | `version` | Mutabık (DB varsayılanı 1 yapıldı) |
| `double precision` | `double` | `number` | Mutabık |

### 12.2 Enum değerleri

| Kolon | Değerler | Durum |
|---|---|---|
| `tasks.priority` | `LOW`, `NORMAL`, `HIGH`, `URGENT` | Mutabık (backend önerisi alındı) → team-lead AN-08 |
| `alerts.priority` | `HIGH`, `CRITICAL` (varsayılan `CRITICAL`; iletim her zaman `CRITICAL` kuyruktan) | Mutabık → team-lead AN-08 |
| `alert_recipients.failure_reason` | `NO_DEVICE`, `TOKEN_INVALID`, `FCM_UNAVAILABLE`, `FCM_REJECTED` | Mutabık (backend değerleri alındı) |
| `alert_recipients.response_option` | `RECEIVED`, `NEED_HELP`, `ON_MY_WAY` | Mutabık |
| `notifications.kind` | Backend listesi **eksi `ALERT_REMINDER`**, **artı `OPERATION_CLOSE_REMINDER`**: `ALERT_SENT`, `ALERT_CANCELLED`, `ALERT_CLOSED`, `TASK_ASSIGNED`, `TASK_UPDATED`, `TASK_CANCELLED`, `ASSIGNMENT_REMOVED`, `CAPACITY_FULL`, `MEETING_POINT_UPDATED`, `MEETING_POINT_CANCELLED` (G), `GROUP_MEMBER_ADDED`, `ANNOUNCEMENT`, `OPERATION_INVITE`, `OPERATION_CLOSE_REMINDER`, `LOCATION_STOP`, `SESSION_REVOKED` | Önerildi. Gerekçe: hatırlatma yeni bildirim değil teslim denemesidir (DM §2, `communication.md` §1.5); `ALERT_REMINDER` yalnızca FCM `type` değeridir. Planlanan bitiş + 2 sa hatırlatması ise bir bildirim kaydı üretir |
| `notifications.source_type` | `ALERT`, `TASK`, `TASK_ASSIGNMENT`, `GROUP`, `OPERATION`, `INVITATION` | Önerildi (API'de görünmez; `link.type` buradan türetilir) |
| `location_sharings.stop_reason` | `USER`, `OPERATION_CLOSED`, `OPERATION_SUSPENDED` (G), `PARTICIPATION_REVOKED`, `SESSION_ENDED`, `ACCOUNT_DISABLED` | Önerildi: backend `stopReason` yalnızca `USER`, `OPERATION_CLOSED`, `SESSION_ENDED` içeriyor; N-10 katılım kaldırma, §7.3 askıya alma ve hesap devre dışı bırakma diğer değerleri gerektirir |
| `location_sharings.availability_reason` | `PERMISSION_DENIED`, `GPS_OFF`, `NO_FIX` | Mutabık (backend N-07 önerisinin saklanma yeri) |
| `devices.deactivation_reason`, `refresh_tokens.revoke_reason`, `role_assignments.revoke_reason`, `group_memberships.end_reason`, `task_assignments.source`, `deliveries.queue_class`, `audit_entries.client` | §3 – §6'daki listeler | İç alanlar; API'de görünmez |
| `groups.meeting_points.status` (G) | `ACTIVE`, `UPDATED`, `CANCELLED` (ERD §2) | Önerildi: backend `MeetingPoint.status`'ta `SUPERSEDED` yazmış; ERD `UPDATED` diyor → ERD izlenmeli |

### 12.3 Kolon ↔ API alan adı eşlemesi

Genel kural (mutabık): `snake_case` ↔ `camelCase` birebir. Hesaplanan alanlar (`freshness`, `overdue`, `counts`, `pendingCount`, `memberCount`) saklanmaz. İstisnalar:

| Kolon(lar) | API alanı | Durum |
|---|---|---|
| `groups.mp_address, mp_lat, mp_lon, mp_description, mp_time, mp_updated_by, mp_updated_at` | `meetingPoint { address, lat, lon, description, time, updatedBy, updatedAt }`; tümü boşsa `null` | Mutabık |
| `tasks.meeting_point` (text, DM) | `TaskCreateRequest.meetingPoint` (`MeetingPointRequest` nesnesi) | Çözüldü: **LD-26** metin; API `meetingPoint` alanı `string` (≤ 300) olmalı |
| `tasks.time_window` | `timeWindow { start, end }` | Mutabık |
| `tasks.target_expr`, `alerts.target_expr` | `targets { userIds[], groupIds[] }` | Önerildi |
| `tasks.accepted_count` | `acceptedCount` | Önerildi (backend "hesaplanır" diyor; DB sayaç kolonu öneriyor, API değişmez — §12.6 madde 3) |
| `tasks.response_window_minutes` | `responseWindowMinutes` | Mutabık |
| `task_assignments.respond_by` | `respondBy` | Mutabık |
| `task_state_changes` satırları | `TaskView.history[] { state, changedBy, changedAt }` | Önerildi (§5.5) |
| `alerts.audience_fingerprint` | `audienceHash` | Mutabık |
| `alerts.preview_recipient_count` | `AlertPreview.recipientCount` | Mutabık |
| `last_locations.clock_skew_flag` | `clockSkew` | Mutabık |
| `alert_recipients.responded_at_server` | `respondedAt` | Mutabık |
| `alert_recipients.via_group_id` | `group` (`GroupRef`, tekil) | Önerildi: backend `AlertRecipientView.groups[]` yazmış; temel doküman (`api-and-data-design-basis.md` §5) `group` tekil diyor ve anlık görüntü tek grup saklar |
| `location_sharings.availability_reason` | `LocationSharingStartRequest.reason` | Mutabık |
| `deliveries.id` (IN_APP) | `NotificationInboxItem.id` | Mutabık |
| `deliveries.queued_at` / `read_at` (IN_APP) | `NotificationInboxItem.createdAt` / `readAt` | Mutabık |
| `refresh_tokens.family_id` | JWT `sid` | Mutabık (backend'in `family_id` adı alındı) |
| `devices.installation_id` | `installationId` | Mutabık |
| `users.closure_requested_at` (G) | ID-30 | Önerildi |

### 12.4 Kısıt ↔ hata kodu eşlemesi

Adlandırma kuralı (mutabık): `pk_<tablo>`, `fk_<tablo>_<hedef>`, `ck_<tablo>_<anlam>`, `uq_<tablo>_<anlam>` (kısmi benzersiz indeksler dahil), `ix_` benzersiz olmayan indeks. Tek çevirici kısıt adını koda eşler; eşlenmemiş ihlal = `500` + günlük alarmı. Enum/uzunluk `ck_*` ihlalleri de `500`'dür (Bean Validation kaçırmış demektir; backend görüşü alındı).

| Kısıt | Bağlam | Hata kodu | Durum |
|---|---|---|---|
| `ux_group_memberships_active_member` | Üye ekleme | `409 ALREADY_EXISTS` | Mutabık |
| `ux_group_memberships_active_leader` | Eşzamanlı lider değişimi (grup kilidiyle olmamalı) | `409 VERSION_CONFLICT` (yeniden dene) | Mutabık |
| `ux_role_assignments_active` | Rol atama / katılım | `409 ALREADY_EXISTS`; davet kabulünde idempotent başarı | Mutabık |
| `uq_task_assignments_task_user` | Elle atama (yeniden ekleme `UPDATE` olduğu için yalnızca yarışta) | `409 ALREADY_EXISTS` | Mutabık |
| `ck_tasks_capacity_not_exceeded` | Kabul | `409 CAPACITY_FULL` | Önerildi (§12.6 madde 3) |
| `ck_tasks_capacity_not_exceeded` | Görev düzenleme | `409 CAPACITY_BELOW_ACCEPTED` | Önerildi |
| `ux_alerts_corrects` | Düzeltilmiş alarm taslağı | `409 ALREADY_EXISTS` | Mutabık |
| `uq_alert_recipients_alert_user` | – | `500` (olmamalı) | Mutabık |
| `uq_users_email` | Davet kabulü (yarış) | `409 ALREADY_EXISTS` | Mutabık. Not: DB'de `lower(email)` ifade indeksi yerine **düz benzersiz + `ck_users_email_lower`** (§1.2 harmanlama uyarısı); anlam aynı |
| `ux_invitations_pending_email_scope` | Davet oluşturma | `409 ALREADY_EXISTS` | Mutabık |
| `uq_invitations_token_hash`, `uq_invitations_code_hash` | Davet oluşturma | İç yeniden üretme | Mutabık |
| `ux_groups_operation_name` | Grup oluşturma/düzenleme | `409 ALREADY_EXISTS` | Önerildi (backend §8.2 "benzersiz (öneri)") |
| `uq_devices_user_installation`, `ux_devices_fcm_token_active`, `ux_devices_installation_active` | Cihaz kaydı | Upsert + eski satırı pasifleştirme; yarışta yeniden dene | Önerildi (backend `fcm_token`'ı düz benzersiz yazmış; §3.3 gerekçe: pasif satırlar teslim geçmişinde kalır) |
| `uq_notifications_idempotency_key`, `ux_notifications_alert_lifecycle` | Olay tekrarı (iç) | Sessizce yok say | Mutabık |
| `ux_deliveries_push_unique`, `ux_deliveries_in_app_unique` | İşçi tekrarı (iç) | `ON CONFLICT DO NOTHING` | Mutabık |
| `pk_idempotency_keys` | – | Desen gereği oluşmaz (§8.3) | Mutabık |
| `uq_consent_records_user_version` (G) | Onay tekrarı | İdempotent başarı | Önerildi |
| `fk_*` | Var olmayan referans | Uygulama önce yükler (`404`); ihlal `500` | Mutabık |
| Uygulama kuralları | Grup ≤ 50 üye → `409 GROUP_FULL` (LD-36); son lider → `409 LEADER_REQUIRED`; aktif operasyon sayısı zorlanmaz (LD-20) | – | Mutabık / team-lead |

### 12.5 Denetim tablosu

**`audit.audit_entries`** (AuditEntry, K-26, NFR-05)

| Kolon | Tip | NULL | Varsayılan | Açıklama | Kaynak |
|---|---|---|---|---|---|
| `id` | uuid | Hayır | – | UUIDv7 (denetim dinleyicisinin ekleme anında üretilir) | ERD |
| `actor_id` | uuid | Evet | – | Sistem işleminde boş | DM |
| `action` | text | Hayır | – | Backend §12.5 eylem kataloğu | DM |
| `resource_type` | text | Hayır | – | `ALERT`, `ALERT_RECIPIENT`, `TASK`, `TASK_ASSIGNMENT`, `GROUP`, `GROUP_MEMBERSHIP`, `OPERATION`, `ROLE_ASSIGNMENT`, `USER`, `SESSION`, `INVITATION`, `LOCATION`, `NOTIFICATION`, `MEETING_POINT` | DM |
| `resource_id` | uuid | Evet | – | Çok biçimli, FK yok | DM |
| `operation_id` | uuid | Evet | – | Kapsam filtresi, Genişletilmiş AC-27.4 | FİZ |
| `client` | text | Hayır | – | `WEB`, `ANDROID`, `SYSTEM` | FİZ |
| `trace_id` | text | Evet | – | | FİZ |
| `at` | timestamptz | Hayır | – | **İşlemin gerçekleştiği an** (olaydan gelir; ekleme anı değil). LD-05'te pencere başlangıcı | DM |
| `details` | jsonb | Evet | – | İçerik, koordinat, parola, belirteç **yok** (AC-27.2) | DM |

Kısıtlar: `pk_audit_entries (id)`, `fk_audit_entries_actor → identity.users` RESTRICT, `ck_audit_entries_action` (`action ~ '^[A-Z][A-Z_]{2,63}$'`; katalog backend'de), `ck_audit_entries_resource_type`, `ck_audit_entries_client`, `ck_audit_entries_details` (`details IS NULL OR jsonb_typeof(details) = 'object'`).

**Sıralama `at` ile, `id` ile değil:** Denetim kayıtları outbox üzerinden **zaman uyumsuz** yazıldığından (backend §12.5) ve LD-05 kayıtları pencere sonunda eklendiğinden, `id` (ekleme anı) sırası olay sırasından sapabilir. Backend AU-01 "zaman azalan" ile mutabık. İndeksler: `ix_audit_entries_at (at DESC, id DESC)` (liste + imleç `(at, id)`), Genişletilmiş filtreler için `ix_audit_entries_actor (actor_id, at DESC, id DESC)`, `ix_audit_entries_action (action, at DESC, id DESC)`, `ix_audit_entries_resource (resource_type, resource_id, at DESC)`, `ix_audit_entries_operation (operation_id, at DESC)`.

Yetki: `app_runtime` yalnızca `SELECT, INSERT` (§2.3). LD-05 ara tablosu §6.7.

### 12.6 Mutabakat bekleyen konular (backend-architect'e iletildi)

| # | Konu | DB önerisi | Gerekçe |
|---|---|---|---|
| 1 | `idempotency_keys.response_body` tipi | `text` (backend `jsonb` önermişti) | "İlk yanıt **aynen** döner" kuralı bayt düzeyinde aynı gövdeyi ister; `jsonb` anahtar sırasını ve boşlukları değiştirir. Gövdeyi sorgulama ihtiyacı yok ve `jsonb` kullanım listesi dar tutuluyor (§1.1) |
| 2 | TK-11 kapasite-dolu yolu (işlem commit + 409) | 409 yolunda işlem kendi eklediği idempotency satırını commit'ten önce siler (§8.3) | Aksi hâlde 2xx olmayan yanıt saklanır veya işlem geri alınıp `CAPACITY_FULL` durumu kaybolur |
| 3 | Kapasite DB güvencesi | `tasks.accepted_count` + `ck_tasks_capacity_not_exceeded` | Backend yalnızca `capacity > 0` yedeğini öneriyor; AC-14.6 en kritik eşzamanlılık kuralıdır, uygulama hatasında bile kapasite aşılamamalı. Kolon `acceptedCount` gösterimini de JOIN'siz verir |
| 4 | Görevde toplanma noktası | **LD-26:** metin (`tasks.meeting_point`, ≤ 300). Backend `TaskCreateRequest.meetingPoint`'i `string` yapmalı | Karar verildi |
| 5 | Alıcı grubu | `via_group_id` tekil → API `group` | Temel dokümanla uyum; çoklu grup gerekiyorsa sorguda üyeliklerden türetilir ama tarihsel olarak doğru olmaz |
| 6 | `ALERT_REMINDER` bildirim türü | Kaldırılsın (yalnızca FCM `type`) | DM §2 |
| 7 | Kurum düzeyi `GON` | Yok (backend §6 geçerli çiftleriyle aynı, `ck_role_assignments_role_scope`) | Mutabık |
| 8 | Gelen kutusu sıralaması | `id DESC` tek anahtar; "açık alarmlar önce" ayrı sorgu (`GET /me/alerts`) | İmleçli sayfalama iki modüle yayılan bileşik sıralamayı destekleyemez (`database-review-of-backend.md` B-05) |
| 9 | LD-05 sayacı | §6.7 ara tablo deseni | Çökme güvenli, yalnızca ekleme korunur |

### 12.7 `platform.event_publication` (Spring Modulith)

Yapı Spring Modulith'in JDBC olay yayın kaydı sürümüne aittir; elle tasarlanmaz, Flyway'e Modulith'in yayımladığı şemadan alınır (`id`, `listener_id`, `event_type`, `serialized_event`, `publication_date`, `completion_date`; yeni sürümlerde ek durum kolonları). Fiziksel ekler: tamamlanmamış yayınlar için kısmi indeks `(publication_date) WHERE completion_date IS NULL`, temizlik §9.4. Şema adının `platform` olarak yapılandırılabildiği seçilen Modulith sürümünde doğrulanmalıdır.

**LD-28 (karar):** `location.updated` gibi yüksek frekanslı SSE olayları kalıcı outbox'tan **geçirilmemelidir** (günde 108 000 – 216 000 yayın satırı ve vakum yükü). Bunlar işlem sonrası bellek içi olay olarak yayınlanır; SSE zaten doğruluk kaynağı değildir (RT-K2). Kalıcı outbox yalnızca kaybolmaması gereken olaylar içindir (`AlertSent`, `NotificationRequested`, `OperationStateChanged`, `MembershipChanged`, denetim olayları…).

---

## 13. team-lead için açık noktalar

Bunlar kesinleşmiş kararları **değiştirmez**; mantıksal modelde boşluk kalan veya fiziksel eklemenin onay gerektirdiği noktalardır. "Backend" sütunu T-03 sonrası backend-architect'in tutumudur.

| # | Konu | Öneri | Backend | Etkisi onaylanmazsa | Karar |
|---|---|---|---|---|---|
| AN-01 | Taslak alarm ve görev hedeflerinin saklanacağı yer yok (TD-05 gönderimde yeniden çözüm ister) | `alerts.target_expr`, `tasks.target_expr` `jsonb` (biçim `audience_expr` ile aynı) + `alerts.audience_fingerprint`. `jsonb` kullanım listesine (`api-and-data-design-basis.md` §4.2) ekleme gerektirir | DB önerisini bekliyordu | `alert_targets`/`task_targets` ara tabloları | **LD-27** kabul |
| AN-02 | Alıcının/atamanın geldiği grup | `via_group_id` (tekil, anlık görüntü) | `groups[]` önermişti | Grup sütunu her istekte üyeliklerden türetilir, geçmişte yanlış olur | Backend ile (B-17) |
| AN-03 | `AlertRecipientView.sentAt` için alıcı satırında alan yok | `alert_recipients.sent_at`, `queued_at`, `first_responded_at` | Uyumlu | `deliveries`'ten toplama | Mutabık |
| AN-04 | Yeniden gönderimde `attempt` 0'dan başlar → DM §5B teslim tekilliği bozulur | `deliveries.round` + kuyruk kolonları | Uyumlu (AL-10 "attempt 0, aynı bildirim") | Yeniden gönderim yeni `Notification` olmalı | Backend ile (B-01) |
| AN-05 | "FCM token benzersiz" kuralı kullanıcı değişiminde geçmiş satırlarla çakışır | Yalnızca aktif satırlarda benzersiz + `installation_id` | Düz benzersiz önermişti | Eski cihaz satırları silinmeli (teslim geçmişi bozulur) | Backend ile (B-11) |
| AN-06 | Oturum/zincir kimliği | `refresh_tokens.family_id` (JWT `sid`) | Mutabık | – | Mutabık |
| AN-07 | `Alert.response_options` | Saklanmaz (K-13 sabit) | Mutabık (API sabit liste döner) | Sabit değerli gereksiz kolon | Mutabık |
| AN-08 | Görev ve alarm önceliği değer kümeleri | Görev `LOW/NORMAL/HIGH/URGENT`; alarm `HIGH/CRITICAL` | Mutabık (backend N-06) | – | Mutabık; team-lead onayı (N-06) |
| AN-09 | `REMOVED`/`REJECTED`/`CAPACITY_FULL` atamanın yeniden `PENDING`'e alınması; kabul edilmiş atama kaldırılınca `CAPACITY_FULL`'lar geri açılmaz | Yeni geçiş: elle yeniden atama = mevcut satırın `UPDATE`'i | Mutabık (N-17) | Durum makinesinde tanımsız geçiş | Mutabık |
| AN-10 | AC-15.2 görev geçmişi | `tasks.task_state_changes` (§5.5) | `history[]` alanını tanımladı, saklama yerini DB'ye bıraktı | Denetimden okuma (K-26 ve modül yönüyle çelişir) | **LD-24** kabul |
| AN-11 | Rol–kapsam çiftleri | `ck_role_assignments_role_scope`; kurum düzeyi `GON` yok | Mutabık | – | **LD-23** kabul |
| AN-12 | Aynı e-posta + kapsam için tek bekleyen davet | `ux_invitations_pending_email_scope` → `409 ALREADY_EXISTS` | Mutabık | Çift davet | Mutabık |
| AN-13 | Duyuru listesi (WEB-07) operasyona göre süzülemiyor | `notifications.operation_id` | Uyumlu (NT-03 `operationId?`) | `jsonb` içinde arama | Mutabık |
| AN-14 | Operasyon kapanınca `last_locations` | Kapanış zincirinde silinsin (veri en aza indirme). Backend N-04 (A) seçilirse `STOPPED` paylaşımın koordinatı zaten gösterilmediğinden durdurmada silme de değerlendirilebilir | "Birlikte öneri" bekliyordu | Son konumlar dönem sonuna kadar kalır | **LD-25** kabul (silinir) |
| AN-15 | Arşivlenen grubun üyelikleri | Değiştirilmez; erişim `archived_at` ile kapanır | – | Arşivde toplu güncelleme | Açık değil (DB kararı) |
| AN-16 | Genişletilmiş AC-03.4 parola sıfırlama belirteci | `identity.password_reset_tokens` (`id`, `user_id`, `token_hash` benzersiz, `expires_at` = +30 dk, `used_at`, `created_at`; kısmi indeks `(user_id) WHERE used_at IS NULL`) — *Genişletilmiş* | ID-27/28 bunu gerektiriyor | AC-03.4 uygulanamaz | Bekliyor (Genişletilmiş, §10.3) |
| AN-17 | Genişletilmiş AC-31.3 bildirim izni | `devices.notification_permission` (`GRANTED`/`DENIED`, boş = bilinmiyor), `alarm_channel_enabled`, `permission_reported_at` | `DeviceRegisterRequest.notificationsEnabled`, `ParticipantView.notificationPermission` | AC-31.3 uygulanamaz | **LD-29**: yalnızca Genişletilmiş (§10.3) |
| AN-18 | Yeni hata kodları | Backend N-05 kümesi (`ALREADY_EXISTS`, `GROUP_FULL` — önerilen ad `GROUP_MEMBER_LIMIT` LD-36 ile `GROUP_FULL` oldu —, `OPERATION_CLOSED`, `AUTH_UNAUTHENTICATED`) yeterli; DB'nin önceki önerileri (`EMAIL_ALREADY_REGISTERED`, `INVITATION_ALREADY_PENDING`, `GROUP_FULL`) **geri çekildi**. Açık kalan: "en fazla 5 aktif operasyon" için kod (`ACTIVE_OPERATION_LIMIT` veya `INVALID_STATE_TRANSITION`) | N-05 | Genel kodlar | **LD-20** ile kapandı (`ACTIVE_OPERATION_LIMIT` yok) |
| AN-19 | Yüksek frekanslı SSE olayları ve outbox | Konum olayları kalıcı outbox dışında (§12.7) | – | Günde 100 – 200 bin gereksiz yayın satırı | **LD-28** kabul |
| AN-20 | Metin uzunluk sınırları | Backend §12.4 değerleri esas alındı (§1.4) | Mutabık (N-15) | Hafta 3'te sabitlenir | Mutabık |
| AN-21 | Görevde toplanma noktası metin mi nesne mi (§12.6 madde 4) | A: API metin (DM'ye sadık, öneri). B: `tasks.mp_*` kolonları | Nesne önerdi | – | **LD-26**: metin |
| AN-22 | LD-05 sayaç deseni | §6.7 kalıcı ara tablo + pencere sonunda tek ekleme | İletildi | Bellek içi sayaç çökmede denetim kaydını kaybeder | **LD-22** kabul |
