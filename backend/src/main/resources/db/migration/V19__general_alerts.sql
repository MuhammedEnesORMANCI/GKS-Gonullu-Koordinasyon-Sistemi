-- ADR-026: Genel Acil Alarm. Aynı alarm tablosu/alıcı satırları; GENERAL kapsamlı alarmın operasyonu yoktur
-- (hedef: kurum geneli, seçili operasyonlar, gruplar, kişiler; target_expr içinde). İsteğe bağlı olay yeri (her iki kapsam).
ALTER TABLE alerts.alerts ALTER COLUMN operation_id DROP NOT NULL;

ALTER TABLE alerts.alerts
    ADD COLUMN scope     text             NOT NULL DEFAULT 'OPERATION',
    ADD COLUMN event_lat double precision,
    ADD COLUMN event_lon double precision;

ALTER TABLE alerts.alerts ADD CONSTRAINT ck_alerts_scope
    CHECK (scope IN ('OPERATION', 'GENERAL') AND ((scope = 'OPERATION') = (operation_id IS NOT NULL)));

ALTER TABLE alerts.alerts ADD CONSTRAINT ck_alerts_event_location CHECK (
    (event_lat IS NULL) = (event_lon IS NULL)
    AND (event_lat IS NULL OR event_lat BETWEEN -90 AND 90)
    AND (event_lon IS NULL OR event_lon BETWEEN -180 AND 180));

CREATE INDEX ix_alerts_general ON alerts.alerts (created_by, id DESC) WHERE scope = 'GENERAL';
