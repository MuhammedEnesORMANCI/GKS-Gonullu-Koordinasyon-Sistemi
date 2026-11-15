# Activity Diagram — Konum paylaşımının başlatılması ve durdurulması

> Kaynak: K-14, K-15, K-16, BR-09, US-17 – US-19, AC-08.3, ADR-011, VS-18, TD-11, TD-18. Arka planda kesintisiz çalışma **garanti edilmez** (`../design/technical-risks-validation.md` G-07).

```mermaid
flowchart TD
  S(("Başla"))

  subgraph L1["Gönüllü — Android"]
    G1["AND-07: Paylaşımı başlat"]
    G2{"Konum izni<br/>(kullanım sırasında) var mı?"}
    G3["İzin iste"]
    G2b{"İzin verildi mi?"}
    G4["Açıklama + ayarlara bağlantı<br/>paylaşım KAPALI"]
    G5["Ön plan servisi başlar<br/>kalıcı bildirim: Paylaşılıyor · Durdur"]
    G6["60 sn'de bir konum al"]
    G7{"Ağ var mı?"}
    G8["Konum gönderilemiyor göstergesi<br/>ölçüm kuyruğa alınmaz (TD-11)"]
    G9["Kullanıcı Durdur der<br/>(ekrandan veya bildirimden)"]
    G10["Servis durur<br/>Paylaşım durdu"]
  end

  subgraph L2["Sistem"]
    S1{"Operasyon ACTIVE ve<br/>kişi katılımcı mı?"}
    S1x["Başlatılamaz (AC-17.4)"]
    S2["Paylaşım ACTIVE"]
    S3{"Paylaşım ve operasyon<br/>hâlâ aktif mi?"}
    S4["Son konumu üzerine yaz<br/>(geçmiş yok, K-16)<br/>saat farkı > 2 dk ise işaretle"]
    S5["SSE: operation konusu"]
    S6["Ölçüm reddedilir<br/>yanıt servisi durdurur"]
    S7["Paylaşım STOPPED"]
    S8["Operasyon kapandı:<br/>tüm paylaşımlar STOPPED<br/>+ FCM konumu durdur (TD-18)"]
  end

  subgraph L3["KOR — Web"]
    K1["WEB-08 Harita: son konum,<br/>ölçüm zamanı, güncellik etiketi<br/>(2 dk güncel değil · 15 dk son bilinen)"]
    K2["Her görüntüleme denetime yazılır"]
  end

  E(("Bitiş"))

  S --> G1 --> S1
  S1 -- "hayır" --> S1x --> E
  S1 -- "evet" --> G2
  G2 -- "hayır" --> G3 --> G2b
  G2b -- "hayır" --> G4 --> E
  G2b -- "evet" --> S2
  G2 -- "evet" --> S2
  S2 --> G5 --> G6 --> G7
  G7 -- "hayır" --> G8 --> G6
  G7 -- "evet" --> S3
  S3 -- "evet" --> S4 --> S5 --> K1 --> K2
  S4 --> G6
  S3 -- "hayır" --> S6 --> G10
  G6 -. "kullanıcı" .-> G9 --> S7 --> G10
  S8 -. "FCM mesajı" .-> G10
  S8 -. "mesaj ulaşmazsa" .-> S6
  G10 --> E
```

**Açıklama:** Paylaşımı yalnızca gönüllü (veya kendi konumu için grup lideri) başlatır ve istediği an durdurabilir (K-14). Paylaşım yalnızca aktif operasyonda ve operasyon katılımcısı için mümkündür. Konum, kalıcı bildirimli bir ön plan servisiyle 60 sn'de bir alınır; ayrı "her zaman" izni istenmez (ADR-011). Bu varsayım Hafta 2'de doğrulanır (TV-01). Ağ yokken ölçümler biriktirilmez; yalnızca son konum önemlidir. Operasyon kapanınca sunucu paylaşımları durdurur ve cihaza mesaj gönderir; mesaj ulaşmazsa sonraki ölçüm reddedilir ve servis durur (≤ 1 dk hedefi, AC-08.3). Konumu yalnızca aktif operasyonun KOR'u görür (K-15); grup lideri ve diğer gönüllüler göremez.
