-- V15 (ADR-020, proje sahibi kararları 16.11.2026):
--  1. Uygulama kısıtlı bir veritabanı kullanıcısıyla bağlanır; Flyway tablo sahibiyle çalışır. Bu migration rolü
--     (yoksa, parolasız NOLOGIN) oluşturur ve yetkileri verir. Parola ve LOGIN yetkisi depoya girmez:
--     scripts/db/initdb/10-gks-app-user.sh (yeni kurulum) veya aynı betiğin elle çalıştırılmasıyla (mevcut kurulum)
--     verilir. Rol adı Flyway yer tutucusudur: spring.flyway.placeholders.appUser (GKS_DB_APP_USER, varsayılan gks_app).
--  2. Denetim tablolarında uygulama yalnızca SELECT/INSERT yapabilir (UPDATE/DELETE/TRUNCATE yetkisi yok; V12
--     tetikleyicileri ayrıca korur). Tablo sahibi olmadığı için tetikleyiciyi de kaldıramaz.
--  3. Kimlik olayları için audit_entries'e ip kolonu; tanınmayan hesapla başarısız girişte aktör boş olabilir.
--  4. Konum görüntüleme artık her çağrıda tek LOCATION_VIEWED kaydıdır; LD-05 pencere tablosu kaldırılır. Tablodaki
--     açık pencereler kaybolmasın diye önce denetime yazılır.

-- ---- 3. denetim: ip ve aktörsüz başarısız giriş
ALTER TABLE audit.audit_entries ADD COLUMN ip inet;
ALTER TABLE audit.audit_entries ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE audit.audit_entries ADD CONSTRAINT ck_audit_entries_actor_required
    CHECK (actor_id IS NOT NULL OR action = 'LOGIN_FAILED');

-- ---- 4. LD-05 penceresi: açık pencereleri yaz, tabloyu kaldır
INSERT INTO audit.audit_entries (id, actor_id, actor_basis, action, resource_type, operation_id, client, at, details)
SELECT gen_random_uuid(), actor_id, 'KOR', 'LOCATION_VIEWED', 'LOCATION', operation_id, client, window_start,
       jsonb_strip_nulls(jsonb_build_object(
           'queryCount', query_count,
           'windowStart', window_start,
           'windowEnd', window_start + interval '5 minutes',
           'lastViewedAt', last_viewed_at,
           'filtersUsed', CASE WHEN filters_used IS NULL THEN NULL ELSE to_jsonb(string_to_array(filters_used, ',')) END,
           'migratedFromWindow', true))
  FROM audit.location_view_windows;
DROP TABLE audit.location_view_windows;

-- ---- 1–2. uygulama rolü ve yetkileri
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appUser}') THEN
        EXECUTE format('CREATE ROLE %I NOLOGIN', '${appUser}');
    END IF;
END
$$;

GRANT USAGE ON SCHEMA identity, access, organization, groups, tasks, location, alerts, notifications, audit, platform
    TO "${appUser}";

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES
    IN SCHEMA identity, access, organization, groups, tasks, location, alerts, notifications TO "${appUser}";
GRANT USAGE, SELECT ON ALL SEQUENCES
    IN SCHEMA identity, access, organization, groups, tasks, location, alerts, notifications TO "${appUser}";
GRANT SELECT, INSERT, UPDATE, DELETE ON platform.idempotency_keys TO "${appUser}";

-- Denetim: yalnızca okuma ve ekleme.
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA audit TO "${appUser}";

-- Sonraki migration'ların (tablo sahibi = Flyway kullanıcısı) oluşturacağı tablolar için aynı kural.
ALTER DEFAULT PRIVILEGES IN SCHEMA identity, access, organization, groups, tasks, location, alerts, notifications
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${appUser}";
ALTER DEFAULT PRIVILEGES IN SCHEMA identity, access, organization, groups, tasks, location, alerts, notifications
    GRANT USAGE, SELECT ON SEQUENCES TO "${appUser}";
ALTER DEFAULT PRIVILEGES IN SCHEMA audit GRANT SELECT, INSERT ON TABLES TO "${appUser}";
