-- Her uygulama modülü kendi şemasına sahiptir (TD-20, ADR-001).
-- Bu sürüm yalnızca şemaları oluşturur; tablolar ilgili modüllerle birlikte eklenir.
-- platform şeması (Flyway geçmişi, ileride outbox ve idempotency) Flyway tarafından oluşturulur.
CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS access;
CREATE SCHEMA IF NOT EXISTS organization;
CREATE SCHEMA IF NOT EXISTS groups;
CREATE SCHEMA IF NOT EXISTS tasks;
CREATE SCHEMA IF NOT EXISTS location;
CREATE SCHEMA IF NOT EXISTS alerts;
CREATE SCHEMA IF NOT EXISTS notifications;
CREATE SCHEMA IF NOT EXISTS audit;
