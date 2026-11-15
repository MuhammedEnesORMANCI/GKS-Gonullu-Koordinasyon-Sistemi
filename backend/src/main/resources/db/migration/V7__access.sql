-- access modülü: kapsamlı rol atamaları (database-physical-design.md §4.1; K-07, TD-01).
-- GON/OPERATION = operasyona katılım. Kurum düzeyinde GON yoktur (LD-23).

CREATE TABLE access.role_assignments (
    id            uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    role          text        NOT NULL,
    scope_type    text        NOT NULL,
    scope_id      uuid        NOT NULL,
    granted_by    uuid,
    granted_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    revoked_by    uuid,
    revoke_reason text,
    CONSTRAINT pk_role_assignments PRIMARY KEY (id),
    CONSTRAINT fk_role_assignments_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_assignments_granted_by FOREIGN KEY (granted_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_assignments_revoked_by FOREIGN KEY (revoked_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_role_assignments_role CHECK (role IN ('ADM', 'KOR', 'GON', 'TEK', 'UYM')),
    CONSTRAINT ck_role_assignments_scope_type CHECK (scope_type IN ('ORGANIZATION', 'OPERATION')),
    CONSTRAINT ck_role_assignments_role_scope CHECK (
        (role IN ('ADM', 'TEK', 'UYM') AND scope_type = 'ORGANIZATION')
        OR (role IN ('KOR', 'GON') AND scope_type = 'OPERATION')),
    CONSTRAINT ck_role_assignments_revoked CHECK (revoked_at IS NOT NULL OR (revoked_by IS NULL AND revoke_reason IS NULL)),
    CONSTRAINT ck_role_assignments_revoke_reason CHECK (revoke_reason IS NULL OR revoke_reason IN (
        'MANUAL', 'OPERATION_CLOSED', 'ACCOUNT_CLOSED', 'INVITATION_DECLINED'))
);

CREATE UNIQUE INDEX ux_role_assignments_active ON access.role_assignments (user_id, role, scope_type, scope_id)
    WHERE revoked_at IS NULL;
CREATE INDEX ix_role_assignments_user_active ON access.role_assignments (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_role_assignments_scope_active ON access.role_assignments (scope_type, scope_id, role)
    WHERE revoked_at IS NULL;
