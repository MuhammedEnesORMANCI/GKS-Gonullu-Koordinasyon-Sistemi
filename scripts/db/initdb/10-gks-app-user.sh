#!/bin/sh
# ADR-020: uygulamanın kısıtlı veritabanı kullanıcısını oluşturur veya parolasını günceller (idempotent).
# Yetkiler (SELECT/INSERT/UPDATE/DELETE; denetim tablolarında yalnızca SELECT/INSERT) Flyway V15'tedir.
#  - Yeni kurulum: postgres imajı boş veri dizininde bu betiği kendiliğinden çalıştırır.
#  - Mevcut kurulum: docker compose exec postgres sh /docker-entrypoint-initdb.d/10-gks-app-user.sh
# Parola ortamdan gelir (GKS_DB_APP_PASSWORD, compose.yaml → .env); betikte ve günlükte yer almaz.
set -eu
APP_USER="${GKS_DB_APP_USER:-gks_app}"
if [ -z "${GKS_DB_APP_PASSWORD:-}" ]; then
  echo "GKS_DB_APP_PASSWORD tanımlı değil (.env)" >&2
  exit 1
fi
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_user="$APP_USER" -v app_password="$GKS_DB_APP_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I', :'app_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L',
              :'app_user', :'app_password') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
SQL
echo "Uygulama kullanıcısı hazır: $APP_USER"
