# Activity Diagram — Acil alarmın oluşturulması, gönderilmesi, yanıtlanması ve iptali

> Kaynak: K-09, K-13, K-17, K-24, K-31, BR-04, US-20 – US-25, TD-03, TD-05 – TD-10. Ayrıntılı tasarım: `../design/emergency-alert-design.md`. Otomatik eskalasyon (R2) gösterilmez.

```mermaid
flowchart TD
  S(("Başla"))

  subgraph L1["KOR — Web"]
    K1["WEB-09: mesaj, talep edilen eylem,<br/>hedef (operasyon kapsamı)"]
    K2["Onay penceresi:<br/>BU BİR ACİL ALARMDIR + alıcı listesi"]
    K3{"Onaylıyor mu?"}
    K4["WEB-10: alıcı bazlı durum izleme"]
    K5{"İşlem?"}
    K6["Yanıtsız/başarısızlara yeniden gönder"]
    K7["İptal et (isteğe bağlı: düzeltilmiş alarm, TD-09)"]
    K8["Kapat (TD-06)"]
  end

  subgraph L2["Sistem"]
    S1{"MFA · yetki · operasyon ACTIVE ·<br/>hedef kapsamda?"}
    S1x["Red (403 / 409)"]
    S2["Taslak + hedef kitle önizlemesi<br/>gönderen hariç, en fazla 500"]
    S3{"Hedef kitle önizlemeden<br/>sonra değişti mi?"}
    S4["Alarm OPEN · alıcılar QUEUED<br/>CRITICAL bildirim · denetim kaydı"]
    S5["CRITICAL kuyruk → FCM<br/>(cihaz başına)"]
    S6{"FCM sonucu?"}
    S7["3 deneme, üstel bekleme"]
    S8["Alıcı FAILED"]
    S9["Alıcı SENT"]
    S10{"2 dk içinde yanıt var mı?"}
    S11{"Hatırlatma sayısı < 5 mi?"}
    S12["Hatırlatma gönder (aynı collapse_key)"]
    S13["Alıcı UNANSWERED"]
    S14["Alıcı RESPONDED · hatırlatmalar durur<br/>SSE (≤ 5 sn) · denetim kaydı"]
    S15["Alarm CANCELLED · hatırlatmalar durur<br/>tüm alıcılara iptal bildirimi"]
    S16["Alarm CLOSED · hatırlatmalar durur"]
  end

  subgraph L3["Gönüllü — Android"]
    G1["FCM alınır · durum sunucudan doğrulanır (TD-10)<br/>alındı bilgisi → DELIVERED"]
    G2{"Uygulama ön planda mı?"}
    G3["Tam ekran alarm (AND-08)"]
    G4["alarm kanalında yüksek önemli bildirim<br/>dokununca tam ekran"]
    G5["Görüldü → SEEN"]
    G6{"Yanıt veriyor mu?"}
    G7["Aldım / Yardım gerekiyor / Göreve gidiyorum"]
    G8{"Ağ var mı?"}
    G9["WorkManager ile yeniden dene<br/>(idempotent)"]
    G10["ALARM İPTAL EDİLDİ ekranı"]
  end

  E(("Bitiş"))

  S --> K1 --> S1
  S1 -- "hayır" --> S1x --> E
  S1 -- "evet" --> S2 --> K2 --> K3
  K3 -- "hayır" --> E
  K3 -- "evet" --> S3
  S3 -- "evet" --> S2
  S3 -- "hayır" --> S4 --> S5 --> S6
  S6 -- "geçici hata" --> S7 --> S6
  S6 -- "kalıcı hata / cihaz yok" --> S8 --> K4
  S6 -- "kabul" --> S9 --> G1 --> G2
  G2 -- "evet" --> G3 --> G5
  G2 -- "hayır" --> G4 --> G5
  G5 --> G6
  G6 -- "evet" --> G7 --> G8
  G8 -- "evet" --> S14 --> K4
  G8 -- "hayır" --> G9 --> G8
  G6 -- "hayır" --> S10
  S9 --> S10
  S10 -- "evet" --> S14
  S10 -- "hayır" --> S11
  S11 -- "evet" --> S12 --> G1
  S11 -- "hayır" --> S13 --> K4
  K4 --> K5
  K5 -- "yeniden gönder" --> K6 --> S4
  K5 -- "iptal" --> K7 --> S15 --> G10 --> E
  K5 -- "kapat" --> K8 --> S16 --> E
```

**Açıklama:** Alarmı yalnızca aktif operasyonun KOR'u, MFA'lı oturumla ve onay adımından geçerek gönderir (K-09, K-17, K-19, BR-04). Onay ile gönderim arasında hedef kitle değiştiyse yeni önizleme istenir (TD-05). Gönderim `CRITICAL` kuyruktan cihaz başına FCM mesajıyla yapılır; geçici hatalar 3 kez denenir, kalıcı hatada alıcı "Gönderilemedi" olur. Android'de uygulama ön plandaysa tam ekran alarm açılır; değilse yüksek önemli bildirim çıkar ve dokununca tam ekran açılır. Kilit ekranında kendiliğinden tam ekran açılması MVP'de **yoktur** (RT-03). Yanıt vermeyene 2 dk aralıkla en fazla 5 hatırlatma gider, sonra alıcı "Yanıtsız" olur (K-13). İptal bildirimi yanıtlamış olanlar dahil tüm alıcılara gider. Yeniden gönderim yalnızca `UNANSWERED` ve `FAILED` alıcılar içindir. Sessiz mod, DND ve zorla durdurulmuş uygulama durumlarında alarmın ulaşması veya ses çıkarması garanti edilmez.
