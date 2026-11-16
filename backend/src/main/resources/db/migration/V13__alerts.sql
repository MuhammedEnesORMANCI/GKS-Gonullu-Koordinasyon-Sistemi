-- alerts modülü: acil alarm ve alıcı anlık görüntüsü (database-physical-design.md §6.3 – §6.4, emergency-alert-design.md).
-- Bu aşamada iletim gelen kutusu (IN_APP) ve SSE üzerindendir; FCM eklenene kadar alıcının "SENT" durumu
-- "gelen kutusuna yazıldı" anlamındadır (ADR-018). sent_notification_id: yeniden gönderimde aynı bildirimin
-- gelen kutusu kaydı yeniden okunmamış yapılır (FİZ eki).

CREATE TABLE alerts.alerts (
    id                        uuid        NOT NULL,
    operation_id              uuid        NOT NULL,
    message                   text        NOT NULL,
    requested_action          text,
    priority                  text        NOT NULL DEFAULT 'CRITICAL',
    state                     text        NOT NULL DEFAULT 'DRAFT',
    version                   integer     NOT NULL DEFAULT 1,
    target_expr               jsonb       NOT NULL,
    audience_fingerprint      text,
    preview_recipient_count   integer,
    previewed_at              timestamptz,
    recipient_count           integer,
    reminder_interval_seconds integer     NOT NULL DEFAULT 120,
    reminder_max              integer     NOT NULL DEFAULT 5,
    corrects_alert_id         uuid,
    idempotency_key           text,
    sent_notification_id      uuid,
    created_by                uuid        NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    sent_by                   uuid,
    sent_at                   timestamptz,
    cancelled_by              uuid,
    cancelled_at              timestamptz,
    closed_by                 uuid,
    closed_at                 timestamptz,
    close_reason              text,
    CONSTRAINT pk_alerts PRIMARY KEY (id),
    CONSTRAINT fk_alerts_operation FOREIGN KEY (operation_id) REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alerts_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alerts_sent_by FOREIGN KEY (sent_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alerts_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alerts_closed_by FOREIGN KEY (closed_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alerts_corrects FOREIGN KEY (corrects_alert_id) REFERENCES alerts.alerts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alerts_sent_notification FOREIGN KEY (sent_notification_id)
        REFERENCES notifications.notifications (id) ON DELETE RESTRICT,
    CONSTRAINT ck_alerts_not_self_correct CHECK (corrects_alert_id IS NULL OR corrects_alert_id <> id),
    CONSTRAINT ck_alerts_state CHECK (state IN ('DRAFT', 'SENT', 'OPEN', 'CANCELLED', 'CLOSED')),
    CONSTRAINT ck_alerts_priority CHECK (priority IN ('HIGH', 'CRITICAL')),
    CONSTRAINT ck_alerts_close_reason CHECK (close_reason IN ('MANUAL', 'OPERATION_CLOSED')),
    CONSTRAINT ck_alerts_message CHECK (char_length(message) BETWEEN 1 AND 500),
    CONSTRAINT ck_alerts_requested_action CHECK (requested_action IS NULL OR char_length(requested_action) <= 200),
    CONSTRAINT ck_alerts_sent_fields CHECK ((state = 'DRAFT') = (sent_at IS NULL)
        AND (state = 'DRAFT' OR (sent_by IS NOT NULL AND recipient_count IS NOT NULL))),
    CONSTRAINT ck_alerts_cancelled CHECK ((state = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_alerts_closed CHECK ((state = 'CLOSED') = (closed_at IS NOT NULL AND close_reason IS NOT NULL)),
    CONSTRAINT ck_alerts_manual_close_actor CHECK (close_reason IS DISTINCT FROM 'MANUAL' OR closed_by IS NOT NULL),
    CONSTRAINT ck_alerts_recipient_count CHECK (recipient_count IS NULL OR recipient_count BETWEEN 0 AND 500),
    CONSTRAINT ck_alerts_reminder CHECK (reminder_interval_seconds > 0 AND reminder_max BETWEEN 0 AND 10)
);

CREATE UNIQUE INDEX ux_alerts_corrects ON alerts.alerts (corrects_alert_id) WHERE corrects_alert_id IS NOT NULL;
CREATE INDEX ix_alerts_operation ON alerts.alerts (operation_id, id DESC);
CREATE INDEX ix_alerts_operation_open ON alerts.alerts (operation_id) WHERE state = 'OPEN';

CREATE TABLE alerts.alert_recipients (
    id                       uuid        NOT NULL,
    alert_id                 uuid        NOT NULL,
    user_id                  uuid        NOT NULL,
    via_group_id             uuid,
    state                    text        NOT NULL DEFAULT 'QUEUED',
    response_option          text,
    responded_at_device      timestamptz,
    responded_at_server      timestamptz,
    first_responded_at       timestamptz,
    response_idempotency_key text,
    queued_at                timestamptz NOT NULL DEFAULT now(),
    sent_at                  timestamptz,
    delivered_at             timestamptz,
    seen_at                  timestamptz,
    reminder_count           integer     NOT NULL DEFAULT 0,
    next_reminder_at         timestamptz,
    resend_count             integer     NOT NULL DEFAULT 0,
    failure_reason           text,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_recipients PRIMARY KEY (id),
    CONSTRAINT fk_alert_recipients_alert FOREIGN KEY (alert_id) REFERENCES alerts.alerts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alert_recipients_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alert_recipients_via_group FOREIGN KEY (via_group_id) REFERENCES groups.groups (id) ON DELETE RESTRICT,
    CONSTRAINT uq_alert_recipients_alert_user UNIQUE (alert_id, user_id),
    CONSTRAINT ck_alert_recipients_state CHECK (state IN ('QUEUED', 'SENT', 'DELIVERED', 'SEEN', 'RESPONDED',
        'UNANSWERED', 'FAILED')),
    CONSTRAINT ck_alert_recipients_response_option CHECK (response_option IN ('RECEIVED', 'NEED_HELP', 'ON_MY_WAY')),
    CONSTRAINT ck_alert_recipients_failure_reason CHECK (failure_reason IN ('NO_DEVICE', 'TOKEN_INVALID',
        'FCM_UNAVAILABLE', 'FCM_REJECTED')),
    CONSTRAINT ck_alert_recipients_response CHECK ((state = 'RESPONDED') = (response_option IS NOT NULL)),
    CONSTRAINT ck_alert_recipients_response_times CHECK (response_option IS NULL
        OR (responded_at_server IS NOT NULL AND first_responded_at IS NOT NULL)),
    CONSTRAINT ck_alert_recipients_failed CHECK ((state = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_alert_recipients_reminder CHECK (reminder_count BETWEEN 0 AND 10)
);

CREATE INDEX ix_alert_recipients_reminder_due ON alerts.alert_recipients (next_reminder_at)
    WHERE state IN ('SENT', 'DELIVERED', 'SEEN') AND next_reminder_at IS NOT NULL;
CREATE INDEX ix_alert_recipients_user ON alerts.alert_recipients (user_id, alert_id);
