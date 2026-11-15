# Sequence Diagram — Konum paylaşımı

> Kaynak: K-14, K-15, K-16, BR-08, BR-09, US-17 – US-19, AC-08.3, ADR-011, VS-18, TD-11, TD-18. Arka planda kesintisiz çalışma garanti edilmez (TV-01). Uç nokta adları taslaktır.

```mermaid
sequenceDiagram
  autonumber
  actor G as Gönüllü
  participant A as Android uygulaması
  participant S as Konum ön plan servisi
  participant L as location modülü
  participant O as organization modülü
  participant RT as realtime (SSE)
  participant W as Web paneli
  actor K as KOR
  participant AU as audit modülü
  participant F as FCM

  G->>A: AND-07: Paylaşımı başlat
  A->>A: Konum izni (kullanım sırasında) kontrol / iste
  A->>L: POST /location-sharing (operasyon)
  L->>O: Operasyon ACTIVE mi, kişi katılımcı mı?
  alt Hayır
    L-->>A: 409 Başlatılamaz
  else Evet
    L-->>A: 200 ACTIVE
    A->>S: Ön plan servisini başlat (kalıcı bildirim)
    loop Her 60 sn (VS-18)
      S->>S: Konum ölç (enlem, boylam, doğruluk, ölçüm zamanı)
      alt Ağ yok
        S->>S: Göstergeyi güncelle, ölçümü at (TD-11)
      else Ağ var
        S->>L: POST /locations
        L->>L: Paylaşım ve operasyon aktif mi?
        L->>L: Son konumu üzerine yaz (geçmiş yok, K-16), saat farkı > 2 dk ise işaretle
        L->>RT: LocationUpdated (operation konusu)
        L-->>S: 204
      end
    end
  end

  K->>W: WEB-08 Harita
  W->>L: GET /operations/{id}/locations
  L->>L: can(K, location.view, operasyon)
  L->>AU: LOCATION_VIEWED (kullanıcı, operasyon, zaman)
  L-->>W: Son konumlar + ölçüm zamanları
  RT-->>W: SSE güncellemeleri
  W-->>K: Güncellik etiketleri (2 dk güncel değil, 15 dk son bilinen)

  alt Gönüllü durdurur
    G->>S: Bildirimden veya AND-07'den Durdur
    S->>L: DELETE /location-sharing
    L->>L: Paylaşım STOPPED
  else Operasyon kapanır (BR-09)
    O->>L: OperationStateChanged (COMPLETED)
    L->>L: Tüm paylaşımlar STOPPED
    L->>F: Yüksek öncelikli konumu durdur mesajı (TD-18)
    F-->>A: Mesaj
    A->>S: Servisi durdur
    opt Mesaj ulaşmadı
      S->>L: POST /locations
      L-->>S: 409 Paylaşım durduruldu
      S->>S: Servisi durdur
    end
  end
  L->>RT: LocationSharingChanged
```

**Açıklama:** Paylaşımı kullanıcı başlatır ve durdurur (K-14). Paylaşım yalnızca aktif operasyonda ve operasyon katılımcısı için mümkündür. Konum, kalıcı bildirimli ön plan servisiyle 60 sn'de bir gönderilir; sunucu yalnızca son konumu tutar (K-16). Konumu yalnızca aktif operasyonun KOR'u görür ve her görüntüleme denetime yazılır (K-15, BR-08). Operasyon kapanınca paylaşımlar sunucu tarafında hemen durur. Cihaz durdurma mesajını alamazsa bir sonraki ölçüm reddedilir ve servis kendini durdurur; hedef ≤ 1 dk (AC-08.3).
