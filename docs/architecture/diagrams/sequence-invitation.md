# Sequence Diagram — Gönüllü davet süreci

> Kaynak: K-06, K-10, US-01, US-02, TD-01, TD-02, TD-17. Uç nokta adları taslaktır.

```mermaid
sequenceDiagram
  autonumber
  actor K as ADM / KOR
  participant W as Web paneli
  participant ID as identity modülü
  participant AC as access modülü
  participant DB as PostgreSQL
  participant MP as Mailpit (SMTP)
  actor G as Davetli gönüllü
  participant A as Android uygulaması
  participant AU as audit modülü

  K->>W: E-posta + kapsam (kurum veya operasyon)
  W->>ID: POST /invitations (Idempotency-Key)
  ID->>AC: can(K, invitation.create, kapsam)
  alt Yetkisiz
    AC-->>W: 403
  else Yetkili
    ID->>DB: Kayıtlı kullanıcı var mı?
    alt Kayıtlı kullanıcı ve operasyon daveti (TD-01)
      ID->>DB: Operasyon daveti (72 saat)
      ID-->>W: 201 (uygulama içi bildirim + e-posta)
    else Yeni kişi
      ID->>DB: Davet: belirteç özeti, kod, kapsam, son geçerlilik = +72 saat
      ID->>MP: Davet e-postası (bağlantı + kod)
      ID->>AU: INVITATION_CREATED
      ID-->>W: 201 Davet oluşturuldu
    end
  end

  G->>MP: E-postayı okur (demoda Mailpit arayüzü)
  Note over G,A: https bağlantısı web sunucusundaki statik davet sayfasını açar (kod gösterilir, uygulama açılmaya çalışılır)
  G->>A: Uygulama açılır veya kod AND-02'ye elle girilir
  A->>ID: GET /invitations/{kod}
  alt Süresi dolmuş veya kullanılmış
    ID-->>A: 410 → Davet geçersiz mesajı
  else Geçerli
    ID-->>A: Davet bilgisi (kurum/operasyon adı)
    G->>A: Parola, ad-soyad, görünen ad, isteğe bağlı telefon
    A->>ID: POST /invitations/{kod}/accept
    ID->>DB: Kullanıcı ACTIVE + davet kullanıldı (tek işlem)
    ID->>AC: GON rolü (kurum) ve gerekirse GON rolü (operasyon)
    ID->>AU: INVITATION_ACCEPTED, ROLE_ASSIGNED
    ID-->>A: Erişim + yenileme belirteci
    A->>A: Bildirim izni iste (Android 13+, TD-17)
    A->>ID: PUT /devices (FCM token)
    A-->>G: AND-03 Ana sayfa (izin yoksa kalıcı uyarı)
  end
```

**Açıklama:** Davet tek kullanımlıktır; sunucu yalnızca belirtecin özetini saklar ve davet 72 saat geçerlidir. E-posta hem bağlantı hem kod içerir. Bağlantı bir https adresidir ve web sunucusundaki statik davet sayfasını açar. Sayfa kodu gösterir ve uygulamayı açmayı dener; e-posta istemcileri özel URI şemalı bağlantıları engellediği için bu yol seçildi. Uygulama açılamazsa kod elle girilir (TD-02). Demoda e-postalar Mailpit'te yakalanır; sentetik kullanıcılara gerçek e-posta gitmez (K-04). Davetin kabulü, hesabın etkinleşmesi ve rol atanması tek işlemde yapılır ve denetime yazılır. Kayıtlı kullanıcıya gönderilen operasyon daveti uygulamada kabul edilir (TD-01, `activity-join-operation.md`). Aydınlatma metni onayı Genişletilmiş halkadadır ve gösterilmez.
