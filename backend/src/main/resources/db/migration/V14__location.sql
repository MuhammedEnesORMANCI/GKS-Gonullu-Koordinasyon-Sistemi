-- location modülü: paylaşım durumu ve son konum (database-physical-design.md §6.1 – §6.2) ve LD-05 konum
-- görüntüleme penceresi (§6.7). Geçmiş tutulmaz (K-16). Saklama (ADR-019, proje sahibi kararı 16.11.2026):
-- LD-25'teki "kapanışta sil" yerine son konumlar operasyon kapandıktan sonra yapılandırılabilir süre
-- (varsayılan 30 gün) saklanır; retain_until kapanışta dolar, günlük iş süresi geçenleri siler.

CREATE TABLE location.location_sharings (
    user_id             uuid        NOT NULL,
    operation_id        uuid        NOT NULL,
    state               text        NOT NULL DEFAULT 'OFF',
    started_at          timestamptz,
    stopped_at          timestamptz,
    availability_reason text,
    stop_reason         text,
    last_sent_at        timestamptz,
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_location_sharings PRIMARY KEY (user_id, operation_id),
    CONSTRAINT fk_location_sharings_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_sharings_operation FOREIGN KEY (operation_id)
        REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_location_sharings_state CHECK (state IN ('OFF', 'ACTIVE', 'UNAVAILABLE', 'STOPPED')),
    CONSTRAINT ck_location_sharings_stop_reason CHECK (stop_reason IN ('USER', 'OPERATION_CLOSED', 'OPERATION_SUSPENDED',
        'PARTICIPATION_REVOKED', 'SESSION_ENDED', 'ACCOUNT_DISABLED')),
    CONSTRAINT ck_location_sharings_availability_reason CHECK (availability_reason IN ('PERMISSION_DENIED', 'GPS_OFF', 'NO_FIX')),
    CONSTRAINT ck_location_sharings_started CHECK (state <> 'ACTIVE' OR started_at IS NOT NULL),
    CONSTRAINT ck_location_sharings_availability CHECK ((state = 'UNAVAILABLE') = (availability_reason IS NOT NULL))
);

CREATE INDEX ix_location_sharings_operation_active ON location.location_sharings (operation_id) WHERE state = 'ACTIVE';

CREATE TABLE location.last_locations (
    user_id         uuid             NOT NULL,
    operation_id    uuid             NOT NULL,
    lat             double precision NOT NULL,
    lon             double precision NOT NULL,
    accuracy        double precision,
    measured_at     timestamptz      NOT NULL,
    received_at     timestamptz      NOT NULL DEFAULT now(),
    clock_skew_flag boolean          NOT NULL DEFAULT false,
    retain_until    timestamptz,
    CONSTRAINT pk_last_locations PRIMARY KEY (user_id, operation_id),
    CONSTRAINT fk_last_locations_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_last_locations_operation FOREIGN KEY (operation_id)
        REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_last_locations_coords CHECK (lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180),
    CONSTRAINT ck_last_locations_accuracy CHECK (accuracy IS NULL OR accuracy >= 0)
) WITH (fillfactor = 70, autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 1000);

CREATE INDEX ix_last_locations_operation ON location.last_locations (operation_id);
CREATE INDEX ix_last_locations_retain ON location.last_locations (retain_until) WHERE retain_until IS NOT NULL;

-- LD-05: 5 dk'lık pencerede tek LOCATION_VIEWED (audit modülünün ara tablosu; audit_entries yalnızca eklenebilir kalır).
CREATE TABLE audit.location_view_windows (
    actor_id       uuid        NOT NULL,
    operation_id   uuid        NOT NULL,
    window_start   timestamptz NOT NULL,
    last_viewed_at timestamptz NOT NULL,
    query_count    integer     NOT NULL DEFAULT 1,
    client         text        NOT NULL DEFAULT 'WEB',
    filters_used   text,
    CONSTRAINT pk_location_view_windows PRIMARY KEY (actor_id, operation_id),
    CONSTRAINT fk_location_view_windows_actor FOREIGN KEY (actor_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_location_view_windows_operation FOREIGN KEY (operation_id)
        REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_location_view_windows_count CHECK (query_count >= 1),
    CONSTRAINT ck_location_view_windows_client CHECK (client IN ('WEB', 'ANDROID'))
);

CREATE INDEX ix_location_view_windows_start ON audit.location_view_windows (window_start);
