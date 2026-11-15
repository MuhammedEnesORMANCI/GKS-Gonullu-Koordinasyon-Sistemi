-- groups modülü: operasyon grubu ve üyelik (database-physical-design.md §5.1, §5.2; uzunluklar §1.4).

CREATE TABLE groups.groups (
    id              uuid             NOT NULL,
    organization_id uuid             NOT NULL,
    operation_id    uuid,
    name            text             NOT NULL,
    description     text,
    type            text             NOT NULL DEFAULT 'OPERATIONAL',
    join_policy     text             NOT NULL DEFAULT 'MANAGED',
    mp_address      text,
    mp_lat          double precision,
    mp_lon          double precision,
    mp_description  text,
    mp_time         timestamptz,
    mp_updated_by   uuid,
    mp_updated_at   timestamptz,
    created_by      uuid             NOT NULL,
    created_at      timestamptz      NOT NULL DEFAULT now(),
    updated_at      timestamptz      NOT NULL DEFAULT now(),
    archived_at     timestamptz,
    CONSTRAINT pk_groups PRIMARY KEY (id),
    CONSTRAINT fk_groups_organization FOREIGN KEY (organization_id)
        REFERENCES organization.organizations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_groups_operation FOREIGN KEY (operation_id)
        REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_groups_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_groups_mp_updated_by FOREIGN KEY (mp_updated_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_groups_type CHECK (type IN ('OPERATIONAL')),
    CONSTRAINT ck_groups_join_policy CHECK (join_policy IN ('MANAGED')),
    CONSTRAINT ck_groups_operational_has_operation CHECK (type <> 'OPERATIONAL' OR operation_id IS NOT NULL),
    CONSTRAINT ck_groups_mp_coords CHECK ((mp_lat IS NULL) = (mp_lon IS NULL)
        AND (mp_lat IS NULL OR mp_lat BETWEEN -90 AND 90)
        AND (mp_lon IS NULL OR mp_lon BETWEEN -180 AND 180)),
    CONSTRAINT ck_groups_mp_audit CHECK ((mp_updated_at IS NULL) = (mp_updated_by IS NULL)),
    CONSTRAINT ck_groups_lengths CHECK (
        char_length(name) BETWEEN 2 AND 80
        AND (description IS NULL OR char_length(description) <= 1000)
        AND (mp_address IS NULL OR char_length(mp_address) <= 300)
        AND (mp_description IS NULL OR char_length(mp_description) <= 500))
);

-- Aynı operasyonda arşivlenmemiş iki grubun adı büyük/küçük harf duyarsız aynı olamaz (§12.4 → 400 DUPLICATE).
CREATE UNIQUE INDEX ux_groups_operation_name ON groups.groups (operation_id, lower(name)) WHERE archived_at IS NULL;
CREATE INDEX ix_groups_operation ON groups.groups (operation_id) WHERE archived_at IS NULL;

CREATE TABLE groups.group_memberships (
    id         uuid        NOT NULL,
    group_id   uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL DEFAULT 'MEMBER',
    status     text        NOT NULL DEFAULT 'ACTIVE',
    added_by   uuid        NOT NULL,
    joined_at  timestamptz NOT NULL DEFAULT now(),
    ended_at   timestamptz,
    ended_by   uuid,
    end_reason text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_group_memberships PRIMARY KEY (id),
    CONSTRAINT fk_group_memberships_group FOREIGN KEY (group_id) REFERENCES groups.groups (id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_memberships_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_memberships_added_by FOREIGN KEY (added_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_group_memberships_ended_by FOREIGN KEY (ended_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_group_memberships_role CHECK (role IN ('LEADER', 'MEMBER')),
    CONSTRAINT ck_group_memberships_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT ck_group_memberships_end_reason CHECK (end_reason IS NULL OR end_reason IN (
        'REMOVED_BY_COORDINATOR', 'PARTICIPATION_REVOKED', 'ACCOUNT_CLOSED')),
    CONSTRAINT ck_group_memberships_ended CHECK ((status = 'ACTIVE') = (ended_at IS NULL))
);

-- Tek aktif üyelik ve en fazla bir aktif lider (K-32); "en az bir lider" uygulamada (DB E-5).
CREATE UNIQUE INDEX ux_group_memberships_active_member ON groups.group_memberships (group_id, user_id)
    WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX ux_group_memberships_active_leader ON groups.group_memberships (group_id)
    WHERE role = 'LEADER' AND status = 'ACTIVE';
CREATE INDEX ix_group_memberships_user_active ON groups.group_memberships (user_id) WHERE status = 'ACTIVE';
