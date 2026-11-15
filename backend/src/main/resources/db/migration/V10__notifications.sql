-- Bildirimler modülü (database-physical-design.md §6.5, §6.6; communication.md §1; ADR-004, TD-13, LD-40, LD-60).
-- Bildirim (ne söylendi, kime hedeflendi) ile teslim (kime, hangi kanaldan, hangi durumda) ayrı tablolardır.
-- IN_APP teslimi iş işlemiyle aynı işlemde SENT olarak yazılır (gelen kutusu); PUSH satırları teslim kuyruğudur
-- ve yalnızca bir push sağlayıcısı (FCM) yapılandırıldığında oluşturulur.

CREATE TABLE notifications.notifications (
    id              uuid        NOT NULL,
    category        text        NOT NULL,
    kind            text        NOT NULL,
    priority        text        NOT NULL,
    title           text        NOT NULL,
    body            text,
    source_type     text,
    source_id       uuid,
    operation_id    uuid,
    audience_expr   jsonb       NOT NULL,
    recipient_count integer,
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    idempotency_key text        NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_notifications_operation FOREIGN KEY (operation_id) REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT ck_notifications_category CHECK (category IN ('ALERT', 'TASK', 'ANNOUNCEMENT', 'MEETING_POINT',
        'OPERATION_INVITE', 'SYSTEM')),
    -- backend-api-design.md §12.4 NotificationKind (API adları esas; LD-60).
    CONSTRAINT ck_notifications_kind CHECK (kind IN ('ALERT_SENT', 'ALERT_CANCELLED', 'ALERT_CLOSED', 'TASK_ASSIGNED',
        'TASK_UPDATED', 'TASK_CANCELLED', 'TASK_CAPACITY_FULL', 'TASK_ASSIGNMENT_REMOVED', 'MEETING_POINT_UPDATED',
        'MEETING_POINT_CANCELLED', 'GROUP_MEMBER_ADDED', 'ANNOUNCEMENT', 'OPERATION_INVITE', 'OPERATION_CLOSE_REMINDER',
        'LOCATION_STOP', 'SESSION_REVOKED')),
    CONSTRAINT ck_notifications_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'NORMAL', 'LOW')),
    CONSTRAINT ck_notifications_source_type CHECK (source_type IN ('ALERT', 'TASK', 'TASK_ASSIGNMENT', 'GROUP',
        'OPERATION', 'INVITATION')),
    CONSTRAINT ck_notifications_source_pair CHECK ((source_type IS NULL) = (source_id IS NULL)),
    CONSTRAINT ck_notifications_alert_critical CHECK (kind NOT IN ('ALERT_SENT', 'ALERT_CANCELLED') OR priority = 'CRITICAL'),
    CONSTRAINT ck_notifications_audience CHECK (jsonb_typeof(audience_expr) = 'object'),
    CONSTRAINT ck_notifications_title CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_notifications_body CHECK (body IS NULL OR char_length(body) <= 2000),
    CONSTRAINT ck_notifications_recipient_count CHECK (recipient_count IS NULL OR recipient_count >= 0)
);

-- Kaynak olay anahtarı (ör. task:{id}:PUBLISHED); en-az-bir-kez tekrarda ikinci bildirim oluşmaz (§6.5, AC-20.5).
CREATE UNIQUE INDEX uq_notifications_idempotency_key ON notifications.notifications (idempotency_key);
CREATE UNIQUE INDEX ux_notifications_alert_lifecycle ON notifications.notifications (source_id, kind)
    WHERE kind IN ('ALERT_SENT', 'ALERT_CANCELLED', 'ALERT_CLOSED');
CREATE INDEX ix_notifications_source ON notifications.notifications (source_type, source_id);
CREATE INDEX ix_notifications_announcements ON notifications.notifications (operation_id, id DESC)
    WHERE category = 'ANNOUNCEMENT';

CREATE TABLE notifications.deliveries (
    id              uuid        NOT NULL,
    notification_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    device_id       uuid,
    channel         text        NOT NULL,
    attempt         integer     NOT NULL DEFAULT 0,
    round           integer     NOT NULL DEFAULT 0,
    queue_class     text        NOT NULL,
    status          text        NOT NULL DEFAULT 'QUEUED',
    send_tries      integer     NOT NULL DEFAULT 0,
    available_at    timestamptz NOT NULL DEFAULT now(),
    fcm_message_id  text,
    error_code      text,
    queued_at       timestamptz NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    failed_at       timestamptz,
    read_at         timestamptz,
    CONSTRAINT pk_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_deliveries_notification FOREIGN KEY (notification_id) REFERENCES notifications.notifications (id) ON DELETE RESTRICT,
    CONSTRAINT fk_deliveries_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_deliveries_device FOREIGN KEY (device_id) REFERENCES identity.devices (id) ON DELETE RESTRICT,
    CONSTRAINT ck_deliveries_channel CHECK (channel IN ('PUSH', 'IN_APP')),
    CONSTRAINT ck_deliveries_status CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    CONSTRAINT ck_deliveries_queue_class CHECK (queue_class IN ('CRITICAL', 'STANDARD')),
    CONSTRAINT ck_deliveries_channel_device CHECK ((channel = 'PUSH') = (device_id IS NOT NULL)),
    CONSTRAINT ck_deliveries_in_app_shape CHECK (channel = 'PUSH' OR (attempt = 0 AND round = 0)),
    CONSTRAINT ck_deliveries_read_in_app CHECK (read_at IS NULL OR channel = 'IN_APP'),
    CONSTRAINT ck_deliveries_attempt CHECK (attempt BETWEEN 0 AND 10 AND round >= 0 AND send_tries BETWEEN 0 AND 10),
    CONSTRAINT ck_deliveries_sent CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_deliveries_push_unique ON notifications.deliveries (notification_id, user_id, device_id, round, attempt)
    WHERE channel = 'PUSH';
CREATE UNIQUE INDEX ux_deliveries_in_app_unique ON notifications.deliveries (notification_id, user_id)
    WHERE channel = 'IN_APP';
-- Teslim kuyruğu (§7.1): kısmi; kuyruk boşken indeks de neredeyse boştur.
CREATE INDEX ix_deliveries_queue ON notifications.deliveries (queue_class, available_at) WHERE status = 'QUEUED';
-- GET /me/notifications imleç sayfalaması ve okunmamış sayısı.
CREATE INDEX ix_deliveries_inbox ON notifications.deliveries (user_id, id DESC) WHERE channel = 'IN_APP';
CREATE INDEX ix_deliveries_unread ON notifications.deliveries (user_id) WHERE channel = 'IN_APP' AND read_at IS NULL;
CREATE INDEX ix_deliveries_notification ON notifications.deliveries (notification_id);
