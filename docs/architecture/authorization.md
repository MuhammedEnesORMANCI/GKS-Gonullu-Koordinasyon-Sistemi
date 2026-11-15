# Yetkilendirme Mimarisi

> **Durum: Kabul edildi** (ADR-002, ADR-013; TD-01, TD-14; K-07, K-08, K-09, K-15, K-19, K-26, K-27). Rol tanımları `docs/analysis/user-roles.md` ile uyumludur; o dosyadaki yetki matrisi bu tasarımın MVP eşleme tablosudur ve negatif testlerin veri kaynağıdır (US-28).

## 1. Neden bu model

Genişleme talebi iki farklı yetki kaynağı doğuruyor:
- **Kurumsal yetki:** Operasyonu yöneten koordinatör, kurum yöneticisi. Kapsamı kurum veya operasyondur.
- **Grup içi yetki:** Kullanıcıların kurduğu bir grubun sahibi veya moderatörü. Kapsamı yalnızca o gruptur.

Bu ikisi karışırsa ciddi bir risk oluşur: kendi grubunu kuran bir gönüllü, "grup yöneticisi" olduğu için acil alarm gönderebilir veya üyelerin konumunu görebilir hâle gelir. Model bu yüzden katmanlıdır ve **kritik izinlerin hangi katmandan gelebileceği sabittir.**

## 2. Katmanlar

```
can(kullanıcı, eylem, kaynak) =
     hesap aktif mi?                                          (MVP)
  ∧  izin  ∈  kurum rolü izinleri(kaynağın kapsamında)        (MVP)
           ∪  grup üyelik rolü izinleri(kaynağın grubunda)    (MVP: LEADER/MEMBER, R2+: genişler)
  ∧  grup politikası bu eyleme izin veriyor mu?               (R2+)
  ∧  kaynak kuralı (ör. operasyon AKTİF mi, ek CLEAN mi?)     (MVP)
  ∧  eylem "yalnızca kurum rolünden" listesinde ise
         izin grup rolünden gelmemiş olmalı                   (MVP'den itibaren)
```

| Katman | İçerik | MVP | Gelecek |
|---|---|---|---|
| **K1 – Kurum rol ataması** | `(user, role, scope_type, scope_id)`. Ör. `(ayse, COORDINATOR, OPERATION, op1)` | ✅ | – |
| **K2 – Grup üyelik rolü** | `GroupMembership.role`: `LEADER`, `MEMBER` | ✅ | `OWNER`, `ADMIN`, `MODERATOR` |
| **K3 – Grup politikası** | `Group.settings`: grup bazında açılıp kapatılan yetenekler | ❌ | R2+ |
| **K4 – Kaynak kuralları** | Durum bazlı koşullar (operasyon aktif, görev iptal değil, üyelik aktif) | ✅ | Ek tarama durumu, görüşme durumu |

**Uygulama:** MVP'de rol → izin eşlemesi koddaki **statik bir tabloda** tutulur (yapılandırma arayüzü yok). Her uç nokta, gerçek zamanlı abonelik ve dosya erişimi yalnızca `can()` çağırır. Yeni özellik eklemek; izin kataloğuna izin eklemek, eşleme tablosunu güncellemek ve yeni kaynak kuralı yazmak demektir.

## 3. Grup politikaları *(R2+)*

Grup bazında yapılandırılabilir ayarlar. Varsayılanlar grup türüne göre değişir. Ayarları kimin değiştirebileceği `group.settings.update` iznine bağlıdır.

| Ayar | Değerler | `OPERATIONAL` varsayılanı | `COMMUNITY` varsayılanı | Sürüm |
|---|---|---|---|---|
| `join_policy` | `MANAGED`, `INVITE`, `REQUEST`, `OPEN` | `MANAGED` | `INVITE` | R2 / R3 |
| `who_can_post` | `ALL_MEMBERS`, `ADMINS_ONLY` | `ALL_MEMBERS` | `ALL_MEMBERS` | R2 |
| `who_can_invite` | `ADMINS`, `ALL_MEMBERS` | `ADMINS` | `ADMINS` | R2 |
| `attachments_enabled` | evet/hayır | kurum kararı | kurum kararı | R3 |
| `who_can_start_call` | `ADMINS`, `ALL_MEMBERS`, `DISABLED` | `ADMINS` | `ALL_MEMBERS` | R4 |
| `video_enabled` | evet/hayır | hayır | hayır | R5 |

