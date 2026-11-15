# Sequence Diagram — Acil alarm ve bildirim süreci

> Kaynak: K-09, K-13, K-17, K-19, K-24, BR-04, BR-06, US-20 – US-25, ADR-004, ADR-009, TD-03, TD-05 – TD-10. Ayrıntılar ve hata durumları: `../design/emergency-alert-design.md`. Uç nokta adları taslaktır.

```mermaid
sequenceDiagram
  autonumber
  actor K as KOR
  participant W as Web paneli
  participant AL as alerts modülü
  participant AC as access modülü
  participant N as notifications (CRITICAL havuzu)
  participant F as FCM
  participant A as Android uygulaması
  actor G as Gönüllü
  participant RT as realtime (SSE)
  participant AU as audit modülü

  K->>W: WEB-09: mesaj, eylem, hedef
  W->>AL: POST /alerts (taslak)
  AL->>AC: MFA tamam mı, can(K, alert.send, operasyon)
  AL->>AL: Operasyon ACTIVE mi, hedefler kapsamda mı
  AL->>AL: Hedef kitleyi çöz (gönderen hariç, ≤ 500)
  AL-->>W: DRAFT + önizleme (sayı, liste, sürüm)
  W-->>K: Onay penceresi: BU BİR ACİL ALARMDIR
  K->>W: Onayla
  W->>AL: POST /alerts/{id}/send (Idempotency-Key, sürüm)
  alt Hedef kitle değişmiş
    AL-->>W: 409 Yeni önizleme gerekli
  else Aynı anahtar daha önce işlenmiş
    AL-->>W: 200 Önceki sonuç (çift alarm yok)
  else Geçerli
    AL->>AL: OPEN + ALERT_RECIPIENT QUEUED (tek işlem)
    AL->>N: NotificationRequested (ALERT, CRITICAL) [outbox]
    AL->>AU: ALERT_SENT
    AL-->>W: 202 Gönderiliyor
  end

  loop Her alıcı cihazı
    N->>F: Yüksek öncelikli data mesajı (collapse_key alert:{id}, TTL 60 dk)
    alt Kabul
      F-->>N: message_id
      N->>AL: DeliveryStatusChanged (SENT)
    else Geçici hata
      N->>N: Üstel bekleme ile 3 deneme
    else Kalıcı hata / geçersiz token
      N->>N: Cihazı pasifleştir
      N->>AL: Alıcının cihazı kalmadıysa FAILED
    end
  end
  AL->>RT: RecipientStateChanged
  RT-->>W: SSE alert konusu (≤ 5 sn)

  F-->>A: Push
  A->>AL: GET /alerts/{id} (durum doğrulama, TD-10)
  A->>AL: POST /recipients/{id}/delivered
  alt Uygulama ön planda
    A-->>G: Tam ekran alarm (AND-08)
  else Arka planda / kilitli
    A-->>G: alarm kanalında yüksek önemli bildirim, dokununca tam ekran
  end
  A->>AL: POST /recipients/{id}/seen

  alt Gönüllü yanıtlar
    G->>A: Aldım / Yardım gerekiyor / Göreve gidiyorum
    A->>AL: POST /recipients/{id}/responses (Idempotency-Key)
    AL->>AL: RESPONDED, hatırlatmaları iptal et
    AL->>AU: ALERT_RESPONDED
    AL->>RT: RecipientStateChanged
    opt Ağ yok
      A->>A: WorkManager benzersiz işi, bağlantı gelince yeniden gönder
    end
  else Yanıt yok
    loop En fazla 5 kez, 2 dk aralıkla (K-13)
      AL->>N: Hatırlatma (aynı collapse_key)
      N->>F: Mesaj
    end
    AL->>AL: UNANSWERED
    AL->>RT: RecipientStateChanged
  end

  opt KOR iptal eder (US-25)
    K->>W: İptal et + onay
    W->>AL: POST /alerts/{id}/cancel
    AL->>AL: CANCELLED, hatırlatmaları durdur
    AL->>N: İptal bildirimi tüm alıcılara (CRITICAL)
    N->>F: Mesaj (aynı bildirim kimliği)
    F-->>A: ALARM İPTAL EDİLDİ
    AL->>AU: ALERT_CANCELLED
  end
```

**Açıklama:** Alarm iki adımda gönderilir: taslak ve önizleme, ardından onaylı gönderim (TD-05). `Idempotency-Key` yinelenen isteklerin ikinci alarm oluşturmasını önler (AC-20.5). Alarm kaydı, alıcı satırları ve bildirim isteği tek işlemde ve outbox ile yazılır; gönderim `CRITICAL` öncelikli ayrı işçi havuzunda yapılır (ADR-004). Panel her alıcının Gönderildi / Teslim edildi / Görüldü / Yanıt durumunu ayrı gösterir (BR-06). "Teslim edildi" ve "Görüldü" sinyalleri uygulamanın çalışıp ağa erişebilmesine bağlıdır; gelmezse "bilinmiyor" görünür. Hatırlatmalar sunucudan gönderilir; cihazda yerel alarm zamanlayıcısı kullanılmaz. Otomatik eskalasyon R2'dedir ve gösterilmez.
