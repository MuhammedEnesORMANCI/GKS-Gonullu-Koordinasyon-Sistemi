# Activity Diagram — Gönüllünün davetle sisteme katılması

> Kaynak: K-06, K-10, SC-01, US-01, US-02, US-03, TD-02, TD-17. Kesik çerçeveli adım Genişletilmiş MVP'dir.

```mermaid
flowchart TD
  S(("Başla"))

  subgraph L1["ADM veya KOR — Web"]
    A1["Davet formu: e-posta + kapsam<br/>(kurum veya kendi operasyonu)"]
  end

  subgraph L2["Sistem"]
    B1{"Yetkili mi?<br/>ADM: kurum · KOR: kendi operasyonu"}
    B1x["403 — işlem reddedilir"]
    B2{"E-posta kayıtlı<br/>bir kullanıcıya mı ait?"}
    B3["Operasyon daveti olarak<br/>uygulama içi bildirim + e-posta<br/>(TD-01, ayrı akış)"]
    B4["Davet oluştur: tek kullanımlık,<br/>72 saat geçerli; denetim kaydı"]
    B5["E-posta gönder: bağlantı + davet kodu<br/>(demoda Mailpit yakalar)"]
    B6{"Davet geçerli mi?<br/>süre · kullanılmış mı"}
    B6x["Süresi doldu / kullanılmış mesajı"]
    B7["Hesap ACTIVE · GON rolü (kurum)<br/>operasyon davetiyse GON rolü (operasyon)<br/>davet kullanıldı · denetim kaydı"]
    B8["FCM token kaydı"]
  end

  subgraph L3["Gönüllü — Android"]
    C1["https bağlantısı statik davet sayfasını açar<br/>(kod + uygulamayı aç) veya<br/>kodu AND-02'ye elle girer"]
    C2["Parola, ad-soyad, görünen ad,<br/>isteğe bağlı telefon girer (K-10)"]
    C3["Aydınlatma metnini okur ve onaylar"]:::ext
    C4{"Bildirim izni verildi mi?<br/>(Android 13+)"}
    C5["Ana sayfada kalıcı uyarı<br/>(AC-31.2)"]
    C6["AND-03 Ana sayfa"]
  end

  E(("Bitiş"))

  S --> A1 --> B1
  B1 -- "hayır" --> B1x --> E
  B1 -- "evet" --> B2
  B2 -- "evet" --> B3 --> E
  B2 -- "hayır" --> B4 --> B5 --> C1 --> B6
  B6 -- "hayır" --> B6x --> E
  B6 -- "evet" --> C2 --> C3 --> B7
  B7 --> C4
  C4 -- "evet" --> B8 --> C6
  C4 -- "hayır" --> B8
  B8 -. "izin yoksa" .-> C5 --> C6
  C6 --> E

  classDef ext stroke-dasharray: 5 5
```

**Açıklama:** Açık kayıt yoktur. Davet yalnızca ADM (kurum geneli) veya KOR (kendi operasyonu) tarafından oluşturulur. Davet tek kullanımlıktır, 72 saat geçerlidir ve e-postada hem bağlantı hem elle girilebilir kod bulunur (TD-02). Aydınlatma metni adımı Genişletilmiş halkadadır; Çekirdekte atlanır (CON-15). Bildirim izni reddedilse de hesap etkinleşir, ama alarm alınamayacağı için ana ekranda kalıcı uyarı gösterilir. Kayıtlı bir kullanıcıya yapılan operasyon daveti ayrı akıştır: `activity-join-operation.md`.