Kurum, grup politikalarına **üst sınır** koyabilir. Ör. "Topluluk gruplarında dosya paylaşımı kapalı" denirse grup sahibi bunu açamaz (kurum politikası > grup politikası).

## 4. Yalnızca kurum rolünden gelebilen izinler (değişmez kural)

Bu izinler **hiçbir koşulda** grup üyelik rolünden (K2) veya grup politikasından (K3) türetilemez:

| İzin | Gerekçe |
|---|---|
| `alert.send` | Acil alarm yalnızca yetkili kurumsal rollerce gönderilir (FR-13, RO-02) |
| `alert.cancel` | – |
| `location.view` | Konum en yüksek riskli veri kategorisi (§7.1, RT-07, RO-07) |
| `announcement.send.organization` | Kurum geneline duyuru (CON-04) |
| `operation.manage` | – |
| `role.assign` | Yetki yükseltme riski |
| `audit.view` | – |
| `user.contact.view` (başkasının iletişim bilgisi) | NFR-15. Grup üyeleri birbirinin telefonunu varsayılan olarak görmez |

**Karar (K-09, K-15):** Grup lideri alarm göndermez ve başkalarının konumunu görmez. `GroupMembership.role = LEADER` yalnızca grup bilgisi ve toplanma noktası düzenleme yetkisi verir (K-08). Kurum yöneticisi de operasyonel izinleri (`alert.send`, `location.view`, `task.manage`) yalnızca kendini o operasyona KOR olarak atayarak kazanır (K-07); gerekçeli acil erişim R2'dedir.

## 5. İzin kataloğu

`S` sütunu izni ilk kullanan sürümü gösterir. MVP'de yalnızca `MVP` satırları uygulanır; diğerleri, ad çakışması olmasın ve yetki modeli değişmesin diye ayrılmıştır.

### 5.1 Grup ve üyelik

| İzin | Açıklama | Kaynak katman | S |
|---|---|---|---|
| `group.create.operational` | Operasyon grubu oluşturma | K1 (KOR, kendi operasyonunda; ADM yalnızca KOR olarak atanarak, K-07). Lider seçimi zorunlu (K-32) | MVP |
| `group.create.community` | Topluluk grubu oluşturma | Aktif kullanıcı talep eder, ADM onaylar (K-27) | R3 |
| `group.view` | Grup bilgisi | K1 kapsamı veya K2 aktif üyelik | MVP |
| `group.update` | Ad, açıklama | K1 veya K2 (`LEADER`/`OWNER`/`ADMIN`) | MVP |
| `group.settings.update` | Grup politikaları | K2 (`OWNER`/`ADMIN`), kurum üst sınırına tabi | R2 |
| `group.archive` | Arşivleme | K1 veya K2 (`OWNER`) | MVP |
| `membership.add` | Üye ekleme | K1 (KOR, kendi operasyonunda), K2 (`ADMIN`, R2+) | MVP |
| `membership.remove` | Üye çıkarma | aynı | MVP |
| `membership.role.update` | Grup içi rol değiştirme | K1 veya K2 (`OWNER`/`ADMIN`) | MVP (lider atama) |
| `membership.leave` | Gruptan ayrılma | Kendisi (K-28) | R2 |
| `join_request.create` | Katılma talebi | Herhangi bir aktif kullanıcı, `join_policy = REQUEST` ise | R2 |
| `join_request.decide` | Talebi onaylama/reddetme | K1 veya K2 (`ADMIN`) | R2 |
| `invite_link.manage` | Davet bağlantısı | K2 (`ADMIN`) ve `who_can_invite` | R2 |
| `meeting_point.update` | Toplanma noktasını paylaşma/güncelleme | K1 (KOR, kendi operasyonu) veya K2 (`LEADER`, kendi grubu) (K-08) | MVP |

### 5.2 Mesajlaşma, dosya, görüşme

