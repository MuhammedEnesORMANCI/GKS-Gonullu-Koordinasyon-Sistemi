# Veritabanı kullanıcıları (ADR-020)

Uygulama veritabanına **kısıtlı bir kullanıcıyla** bağlanır; şemayı yalnızca Flyway **tablo sahibiyle** değiştirir.

| Kullanıcı | Nereden | Ne yapar |
|---|---|---|
| Tablo sahibi (`POSTGRES_USER`) | `.env` → compose → `spring.flyway.user` | Flyway migration'ları. Tabloların, tetikleyicilerin sahibi |
| Uygulama (`GKS_DB_APP_USER`, varsayılan `gks_app`) | `.env` → `spring.datasource.username` | İş tablolarında `SELECT/INSERT/UPDATE/DELETE`; **denetim tablolarında yalnızca `SELECT/INSERT`**. `TRUNCATE`, tetikleyici kapatma/silme, tablo değiştirme yok |

- Rol ve yetkiler: `backend/src/main/resources/db/migration/V15__app_role_and_identity_audit.sql` (rol yoksa parolasız `NOLOGIN` oluşturur, yetkileri verir; sonraki migration'lar için `ALTER DEFAULT PRIVILEGES`).
- Parola ve giriş yetkisi: `scripts/db/initdb/10-gks-app-user.sh` (idempotent). Parola yalnızca ortamdan (`GKS_DB_APP_PASSWORD`) gelir; depoya ve migration'a girmez.
- Yerel çalıştırma: `bootRun` varsayılan olarak `local` profilini açar (`build.gradle.kts`); bağlantı `application.yaml` içindeki `local` bölümündedir. `compose.yaml`'daki postgres servisi Spring Boot Docker Compose bağlantısından hariç tutulur (`org.springframework.boot.ignore`), aksi halde Spring tablo sahibiyle bağlanırdı.
- Testler (Testcontainers) değişmedi: tek süper kullanıcıyla çalışır; kısıtlı rolün yetkileri `AuditApiTests.applicationRoleCanOnlyReadAndInsertAuditEntries` içinde `SET ROLE gks_app` ile doğrulanır.

## Yeni `.env` anahtarları

```
GKS_DB_APP_USER=gks_app
GKS_DB_APP_PASSWORD=<POSTGRES_PASSWORD'den farklı, güçlü bir parola>
```

İsteğe bağlı: `GKS_DB_HOST` (varsayılan `localhost`), `GKS_DB_PORT` (varsayılan `5432`).

## Mevcut kurulumu güncelleme (veri silinmez)

> `docker compose down -v` **çalıştırmayın**; `-v` veri hacmini siler.

1. Backend'i durdurun (bootRun penceresinde Ctrl+C).
2. `.env` dosyasına yukarıdaki iki anahtarı ekleyin.
3. Kök dizinde: `docker compose up -d postgres` — ortam değişkenleri ve bağlamalar değiştiği için kapsayıcı yeniden oluşturulur; `pgdata` hacmi korunur.
4. Uygulama kullanıcısını oluşturun / parolasını verin:
   `docker compose exec postgres sh /docker-entrypoint-initdb.d/10-gks-app-user.sh`
   Beklenen çıktı: `Uygulama kullanıcısı hazır: gks_app`.
5. `cd backend && ./gradlew bootRun` — Flyway tablo sahibiyle V15'i uygular (yetkiler, `ip` kolonu), ardından uygulama `gks_app` ile bağlanır.
6. Doğrulama (isteğe bağlı):
   - `docker compose exec postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB> -c "\dp audit.audit_entries"` → `gks_app=ar/...` (a = INSERT, r = SELECT).
   - `docker compose exec postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB> -c "set role gks_app; delete from audit.audit_entries"` → `permission denied`.

Sıra önemli: 4. adım 5'ten önce yapılmazsa backend açılırken `gks_app` için parola doğrulaması başarısız olur (Flyway yine de uygulanmış olabilir; 4. adımı yapıp backend'i yeniden başlatmak yeterlidir).

## Yeni kurulum

`.env` hazırlanır, `docker compose up -d` çalışır: postgres imajı boş veri dizininde `scripts/db/initdb/` altındaki betiği kendisi çalıştırır. Sonra `bootRun`.

## Üretim notu

Üretimde tablo sahibi süper kullanıcı olmamalı. Tablo sahibinin `CREATE ROLE` yetkisi yoksa uygulama rolünü önceden (DBA) oluşturun; rol varsa V15'teki oluşturma bloğu atlanır ve yalnızca yetkiler verilir. Ters vekil arkasında gerçek istemci IP'si için `server.forward-headers-strategy=native` (veya `framework`) ayarlanmalıdır.
