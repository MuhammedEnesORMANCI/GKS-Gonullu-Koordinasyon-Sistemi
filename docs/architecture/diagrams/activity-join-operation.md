# Activity Diagram — Gönüllünün operasyona katılması

> Kaynak: K-06, K-07, K-11, K-32, SC-02, US-08, US-09, **TD-01 (kabul edildi)**. Operasyona katılım, `ROLE_ASSIGNMENT(role=GON, scope=OPERATION)` kaydıdır.

```mermaid
flowchart TD
  S(("Başla"))

  subgraph L1["ADM — Web"]
    A1["Operasyon oluştur (PLANNED)<br/>ve KOR ata"]
  end

  subgraph L2["KOR — Web"]
    K1["Katılımcı davet et"]
    K2["Grup oluştur ve lider seç<br/>(lider zorunlu, K-32)"]
    K3["Katılımcıyı gruba ekle"]
    K4["Operasyonu aktif et"]
  end

  subgraph L3["Sistem"]
    S1{"Davet edilen kişi<br/>kurumda kayıtlı mı?"}
    S2["Davetle katılım akışı<br/>(activity-invitation)<br/>sonunda GON (operasyon) rolü"]
    S3["Operasyon daveti: uygulama içi bildirim<br/>+ e-posta; 72 saat geçerli"]
    S4["GON rolü (operasyon) oluşur<br/>ParticipantAdded · denetim kaydı"]
    S5{"Kişi operasyon katılımcısı mı?"}
    S5x["Gruba eklenemez"]
    S6["Üyelik ACTIVE · MembershipChanged<br/>üyeye bildirim · denetim kaydı"]
    S7["Operasyon ACTIVE<br/>alarm ve konum paylaşımı artık mümkün"]
  end

  subgraph L4["Gönüllü — Android"]
    G1{"Operasyon davetini<br/>kabul ediyor mu?"}
    G1x["Davet reddedildi"]
    G2["AND-04 Aktif operasyon:<br/>grubum, toplanma noktası, görevlerim"]
  end

  E(("Bitiş"))

  S --> A1 --> K1 --> S1
  S1 -- "hayır" --> S2 --> S4
  S1 -- "evet" --> S3 --> G1
  G1 -- "hayır" --> G1x --> E
  G1 -- "evet" --> S4
  S4 --> K2 --> K3 --> S5
  S5 -- "hayır" --> S5x --> E
  S5 -- "evet" --> S6 --> K4 --> S7 --> G2 --> E
```

**Açıklama:** Operasyonu yalnızca ADM oluşturur ve KOR atar. KOR, kendi operasyonuna yeni kişileri davetle katar; kurumda zaten kayıtlı kişiler operasyon davetini uygulamada kabul eder (TD-01). Gruba yalnızca operasyon katılımcıları eklenebilir; grup lider seçilmeden oluşturulamaz (K-32). Sıralama zorunlu değildir: gruplar operasyon `PLANNED` iken de kurulabilir, ancak alarm ve konum paylaşımı yalnızca `ACTIVE` operasyonda mümkündür (K-17, K-14). Operasyon kapanınca operasyon kapsamlı roller pasifleşir ve gruplar arşivlenir (K-11). Gönüllünün kendi isteğiyle ayrılması R2'dedir (K-28) ve gösterilmez.