| İzin | Açıklama | Kaynak | S |
|---|---|---|---|
| `conversation.read` | Mesajları okuma | Aktif grup üyeliği (D-01) | R2 |
| `message.send` | Mesaj gönderme | Aktif üyelik ve `who_can_post` | R2 |
| `message.delete.own` / `message.delete.any` | Mesaj silme | Kendisi / K2 (`MODERATOR`+) | R2 / R3 |
| `attachment.upload` | Ek yükleme | `message.send` ve `attachments_enabled` | R3 |
| `attachment.download` | Ek indirme | Bağlamın okuma izni ve `scan_status = CLEAN` | R3 |
| `call.start` | Görüşme başlatma | Aktif üyelik ve `who_can_start_call` | R4 |
| `call.join` | Görüşmeye katılma | Aktif üyelik | R4 |
| `call.video` | Kamera açma | `video_enabled` | R5 |
| `call.moderate` | Katılımcının sesini kapatma, çıkarma | K2 (`MODERATOR`+) | R4 |

### 5.3 Bildirim ve iletişim

| İzin | Açıklama | Kaynak | S |
|---|---|---|---|
| `announcement.send.group` | Gruba duyuru | K1 (KOR kapsamında). R3: K2 (`ADMIN`) yalnızca kendi grubuna, yalnızca `NORMAL` öncelikte | MVP |
| `announcement.send.organization` | Kurum geneline duyuru | **Yalnızca K1** (ADM) | MVP |
| `alert.send` | Acil alarm | **Yalnızca K1** | MVP |
| `alert.cancel` | Alarm iptali | **Yalnızca K1** | MVP |
| `alert.close` | Açık alarmı kapatma (TD-06) | **Yalnızca K1** (KOR, kendi operasyonu) | MVP |
| `alert.resend` | Yanıtsız/başarısızlara yeniden gönderme | **Yalnızca K1** (KOR) | MVP |
| `alert.view` | Alarm ve alıcı durumlarını görme | K1 (KOR kendi operasyonu; ADM kurum geneli salt-okur) | MVP |
| `alert.respond` | Alarmı yanıtlama ve yanıtı değiştirme (TD-07) | Alıcının kendisi; kaynak kuralı: alarm `OPEN`, kişi alıcı listesinde | MVP |
| `alert.template.manage` | Grup bazlı alarm şablonu | **Yalnızca K1** | R2 |
| `notification.preference.update` | Kendi tercihleri (CRITICAL hariç) | Kendisi | R2 |

### 5.4 Operasyon, görev, konum ve denetim (MVP)

| İzin | Açıklama | Kaynak | S |
|---|---|---|---|
| `operation.create` | Operasyon oluşturma, KOR atama | **Yalnızca K1** (ADM) | MVP |
| `operation.manage` | Aktif etme, kapatma (askıya alma: Genişletilmiş) | **Yalnızca K1** (ADM, KOR kendi operasyonunda) | MVP |
| `invitation.create` | Davet oluşturma | K1 (ADM kurum, KOR kendi operasyonu) (K-06) | MVP |
| `invitation.respond` | Operasyon davetini kabul/ret etme (TD-01) | Davet edilen kullanıcının kendisi; kaynak kuralı: davet `PENDING`, süresi dolmamış | MVP |
| `user.status.view` | Hesap durum listesi (görünen ad, durum) | K1 (ADM, TEK) (TD-14) | MVP |
| `account.disable`, `session.revoke` | Hesabı devre dışı bırakma, oturumları iptal etme | K1 (ADM; TEK teknik gerekçeyle) (FR-22) | MVP |
| `task.manage` | Görev oluşturma, atama, kapasite, düzenleme, iptal, durum ilerletme, atama kaldırma | **Yalnızca K1** (KOR kendi operasyonunda) (K-12) | MVP |
| `task.respond` | Kendi atamasını kabul/ret | Atanan kişi; kaynak kuralı: atama `PENDING`, kapasite dolu değil | MVP |
| `location.share` | Kendi konumunu paylaşma | Kendisi; kaynak kuralı: operasyon `ACTIVE` ve kişi operasyona dahil (K-14) | MVP |
| `location.view` | Son konumu görme | **Yalnızca K1** (KOR, aktif operasyon) (K-15) | MVP |
| `audit.view` | İş denetim kayıtları | **Yalnızca K1** (ADM, UYM salt-okur) (K-26) | MVP |
| `mfa.reset` | İkinci faktörü sıfırlama | **Yalnızca K1** (ADM) (K-19) | MVP |
| `password.reset` | Başka kullanıcının parolasını sıfırlama (ilk girişte değişim zorunlu) | **Yalnızca K1** (ADM) (AC-03.5, LD-38) | MVP |

