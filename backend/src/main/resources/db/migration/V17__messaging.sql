-- Mesajlaşma (ADR-024): operasyon, koordinasyon ve grup kanalları. Üyelik saklanmaz; her istekte grup üyeliği,
-- katılım ve rollerden hesaplanır (gruptan/operasyondan çıkan erişimi anında kaybeder). Birebir mesaj yoktur.
CREATE SCHEMA IF NOT EXISTS messaging;

CREATE TABLE messaging.channels (
    id            uuid        NOT NULL,
    operation_id  uuid        NOT NULL,
    group_id      uuid,
    kind          text        NOT NULL,
    admins_only   boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- Operasyon kapanınca dolar: kanal salt okunur, saklama süresi buradan sayılır.
    closed_at     timestamptz,
    CONSTRAINT pk_channels PRIMARY KEY (id),
    CONSTRAINT ck_channels_kind CHECK (kind IN ('OPERATION', 'COORDINATION', 'GROUP')),
    CONSTRAINT ck_channels_group CHECK ((kind = 'GROUP') = (group_id IS NOT NULL)),
    CONSTRAINT ck_channels_admins_only CHECK (kind = 'OPERATION' OR NOT admins_only)
);
CREATE UNIQUE INDEX ux_channels_operation_kind ON messaging.channels (operation_id, kind) WHERE group_id IS NULL;
CREATE UNIQUE INDEX ux_channels_group ON messaging.channels (group_id) WHERE group_id IS NOT NULL;
CREATE INDEX ix_channels_closed ON messaging.channels (closed_at) WHERE closed_at IS NOT NULL;

CREATE TABLE messaging.messages (
    id          uuid        NOT NULL,
    channel_id  uuid        NOT NULL,
    sender_id   uuid,
    kind        text        NOT NULL DEFAULT 'USER',
    body        text        NOT NULL,
    created_at  timestamptz NOT NULL,
    deleted_at  timestamptz,
    hidden_at   timestamptz,
    hidden_by   uuid,
    -- Çevrimdışı kuyruktan yeniden gönderimde tekilleştirme (Idempotency-Key).
    client_key  text,
    CONSTRAINT pk_messages PRIMARY KEY (id),
    CONSTRAINT fk_messages_channel FOREIGN KEY (channel_id) REFERENCES messaging.channels (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_messages_hidden_by FOREIGN KEY (hidden_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_messages_kind CHECK (kind IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_messages_sender CHECK ((kind = 'USER') = (sender_id IS NOT NULL)),
    CONSTRAINT ck_messages_body CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT ck_messages_hidden CHECK ((hidden_at IS NULL) = (hidden_by IS NULL)),
    CONSTRAINT ck_messages_client_key CHECK (client_key IS NULL OR char_length(client_key) BETWEEN 8 AND 200)
);
CREATE INDEX ix_messages_channel ON messaging.messages (channel_id, id DESC);
CREATE UNIQUE INDEX ux_messages_client_key ON messaging.messages (sender_id, client_key) WHERE client_key IS NOT NULL;

CREATE TABLE messaging.reads (
    channel_id    uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    last_read_id  uuid        NOT NULL,
    read_at       timestamptz NOT NULL,
    CONSTRAINT pk_reads PRIMARY KEY (channel_id, user_id),
    CONSTRAINT fk_reads_channel FOREIGN KEY (channel_id) REFERENCES messaging.channels (id) ON DELETE CASCADE
);

CREATE TABLE messaging.mutes (
    channel_id  uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    muted_at    timestamptz NOT NULL,
    CONSTRAINT pk_mutes PRIMARY KEY (channel_id, user_id),
    CONSTRAINT fk_mutes_channel FOREIGN KEY (channel_id) REFERENCES messaging.channels (id) ON DELETE CASCADE
);

-- Uygulama rolü (ADR-020): iş şemalarıyla aynı yetkiler.
GRANT USAGE ON SCHEMA messaging TO "${appUser}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA messaging TO "${appUser}";
ALTER DEFAULT PRIVILEGES IN SCHEMA messaging GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${appUser}";

-- Mesaj bildirimi: yalnızca push (gelen kutusuna düşmez), yükte yalnızca kanal ve mesaj kimliği.
ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notifications_category;
ALTER TABLE notifications.notifications ADD CONSTRAINT ck_notifications_category CHECK (category IN ('ALERT', 'TASK',
    'ANNOUNCEMENT', 'MEETING_POINT', 'OPERATION_INVITE', 'SYSTEM', 'MESSAGE'));
ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notifications_kind;
ALTER TABLE notifications.notifications ADD CONSTRAINT ck_notifications_kind CHECK (kind IN ('ALERT_SENT',
    'ALERT_CANCELLED', 'ALERT_CLOSED', 'TASK_ASSIGNED', 'TASK_UPDATED', 'TASK_CANCELLED', 'TASK_CAPACITY_FULL',
    'TASK_ASSIGNMENT_REMOVED', 'MEETING_POINT_UPDATED', 'MEETING_POINT_CANCELLED', 'GROUP_MEMBER_ADDED', 'ANNOUNCEMENT',
    'OPERATION_INVITE', 'OPERATION_CLOSE_REMINDER', 'LOCATION_STOP', 'SESSION_REVOKED', 'MESSAGE_POSTED'));
ALTER TABLE notifications.notifications DROP CONSTRAINT ck_notifications_source_type;
ALTER TABLE notifications.notifications ADD CONSTRAINT ck_notifications_source_type CHECK (source_type IN ('ALERT',
    'TASK', 'TASK_ASSIGNMENT', 'GROUP', 'OPERATION', 'INVITATION', 'CHANNEL'));

-- Denetim: moderasyon ve kanal ayarı.
ALTER TABLE audit.audit_entries DROP CONSTRAINT ck_audit_entries_resource_type;
ALTER TABLE audit.audit_entries ADD CONSTRAINT ck_audit_entries_resource_type CHECK (resource_type IN ('ALERT',
    'ALERT_RECIPIENT', 'TASK', 'TASK_ASSIGNMENT', 'GROUP', 'GROUP_MEMBERSHIP', 'OPERATION', 'ROLE_ASSIGNMENT', 'USER',
    'SESSION', 'INVITATION', 'LOCATION', 'NOTIFICATION', 'MEETING_POINT', 'PUBLIC_ANNOUNCEMENT', 'MESSAGE', 'CHANNEL'));
