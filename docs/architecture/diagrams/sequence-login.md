# Sequence Diagram — Kullanıcı girişi ve yetkilendirme

> Kaynak: ADR-012, K-19, FR-22, US-03, US-06, US-07, NFR-02, ADR-002, TD-04 (belirteç süreleri: erişim 15 dk; yenileme web 12 sa, Android 30 gün), ADR-013. Uç nokta adları taslaktır (`../design/system-architecture.md` §7); uygulanmamıştır.

## Web paneli (ADM, KOR, TEK, UYM)

```mermaid
sequenceDiagram
  autonumber
  actor U as Yönetici / Koordinatör
  participant W as Web paneli
  participant ID as identity modülü
  participant AC as access modülü
  participant DB as PostgreSQL
  participant AU as audit modülü

  U->>W: E-posta + parola
  W->>ID: POST /auth/login
  ID->>DB: Kullanıcıyı ve kilit durumunu oku
  alt Parola hatalı
    ID->>DB: Hatalı deneme sayısını artır
    ID-->>W: 401 genel hata (hesap varlığı açık edilmez)
    Note over ID: 5 hatalı denemede 15 dk kilit (VS-14)
  else Parola doğru
    ID->>AC: Kullanıcının rolleri
    AC-->>ID: ADM ve/veya KOR rolü var mı
    alt ADM veya KOR rolü var (K-19)
      ID-->>W: mfa_required (kısa ömürlü MFA bileti)
      opt İlk giriş, TOTP kurulmamış
        W->>ID: TOTP kurulumu başlat
        ID-->>W: Gizli anahtar (QR)
      end
      U->>W: 6 haneli TOTP kodu
      W->>ID: POST /auth/mfa
      ID->>ID: TOTP doğrula
    end
    opt Yönetici parolayı sıfırlamış (AC-03.5)
      ID-->>W: Yeni parola belirleme zorunlu
    end
    ID->>DB: Yenileme belirteci kaydet (TD-04)
    ID-->>W: Erişim + yenileme belirteci (HttpOnly, Secure çerez) + CSRF
    ID->>AU: LOGIN_SUCCEEDED
  end

  Note over W,AC: Her korumalı istek
  W->>AC: GET /operations/{id} (çerez)
  AC->>AC: JWT doğrula, hesap aktif mi
  AC->>AC: MFA tamamlanmış mı (kurumsal izinler için)
  AC->>AC: can(user, action, resource) + kaynak kuralı
  alt İzin yok
    AC-->>W: 403
  else İzin var
    AC-->>W: 200 (kapsam filtresi uygulanmış veri)
  end

  Note over W,ID: Erişim belirtecinin süresi dolunca
  W->>ID: POST /auth/refresh
  alt Yenileme belirteci geçerli
    ID->>DB: Belirteci döndür (eskisini geçersiz kıl)
    ID-->>W: Yeni belirteçler
  else İptal edilmiş veya süresi dolmuş
    ID-->>W: 401 → giriş ekranı
  end
```

## Android (GON, LDR)

```mermaid
sequenceDiagram
  autonumber
  actor G as Gönüllü
  participant A as Android uygulaması
  participant ID as identity modülü
  participant AC as access modülü

  G->>A: E-posta + parola
  A->>ID: POST /auth/login
  ID-->>A: Erişim + yenileme belirteci
  Note over A,ID: Android oturumu MFA'sızdır ve yalnızca gönüllü/lider izinlerini kullanır. Kullanıcı KOR olsa bile kurumsal izinler bu oturumda reddedilir (authorization.md §5.4)
  A->>A: Yenileme belirtecini şifreli depoya yaz
  A->>ID: PUT /devices (FCM token, uygulama sürümü)
  A->>AC: GET /me (roller, operasyonlar, gruplar)
  AC-->>A: Kapsam bilgisi
  A-->>G: AND-03 Ana sayfa

  Note over ID: ADM oturumları iptal ederse (US-06)
  ID->>ID: Kullanıcının tüm yenileme belirteçlerini iptal et
  A->>ID: POST /auth/refresh
  ID-->>A: 401 → AND-01 Giriş
```

**Açıklama:** Kimlik doğrulama `identity`, yetki denetimi `access` modülündedir. ADM ve KOR rolü olan kullanıcılar web panelinde TOTP doğrulamadan kurumsal izinleri kullanamaz (K-19). Erişim belirteci kısa ömürlü, yenileme belirteci iptal edilebilir ve her kullanımda döndürülür. Oturum iptali en geç bir erişim belirteci ömrü içinde etkili olur; SSE bağlantıları hemen kapatılır. Her istekte yetki sunucuda denetlenir; liste sorgularında kapsam filtresi veritabanı sorgusuna eklenir (NFR-02). Kullanıcının kendi parolasını sıfırlaması Genişletilmiş halkadadır ve gösterilmez.
