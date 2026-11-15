-- identity modülü: kullanıcı, cihaz ve yenileme belirteci (database-physical-design.md §3.1, §3.3, §3.4).
-- Davet tablosu (§3.2) davet kabulüyle birlikte eklenecek.

CREATE TABLE identity.users (
    id                   uuid        NOT NULL,
    email                text        NOT NULL,
    password_hash        text        NOT NULL,
    full_name            text        NOT NULL,
    display_name         text        NOT NULL,
    phone                text,
    status               text        NOT NULL DEFAULT 'ACTIVE',
    totp_secret          text,
    totp_enrolled_at     timestamptz,
    must_change_password boolean     NOT NULL DEFAULT false,
    failed_login_count   integer     NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    password_changed_at  timestamptz,
    disabled_at          timestamptz,
    closed_at            timestamptz,
    closure_requested_at timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'CLOSED')),
    CONSTRAINT ck_users_failed_login CHECK (failed_login_count >= 0),
    CONSTRAINT ck_users_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_users_lengths CHECK (
        char_length(email) BETWEEN 3 AND 254
        AND char_length(full_name) BETWEEN 2 AND 100
        AND char_length(display_name) BETWEEN 2 AND 40
        AND char_length(password_hash) > 0
        AND (phone IS NULL OR phone ~ '^\+[1-9][0-9]{7,14}$'))
);

CREATE TABLE identity.devices (
    id                  uuid        NOT NULL,
    user_id             uuid        NOT NULL,
    installation_id     text        NOT NULL,
    fcm_token           text        NOT NULL,
    platform            text        NOT NULL DEFAULT 'ANDROID',
    app_version         text,
    active              boolean     NOT NULL DEFAULT true,
    deactivated_at      timestamptz,
    deactivation_reason text,
    registered_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at        timestamptz,
    CONSTRAINT pk_devices PRIMARY KEY (id),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_devices_user_installation UNIQUE (user_id, installation_id),
    CONSTRAINT ck_devices_platform CHECK (platform IN ('ANDROID')),
    CONSTRAINT ck_devices_active_reason CHECK (active = (deactivated_at IS NULL)),
    CONSTRAINT ck_devices_deactivation_reason CHECK (deactivation_reason IS NULL OR deactivation_reason IN (
        'FCM_UNREGISTERED', 'FCM_INVALID_TOKEN', 'LOGOUT', 'USER_SWITCH', 'TOKEN_REPLACED', 'ACCOUNT_DISABLED'))
);

CREATE UNIQUE INDEX ux_devices_fcm_token_active ON identity.devices (fcm_token) WHERE active;
CREATE UNIQUE INDEX ux_devices_installation_active ON identity.devices (installation_id) WHERE active;
CREATE INDEX ix_devices_user_active ON identity.devices (user_id) WHERE active;

-- Belirteç yüksek entropilidir (256 bit); yalnızca SHA-256 özeti saklanır (ADR-012).
CREATE TABLE identity.refresh_tokens (
    id            uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    device_id     uuid,
    family_id     uuid        NOT NULL,
    client        text        NOT NULL,
    token_hash    text        NOT NULL,
    mfa_verified  boolean     NOT NULL DEFAULT false,
    issued_at     timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    last_used_at  timestamptz,
    replaced_by   uuid,
    revoked_at    timestamptz,
    revoke_reason text,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refresh_tokens_device FOREIGN KEY (device_id) REFERENCES identity.devices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_refresh_tokens_replaced_by FOREIGN KEY (replaced_by)
        REFERENCES identity.refresh_tokens (id) ON DELETE SET NULL,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_client CHECK (client IN ('WEB', 'ANDROID')),
    CONSTRAINT ck_refresh_tokens_mfa_web_only CHECK (client = 'WEB' OR mfa_verified = false),
    CONSTRAINT ck_refresh_tokens_web_no_device CHECK (client = 'ANDROID' OR device_id IS NULL),
    CONSTRAINT ck_refresh_tokens_revoke_reason CHECK (revoke_reason IS NULL OR revoke_reason IN (
        'LOGOUT', 'LOGOUT_ALL', 'ADMIN_REVOKE', 'REUSE_DETECTED', 'ACCOUNT_DISABLED',
        'PASSWORD_CHANGED', 'MFA_RESET', 'ROTATION_RETRY'))
);

CREATE INDEX ix_refresh_tokens_family ON identity.refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_revoked_live ON identity.refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX ix_refresh_tokens_user_active ON identity.refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_expires ON identity.refresh_tokens (expires_at);

-- V2'de ertelenen operasyon → kullanıcı bağları (database-physical-design.md §4.3).
ALTER TABLE organization.operations
    ADD CONSTRAINT fk_operations_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_operations_closed_by FOREIGN KEY (closed_by) REFERENCES identity.users (id) ON DELETE RESTRICT;
