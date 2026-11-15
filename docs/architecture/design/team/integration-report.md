# Takım Çalışması Birleştirme Raporu (T-08)

| Alan | Bilgi |
|---|---|
| Tarih | 15.11.2026 |
| Hazırlayan | team-lead |
| Durum | **Takım çalışması sonlandırıldı.** Üyeler kullanım limiti nedeniyle son kararların bir kısmını işleyemeden durdu; eksikler §4'te. Bu rapor yeni karar içermez |

## 1. Görev durumu

| Görev | Sahip | Çıktı | Durum |
|---|---|---|---|
| T-01 API sözleşme tasarımı | backend-architect | `backend-api-design.md` v1.0 (1142 satır) | ✅ Taslak tam; LD-30 – LD-41 işlenmedi (§4) |
| T-02 Fiziksel veri modeli | database-architect | `database-physical-design.md` v1.2 (1210 satır) | ✅ Tam; LD-33, LD-35, LD-37, LD-40 işlenmedi (§4) |
| T-03 Backend ↔ Database uyumu | ikisi | Backend §15, Database §12 | ⚠️ Kısmen: §3'teki açıklar kaldı |
| T-04 Database'in backend incelemesi | database-architect | `database-review-of-backend.md` (24 bulgu, 4 Yüksek) | ✅ İnceleme tam; B-01 – B-04 çözülmedi |
| T-05 Backend'in database incelemesi | backend-architect | `backend-review-of-database.md` (2 Yüksek, 7 Orta, 7 Düşük) | ✅ İnceleme tam; Yüksekler LD-22 ve LD-40 ile karara bağlandı |
| T-06 Android teknik doğrulama | android-developer | `android-technical-validation.md` v1.5 | ✅ **Tam**; LD-06 – LD-39'un tamamı işlendi, açık nokta yok |
| T-07 Web ekran–API eşleştirmesi | web-developer | `web-screen-api-mapping.md` v1.0 | ✅ Taslak tam; LD-19, LD-33, LD-35 – LD-38 işlenmedi (§4) |
| T-08 Birleştirme | team-lead | Bu rapor | ✅ |

## 2. Lider kararları

**LD-01 – LD-41** alındı; tamamı `TASKS.md` "Lider kararları" bölümünde. Hiçbiri K-01 – K-33, TD-01 – TD-22 veya MVP halkalarını değiştirmez; tümü mevcut kararların yorumu, uygulama ayrıntısı veya Android/Web platform sınırının dürüst ifadesidir. Kesin kararların işlendiği ortak dokümanlar:

| Doküman | İşlenen LD'ler |
|---|---|
| `authorization.md` | LD-01, LD-34, LD-38 |
| `decisions.md` (ADR-012, ADR-013) | LD-01, LD-18, LD-19 |
| `api-and-data-design-basis.md` | LD-03, LD-04, LD-05, LD-30, LD-31, LD-37, LD-38 |
| `communication.md` §1.5 | LD-08, LD-09, LD-12, LD-14, LD-32 |
| `analysis/user-stories.md` | LD-06, LD-07, LD-11, LD-13, LD-17 (AC-17.8, AC-17.9, AC-21.5, AC-31.2) |
| `technical-risks-validation.md` | LD-06 (G-11) |
| `ui-flows/web-flows.md`, `emergency-alert-design.md`, `diagrams/sequence-alert.md` | LD-02, LD-14 |

## 3. Kontroller

| Kontrol | Sonuç |
|---|---|
| **Yenileme toleransı = 10 sn (LD-18)** | ✅ Tüm belgelerde 10 sn. Backend §14 N-22 satırı ve web §1 cümlesi lider düzeltmesiyle güncellendi; 60 sn kalıntısı yok |
| **LD-30, LD-31, LD-32** | ✅ `api-and-data-design-basis.md` (§1 Date başlığı, §3.4 UNAVAILABLE bildirimi), `communication.md` §1.5 (sıra garantisi yok) ve Android belgesine işlendi. Backend belgesinde **yok** (§4) |
| **Kod üretimi** | ✅ Yok. Yalnızca Markdown; tek diyagram web belgesindeki Mermaid akışı |
| **MVP dışı sızıntı** | ✅ Görülmedi. Web §9 ve Android §1 kapsam dışını açıkça listeliyor; LD-21 (arama) ve LD-20 (operasyon sınırı) kapsam genişlemesini reddetti |
| **Backend ↔ Database uyumu** | Mutabık: kimlik/tip eşlemesi, enum'lar, alan uzunlukları, idempotency deseni, kısıt adlandırma ve kısıt ↔ hata kodu eşlemesi, `target_expr` jsonb, `response_window_minutes`, kurum düzeyinde GON olmaması, LD-05 ara tablosu. **Açık:** §4.2 |