> **ADR-015 güncellemesi (16.11.2026, LD-62):** ADM, KOR ataması olmadan **tüm operasyonlarda** KOR'un operasyon kapsamlı yönetim izinlerine sahiptir (`task.manage`, `group.*`, `membership.*`, `meeting_point.update`, `announcement.send.group`; `operation.manage` ve `invitation.create` zaten vardı). `location.view` ve `alert.*` bu genişlemeye dahil değildir (K-15). Tablodaki "yalnızca KOR kendi operasyonunda" ifadeleri bu notla birlikte okunur. Kural tek yerde: `access/Grants.coordinates`.

> **ADR-014 güncellemesi (15.11.2026, LD-61):** ADM ve KOR'a dayanan kurumsal izinler, TOTP ile açılan 12 sa mutlak **Android yönetim oturumunda** (`mfa_verified = true`) da kullanılabilir. MFA'sız Android oturumu (gönüllü, 30 gün) hiçbir kurumsal izin taşımaz; TEK ve UYM izinleri yalnızca web'dedir. Aşağıdaki paragraf ADR-013 dönemindeki kuraldır ve bu not ile birlikte okunur.

**İstemci ve oturum kuralı (K-19, ADR-013, DR-12):** §4'teki ve bu bölümdeki kurumsal izinler (`operation.*`, `invitation.create`, `task.manage`, `location.view`, `alert.*` – `alert.respond` hariç, `announcement.send.*`, `role.assign`, `user.status.view`, `account.disable`, `session.revoke`, `audit.view`, `mfa.reset`) yalnızca **web oturumunda** kullanılabilir. **ADM veya KOR rolüne dayanan** izinler için oturumun ayrıca `mfa_verified = true` olması gerekir (K-19). TEK ve UYM'nin izinleri (`user.status.view`, `account.disable`, `session.revoke`, `audit.view`) MFA gerektirmez, çünkü K-19 TOTP'yi yalnızca ADM ve KOR için zorunlu kılar (LD-01). Android oturumu bu izinleri hiçbir zaman taşımaz; kullanıcı KOR veya ADM olsa bile Android'den gelen istek 403 ile reddedilir. Android oturumunun kullanabildiği izinler: `task.respond`, `location.share`, `alert.respond`, `invitation.respond`, `meeting_point.update` ve `group.update` (yalnızca LEADER, LD-38), `group.view`, grubuna atanan görevleri salt-okur görme (yalnızca LEADER, LD-34), kendi profili ve oturumu.

## 6. Yetkinin uygulandığı yerler

| Yer | Kural | S |
|---|---|---|
| REST/HTTP uç noktaları | Her istekte `can()`. Kaynak kapsamı sunucuda yüklenir; istemciden gelen kapsam bilgisine güvenilmez | MVP |
| Liste sorguları | Filtre, yetki kapsamı ile sorgu düzeyinde uygulanır (sonradan filtreleme yapılmaz) | MVP |
| Gerçek zamanlı abonelik | Konuya abone olurken `can()`. Üyelik değişince (`MembershipChanged`) aktif abonelik **sunucu tarafından düşürülür** | MVP |
| Bildirim hedef kitlesi | Hedef kitle, göndericinin kapsamıyla kesiştirilir | MVP |
| Dosya erişimi | Kısa ömürlü imzalı URL **yalnızca** `can(attachment.download)` sonrası üretilir. Kalıcı veya genel bağlantı yoktur | R3 |
| SFU erişim belirteci | Kısa ömürlü, tek odaya özel, `call.join` sonrası üretilir. Üyelik bitince oda sunucusundan çıkarma komutu gönderilir | R4 |
| Push içeriği | Kilit ekranında görünen push metni gizlilik düzeyine göre kısaltılır (ör. sohbet içeriği yerine "Yeni mesaj") | R2 |

## 7. Test stratejisi (mimari gereklilik)

- Yetki matrisi (`user-roles.md` §4) ve izin kataloğu **tek bir veri kaynağından** okunur. Testler bu kaynaktan otomatik üretilir (US-28).
- §4'teki izinler için, grup rolünden (`OWNER`, `ADMIN`, `LEADER`) gelen isteklerin reddedildiğini doğrulayan özel negatif testler yazılır. Bu testler R3'te topluluk grupları gelmeden önce, MVP'de yazılır.
- Abonelik düşürme testi: üye çıkarıldıktan sonra o konudaki olayları almaya devam etmemeli.
