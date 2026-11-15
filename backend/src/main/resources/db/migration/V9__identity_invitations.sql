-- Davetler (database-physical-design.md §3.2, TD-01, TD-02, K-06). Kod ve bağlantı belirteci yalnızca anahtarlı
-- özetiyle (HMAC-SHA-256, GKS_INVITATION_HASH_KEY) saklanır.
CREATE TABLE identity.invitations (
    id          uuid        NOT NULL,
    kind        text        NOT NULL,
    email       text        NOT NULL,
    user_id     uuid,
    scope_type  text        NOT NULL,
    scope_id    uuid        NOT NULL,
    token_hash  text        NOT NULL,
    code_hash   text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    status      text        NOT NULL DEFAULT 'PENDING',
    used_at     timestamptz,
    created_by  uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_invitations PRIMARY KEY (id),
    CONSTRAINT fk_invitations_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invitations_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT uq_invitations_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_invitations_kind CHECK (kind IN ('NEW_USER', 'OPERATION')),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_invitations_scope_type CHECK (scope_type IN ('ORGANIZATION', 'OPERATION')),
    CONSTRAINT ck_invitations_operation_kind
        CHECK (kind <> 'OPERATION' OR (scope_type = 'OPERATION' AND user_id IS NOT NULL)),
    CONSTRAINT ck_invitations_used CHECK (status NOT IN ('ACCEPTED', 'DECLINED') OR used_at IS NOT NULL),
    CONSTRAINT ck_invitations_email CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 254)
);

CREATE UNIQUE INDEX ux_invitations_pending_email_scope ON identity.invitations (email, scope_type, scope_id)
    WHERE status = 'PENDING';
CREATE INDEX ix_invitations_user_pending ON identity.invitations (user_id) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_expiry ON identity.invitations (expires_at) WHERE status = 'PENDING';
CREATE INDEX ix_invitations_scope ON identity.invitations (scope_type, scope_id, id DESC);
