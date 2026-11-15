# Sequence Diagram — Görev atama süreci

> Kaynak: K-12, VS-17, US-13 – US-16, AC-14.5, AC-14.6, TD-16, ADR-004, ADR-009. Uç nokta adları taslaktır.

```mermaid
sequenceDiagram
  autonumber
  actor K as KOR
  participant W as Web paneli
  participant T as tasks modülü
  participant AC as access modülü
  participant GR as groups modülü
  participant N as notifications modülü
  participant F as FCM
  participant A as Android uygulaması
  actor G as Gönüllü
  participant RT as realtime (SSE)

  K->>W: Görev formu (başlık, hedef, kapasite, yanıt süresi)
  W->>T: POST /tasks (taslak)
  T->>AC: can(K, task.manage, operasyon)
  T-->>W: 201 DRAFT
  K->>W: Yayınla
  W->>T: POST /tasks/{id}/publish (Idempotency-Key)
  T->>GR: Hedef grupların aktif üyeleri (anlık görüntü, TD-16)
  GR-->>T: Üye listesi
  T->>T: Kişi bazlı atamalar PENDING (tekilleştirilmiş)
  T->>N: NotificationRequested (TASK, HIGH) [outbox]
  T->>RT: AssignmentChanged
  RT-->>W: SSE: görev ve atamalar
  N->>F: Atanan her cihaza mesaj
  F-->>A: Push (görev kanalı)
  A-->>G: Yeni görev bildirimi
  G->>A: AND-05: görevi açar
  A->>T: GET /tasks/{id}
  T-->>A: Görev, kapasite (ör. 3/5), yanıt süresi

  G->>A: Kabul et
  A->>T: POST /assignments/{id}/accept (Idempotency-Key)
  T->>T: Görev satırını kilitle (SELECT ... FOR UPDATE), kabulleri say
  alt Kapasite dolu
    T->>T: Atama CAPACITY_FULL
    T-->>A: 409 Kapasite doldu
  else Yer var
    T->>T: Atama ACCEPTED
    opt Bu kabulle kapasite doldu
      T->>T: Kalan PENDING atamalar CAPACITY_FULL
      T->>N: Kapasite doldu bildirimleri (HIGH)
    end
    T-->>A: 200 Kabul edildi
  end
  T->>RT: AssignmentChanged
  RT-->>W: SSE (≤ 10 sn)
  W-->>K: Atama tablosu ve 4/5 güncellenir

  Note over K,T: Durum ilerletme yalnızca KOR (VS-17)
  K->>W: Devam ediyor / Tamamlandı / İptal
  W->>T: PATCH /tasks/{id}/state
  T->>N: Atananlara durum bildirimi
```

**Açıklama:** Gruba atanan görev, yayın anındaki aktif üyeler için kişi bazlı atamalara dönüşür (TD-16, AC-13.2). Kabul, görev satırı kilitlenerek kapasite kontrolüyle aynı işlemde yapılır; aynı anda gelen kabuller kapasiteyi aşamaz (AC-14.6). Kapasite dolunca bekleyen atamalar kapanır ve ilgili kişilere bildirilir (AC-14.5). Bildirim isteği outbox ile yazılır; görev bildirimi `HIGH` önceliklidir ve alarm kuyruğunu etkilemez (ADR-004). Panel SSE ile güncellenir. Gönüllü kabul ettiği görevi bırakamaz; KOR atamayı kaldırabilir (AC-14.4).
