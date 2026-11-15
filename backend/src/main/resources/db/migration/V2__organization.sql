-- organization modülü: kurum ve operasyon (database-physical-design.md §4.2, §4.3).
-- identity.users henüz yok; fk_operations_created_by ve fk_operations_closed_by
-- identity modülünün migration'ında eklenecek.

CREATE TABLE organization.organizations (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_organizations PRIMARY KEY (id),
    CONSTRAINT ck_organizations_name CHECK (char_length(name) BETWEEN 1 AND 120)
);

-- MVP'de tek kurum vardır (A-01); oluşturma API'si yoktur.
INSERT INTO organization.organizations (id, name)
VALUES ('01900000-0000-7000-8000-000000000001', 'Gönüllü Koordinasyon Kurumu');

CREATE TABLE organization.operations (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    name                   text        NOT NULL,
    description            text,
    state                  text        NOT NULL DEFAULT 'PLANNED',
    planned_end_at         timestamptz,
    activated_at           timestamptz,
    closed_at              timestamptz,
    closed_by              uuid,
    close_reminder_sent_at timestamptz,
    created_by             uuid        NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_operations PRIMARY KEY (id),
    CONSTRAINT fk_operations_organization FOREIGN KEY (organization_id)
        REFERENCES organization.organizations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operations_state
        CHECK (state IN ('PLANNED', 'ACTIVE', 'SUSPENDED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_operations_closed
        CHECK ((state IN ('COMPLETED', 'CANCELLED')) = (closed_at IS NOT NULL)),
    CONSTRAINT ck_operations_activated
        CHECK (state NOT IN ('ACTIVE', 'SUSPENDED', 'COMPLETED') OR activated_at IS NOT NULL),
    CONSTRAINT ck_operations_name CHECK (char_length(name) BETWEEN 3 AND 120),
    CONSTRAINT ck_operations_description CHECK (char_length(description) <= 2000)
);

CREATE INDEX ix_operations_org_state ON organization.operations (organization_id, state);

CREATE INDEX ix_operations_close_reminder ON organization.operations (planned_end_at)
    WHERE state = 'ACTIVE' AND close_reminder_sent_at IS NULL;
