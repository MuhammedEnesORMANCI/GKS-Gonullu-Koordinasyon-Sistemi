# Deployment Diagram

> Kaynak: ADR-008, VS-04, VS-11, VS-12, TD-15, US-29 (TLS ≥ 1.2, sır yönetimi), NFR-01, NFR-11. Demo ortamı: **Docker Compose ile tek makine veya tek sanal sunucu** (VS-11; makinenin kendisi gerçek bilgi gerektirir). Depo ve CI için GitHub varsayılmıştır (ADR-008).

```mermaid
flowchart LR
  subgraph USERS["Kullanıcı cihazları"]
    BR["Tarayıcı<br/>Chrome / Edge / Firefox<br/>(ADM, KOR, TEK, UYM)"]
    PH["Android cihaz<br/>minSdk 26<br/>(GON, LDR)"]
  end

  subgraph HOST["Demo ana makinesi — Docker Compose"]
    subgraph NET["İç ağ"]
      CADDY["caddy<br/>ters vekil · TLS ≥ 1.2<br/>:443 dışa açık"]
      WEBC["web<br/>nginx · statik React derlemesi<br/>+ statik /davet sayfası (TD-02)"]
      APP["backend<br/>Spring Boot · Java 21<br/>:8080 yalnızca iç ağ"]
      PG[("postgres<br/>PostgreSQL 16<br/>:5432 yalnızca iç ağ")]
      BK["backup<br/>zamanlanmış pg_dump<br/>(günlük)"]
      MAIL["mailpit<br/>SMTP :1025 iç ağ<br/>arayüz :8025 yalnızca ekip"]
    end
    V1[("pgdata birimi")]
    V2[("backups birimi")]
    SEC["Sırlar (.env, depoda değil)<br/>DB parolası · JWT anahtarı ·<br/>FCM hizmet hesabı dosyası"]
  end

  subgraph EXT["Harici servisler"]
    FCM["Firebase Cloud Messaging"]
    OSM["OSM döşeme sunucuları"]
    FAD["Firebase App Distribution<br/>(isteğe bağlı APK dağıtımı)"]
  end

  subgraph CI["Geliştirme (varsayım)"]
    GHA["GitHub Actions<br/>derleme · test · modül sınırı ·<br/>OpenAPI kontrolü · imajlar · APK"]
  end

  BR -- "HTTPS + SSE" --> CADDY
  PH -- "HTTPS REST" --> CADDY
  CADDY -- "/" --> WEBC
  CADDY -- "/api, /api/v1/stream" --> APP
  APP --> PG
  PG --- V1
  BK -- "pg_dump" --> PG
  BK --- V2
  APP -- "SMTP" --> MAIL
  SEC -. "ortam değişkeni / dosya" .-> APP
  APP -- "HTTPS gönderim" --> FCM
  FCM -- "push" --> PH
  BR -. "döşeme" .-> OSM
  PH -. "döşeme" .-> OSM
  GHA -. "imaj ve APK" .-> HOST
  GHA -. "APK" .-> FAD
  FAD -. "test kurulumu" .-> PH
```

**Açıklama:** Tüm sunucu bileşenleri tek bir Docker Compose projesinde çalışır. Dışa yalnızca Caddy (443) açılır; backend, PostgreSQL ve Mailpit SMTP portu iç ağdadır. Mailpit arayüzü yalnızca ekip erişimine açıktır. Caddy TLS'i sonlandırır, statik web uygulamasını ve API/SSE trafiğini yönlendirir; SSE için vekil zaman aşımı TV-11'de doğrulanır. Veritabanı kalıcı birimde tutulur ve günlük `pg_dump` yedeği ayrı bir birime alınır (RPO 24 sa, RTO 4 sa, VS-04, TD-15). Sırlar depoda tutulmaz (NFR-01). Android uygulaması Google Play'de yayınlanmaz; imzalı APK veya Firebase App Distribution ile dağıtılır (VS-12). Harici servisler FCM ve OSM'dir; harici servise kişisel veri yalnızca FCM üzerinden kısa bildirim metni olarak gider ve veriler sentetiktir (K-04).