## 4. Tamamlanmayan işler ve çözülmeyen sorunlar

### 4.1 Belgeye işlenmemiş kararlar (karar verildi, metin eksik)

| Belge | Eksik LD'ler |
|---|---|
| `backend-api-design.md` | LD-24 – LD-28 (kısmen), LD-30 – LD-41: özellikle LD-33 `audienceHash`, LD-37 `/invitations/lookup` ve `/invitations/accept`, LD-39 `sessionId`, LD-40 kira deseni, LD-36 ek hata kodları |
| `database-physical-design.md` | LD-33 (fingerprint saklanmaz), LD-35, LD-37, LD-40 (kira kolonları + indeks) |
| `web-screen-api-mapping.md` | LD-19, LD-33, LD-35, LD-36, LD-37 (`/davet` yapı notu), LD-38 |
| Ortak dokümanlar (lider) | `api-and-data-design-basis.md` §2'ye LD-36 kodları; `domain-model.md`'ye LD-24 (`task_state_changes`), LD-25, LD-40 alanları; `activity-invitation`, `sequence-invitation`, `erd` diyagramlarına LD-23 (kurum düzeyinde rol yok) ve LD-37 (yeni davet yolları) |

### 4.2 Çözülmemiş teknik konular

| # | Konu | Ciddiyet | Not |
|---|---|---|---|
| A-1 | ~~**B-02 (güvenlik):** davet kabulü yanıtındaki yenileme belirteci idempotency tablosuna düz metin yazılıyor~~ | Yüksek → **Çözüldü** | LD-42 (15.11.2026): gövde saklanmaz (`body_redacted`), tekrar `409 ALREADY_COMPLETED`, istek özeti HMAC-SHA-256, kod yalnızca HMAC ile kapsam anahtarında. Backend §3.6/§15.4, DB §8.3, testler T-IDEM-01 – 09; anahtar döndürme ve 409/410 ayrımı LD-42 (5)–(6) |
| A-2 | ~~B-01 yeniden gönderimde teslim tekilliği (`deliveries.round`)~~ | Yüksek → **Çözüldü** | LD-43: hatırlatma turu kilitli `resend_count`'tan; T-ALR-R01 – R09 |
| A-3 | ~~B-03 T-LOC-03 testi LD-05 ile çelişiyor~~ | Yüksek → **Çözüldü** | LD-44: şema DB §6.7 ile hizalandı, LC-05 işlem sınırı, eşzamanlı upsert; T-LOC-03 – 10 |
| A-4 | ~~B-04 kapasite-dolu yolunda idempotency işlem sınırı~~ | Yüksek → **Çözüldü** | LD-45 (`NULL` durum + ertelenmiş `ct_idempotency_keys_completed`), LD-46 (`CAPACITY_FULL` atama → `409 CAPACITY_FULL`); T-TSK-02 – 07, T-IDEM-11 – 14 |
| A-5 | Database §12.6 karşı önerileri (kapasite için DB sayacı + CHECK, ALERT_REMINDER'ın bildirim türü olmaması, denetim sırası, gelen kutusu sıralaması vb.) | Orta | Backend yanıtı yok |
| A-6 | LD-19 mutlak süre kuralının Android 30 günlük oturumuna uygulanıp uygulanmayacağı | Düşük | Database'in sorusu; backend yanıtı yok |
| A-7 | `database-review-of-backend.md` v0.9'da kaldı | Düşük | İçerik tam, sürüm güncellenmedi |

**Eksik dosya yok.** Planlanan yedi dosyanın tamamı diskte ve yarım yazılmış dosya yok.

## 5. Sonuç

Tasarım, kodlamaya başlamak için yeterli olgunlukta. Açık konuların hepsi belirli tabloları ve uçları etkiliyor (identity davet/oturum, alerts teslim ve yeniden gönderim, tasks kapasite). Proje iskeleti, ortak altyapı ve diğer modüller bu konulardan etkilenmiyor. A-1 – A-4 ilgili modülün kodlanmasından **önce** backend belgesine işlenmeli.
