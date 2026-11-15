# Activity Diagram — Görev atanması ve görev durumunun güncellenmesi

> Kaynak: K-12, VS-17, SC-04, US-13 – US-16, AC-14.4 – AC-14.6, TD-16. Görev durumları: `DRAFT → PUBLISHED → IN_PROGRESS → DONE / CANCELLED`. Atama durumları: `PENDING → ACCEPTED / REJECTED / CAPACITY_FULL / REMOVED`.

```mermaid
flowchart TD
  S(("Başla"))

  subgraph L1["KOR — Web"]
    K1["Görev taslağı: başlık, hedef (kişi/grup),<br/>isteğe bağlı kapasite, yanıt süresi (vars. 60 dk)"]
    K2["Görevi yayınla"]
    K3["Atamaları ve kapasiteyi izler (3/5)"]
    K4{"Görev ne durumda?"}
    K5["Devam ediyor olarak işaretle"]
    K6["Tamamlandı olarak işaretle"]
    K7["Görevi iptal et"]
    K8["Atamayı kaldır (gerekirse)"]
  end

  subgraph L2["Sistem"]
    S1["Yayın anındaki grup üyelerinden<br/>kişi bazlı atamalar (TD-16) · PENDING"]
    S2["HIGH öncelikli bildirim<br/>(FCM + uygulama içi)"]
    S3{"Kabul mü, ret mi?"}
    S4["Görev satırını kilitle,<br/>kabul sayısını say"]
    S5{"Kapasite dolu mu?"}
    S6["Atama CAPACITY_FULL<br/>kişiye bilgi"]
    S7["Atama ACCEPTED"]
    S8{"Bu kabulle kapasite<br/>doldu mu?"}
    S9["Kalan PENDING atamalar CAPACITY_FULL<br/>ilgili kişilere bildirim"]
    S10["Atama REJECTED"]
    S11["SSE ile panel güncellenir (≤ 10 sn)<br/>denetim kaydı"]
    S12["Yanıt süresi dolan PENDING<br/>atamalar işaretlenir (AC-16.3)"]
    S13["Atananlara durum/iptal bildirimi"]
  end

  subgraph L3["Gönüllü — Android"]
    G1["AND-05 Görevlerim:<br/>görevi ve kapasiteyi görür"]
    G2["Kabul veya ret"]
  end

  E(("Bitiş"))

  S --> K1 --> K2 --> S1 --> S2 --> G1 --> G2 --> S3
  S3 -- "ret" --> S10 --> S11
  S3 -- "kabul" --> S4 --> S5
  S5 -- "evet" --> S6 --> S11
  S5 -- "hayır" --> S7 --> S8
  S8 -- "evet" --> S9 --> S11
  S8 -- "hayır" --> S11
  S1 -. "süre dolunca" .-> S12 --> K3
  S11 --> K3 --> K4
  K4 -- "başladı" --> K5 --> S13
  K4 -- "bitti" --> K6 --> S13
  K4 -- "vazgeçildi" --> K7 --> S13
  K4 -- "kişi değişikliği" --> K8 --> S13
  S13 --> E
```

**Açıklama:** Görevi yalnızca operasyonun KOR'u oluşturur ve durumunu ilerletir (K-12, VS-17); grup lideri ve gönüllü görev durumunu değiştiremez. Kabul, kapasite kontrolüyle aynı işlemde ve satır kilidiyle yapılır; eşzamanlı kabuller kapasiteyi aşamaz (AC-14.6). Kapasite dolunca bekleyen atamalar kapanır. Gönüllü kabul ettiği görevi bırakamaz; gerekirse KOR atamayı kaldırır (AC-14.4). Yanıt süresinin dolması atamanın durumunu değiştirmez, yalnızca panelde işaretler. Liste filtreleri (AC-16.2) Genişletilmiş halkadadır ve akışta gösterilmez.
