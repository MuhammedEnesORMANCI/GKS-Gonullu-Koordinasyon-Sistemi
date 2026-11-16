-- Denetim modülü (K-26, NFR-05; database-physical-design.md §12.5; backend-api-design.md §12.5; ADR-016).
-- Kayıtlar iş işlemiyle aynı işlemde eşzamanlı yazılır; yalnızca eklenebilir: UPDATE, DELETE ve TRUNCATE
-- tetikleyiciyle reddedilir (uygulamada da güncelleme/silme yolu yoktur).

CREATE TABLE audit.audit_entries (
    id            uuid        NOT NULL,
    actor_id      uuid,
    actor_basis   text        NOT NULL,
    action        text        NOT NULL,
    resource_type text        NOT NULL,
    resource_id   uuid,
    operation_id  uuid,
    client        text        NOT NULL,
    trace_id      text,
    at            timestamptz NOT NULL,
    details       jsonb,
    CONSTRAINT pk_audit_entries PRIMARY KEY (id),
    CONSTRAINT fk_audit_entries_actor FOREIGN KEY (actor_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_entries_action CHECK (action ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT ck_audit_entries_resource_type CHECK (resource_type IN ('ALERT', 'ALERT_RECIPIENT', 'TASK',
        'TASK_ASSIGNMENT', 'GROUP', 'GROUP_MEMBERSHIP', 'OPERATION', 'ROLE_ASSIGNMENT', 'USER', 'SESSION', 'INVITATION',
        'LOCATION', 'NOTIFICATION', 'MEETING_POINT')),
    CONSTRAINT ck_audit_entries_client CHECK (client IN ('WEB', 'ANDROID', 'SYSTEM')),
    -- ADR-015: ADM'nin KOR ataması olmadan yaptığı operasyon işlemi 'ADM', atamayla yapılan 'KOR'.
    CONSTRAINT ck_audit_entries_actor_basis CHECK (actor_basis IN ('KOR', 'ADM', 'TEK', 'UYM', 'LEADER', 'SELF', 'SYSTEM')),
    CONSTRAINT ck_audit_entries_details CHECK (details IS NULL OR jsonb_typeof(details) = 'object')
);

-- Kimlikler UUIDv7 ve kayıt eşzamanlı yazıldığından id sırası olay sırasıdır; liste id azalan.
CREATE INDEX ix_audit_entries_actor ON audit.audit_entries (actor_id, id DESC);
CREATE INDEX ix_audit_entries_action ON audit.audit_entries (action, id DESC);
CREATE INDEX ix_audit_entries_resource ON audit.audit_entries (resource_type, resource_id, id DESC);
CREATE INDEX ix_audit_entries_operation ON audit.audit_entries (operation_id, id DESC) WHERE operation_id IS NOT NULL;
CREATE INDEX ix_audit_entries_basis ON audit.audit_entries (actor_basis, id DESC);

CREATE FUNCTION audit.audit_entries_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_entries yalnızca eklenebilir (K-26): % reddedildi', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER tg_audit_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON audit.audit_entries
    FOR EACH ROW EXECUTE FUNCTION audit.audit_entries_append_only();

CREATE TRIGGER tg_audit_entries_no_truncate
    BEFORE TRUNCATE ON audit.audit_entries
    FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_entries_append_only();
