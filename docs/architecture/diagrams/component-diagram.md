# Component Diagram

> Kaynak: ADR-001 – ADR-012, `../design/system-architecture.md` §2–3. Yalnızca MVP bileşenleri gösterilir. R2+ modüller (messaging, files, calls) **gösterilmez.**

```mermaid
flowchart TB
  subgraph AND["Android uygulaması (Kotlin, Jetpack Compose)"]
    direction TB
    A_UI["Ekranlar AND-01..12<br/>(Compose, Material 3)"]
    A_VM["ViewModel katmanı"]
    A_REPO["Depo katmanı<br/>(bellek içi önbellek)"]
    A_API["API istemcisi<br/>Retrofit + OkHttp"]
    A_SEC["Güvenli depo<br/>(Keystore)"]
    A_FCM["FCM mesaj servisi"]
    A_ALARM["Tam ekran alarm ekranı"]
    A_LOC["Konum ön plan servisi<br/>(Fused Location)"]
    A_WM["WorkManager<br/>(alarm yanıtı tekrarı)"]
    A_MAP["Harita<br/>MapLibre + OSM"]
    A_UI --> A_VM --> A_REPO --> A_API
    A_API --> A_SEC
    A_FCM --> A_ALARM
    A_FCM --> A_REPO
    A_ALARM --> A_WM --> A_API
    A_LOC --> A_API
    A_UI --> A_MAP
  end

  subgraph WEB["Web yönetim paneli (TypeScript, React, Vite, MUI)"]
    direction TB
    W_UI["Sayfalar WEB-01..12"]
    W_Q["TanStack Query önbelleği"]
    W_API["OpenAPI'den üretilen istemci"]
    W_SSE["SSE istemcisi<br/>(EventSource)"]
    W_MAP["Harita<br/>Leaflet + OSM"]
    W_UI --> W_Q --> W_API
    W_SSE -- "sorguyu geçersiz kıl" --> W_Q
    W_UI --> W_MAP
  end

  subgraph BE["Backend (Java 21, Spring Boot, Spring Modulith)"]
    direction TB
    B_REST["REST API katmanı<br/>/api/v1 · OpenAPI"]
    B_SSE["SSE uç noktası"]
    subgraph MODS["Uygulama modülleri"]
      M_ID["identity"]
      M_AC["access"]
      M_ORG["organization"]
      M_GR["groups"]
      M_TK["tasks"]
      M_LOC["location"]
      M_AL["alerts"]
      M_NT["notifications"]
      M_RT["realtime"]
      M_AU["audit"]
    end
    B_OUT["Olay yayın kaydı<br/>(outbox)"]
    B_WK["İşçi havuzları<br/>CRITICAL · HIGH/NORMAL · zamanlayıcı"]
    B_REST --> M_ID & M_AC & M_ORG & M_GR & M_TK & M_LOC & M_AL & M_NT & M_AU
    B_SSE --> M_RT
    MODS --> B_OUT --> B_WK
    B_WK --> M_NT
  end

  DB[("PostgreSQL 16<br/>modül başına şema")]
  FCM["Firebase Cloud Messaging"]
  OSM["OSM döşeme sunucuları"]
  SMTP["Mailpit (SMTP)"]

  A_API -- "HTTPS · REST · Bearer" --> B_REST
  W_API -- "HTTPS · REST · çerez + CSRF" --> B_REST
  W_SSE -- "HTTPS · SSE" --> B_SSE
  MODS --> DB
  B_OUT --> DB
  M_NT -- "Firebase Admin SDK" --> FCM
  FCM -- "push" --> A_FCM
  M_ID -- "SMTP" --> SMTP
  A_MAP -. "HTTPS döşeme" .-> OSM
  W_MAP -. "HTTPS döşeme" .-> OSM
```

**Açıklama:** Android ve web istemcileri backend'e yalnızca OpenAPI sözleşmesindeki REST uçlarıyla bağlanır (ADR-010). Web anlık güncellemeleri SSE ile alır (ADR-009). Android kalıcı bağlantı tutmaz; anlık bilgiyi FCM ile alır ve uygulama açılışında REST ile tazeler (ADR-005). Backend tek bir dağıtılabilir birimdir; modüller Spring Modulith uygulama modülleridir ve birbirleriyle açık arayüzler ve outbox ile kalıcılaştırılan olaylar üzerinden konuşur (ADR-001, ADR-004). Alarm gönderimi `CRITICAL` işçi havuzunda diğer trafikten ayrı işlenir. Harita döşemeleri istemciler tarafından doğrudan OSM'den alınır; konum verisi harita servisine gönderilmez. Android'de kalıcı yerel veritabanı yoktur; yalnızca alarm yanıtı WorkManager işi olarak saklanır (K-24).
