-- Kamu duyuruları (ADR-023): halka açık sitede ve uygulamada gösterilen, operasyon (iç) duyurularından ayrı kayıtlar.
-- ADM oluşturur, düzenler, yayımlar, yayından kaldırır (taslak / yayında). Kimlik doğrulamasız uç yalnızca yayındakileri
-- okur. Uygulama rolü (gks_app) yetkileri V15'teki varsayılan yetkilerle (notifications şeması) gelir.
CREATE TABLE notifications.public_announcements (
    id            uuid        NOT NULL,
    title         text        NOT NULL,
    body          text        NOT NULL,
    state         text        NOT NULL DEFAULT 'DRAFT',
    version       integer     NOT NULL DEFAULT 1,
    created_by    uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_by    uuid        NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz,
    CONSTRAINT pk_public_announcements PRIMARY KEY (id),
    CONSTRAINT fk_public_announcements_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_public_announcements_updated_by FOREIGN KEY (updated_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_public_announcements_title CHECK (char_length(title) BETWEEN 3 AND 160),
    CONSTRAINT ck_public_announcements_body CHECK (char_length(body) BETWEEN 1 AND 5000),
    CONSTRAINT ck_public_announcements_state CHECK (state IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_public_announcements_published CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT ck_public_announcements_version CHECK (version >= 1)
);

-- Halka açık liste: yayındakiler, yeniden eskiye (imleç: published_at, id).
CREATE INDEX ix_public_announcements_published ON notifications.public_announcements (published_at DESC, id DESC)
    WHERE state = 'PUBLISHED';
CREATE INDEX ix_public_announcements_updated ON notifications.public_announcements (updated_at DESC, id DESC);

-- Denetim: yeni kaynak türü.
ALTER TABLE audit.audit_entries DROP CONSTRAINT ck_audit_entries_resource_type;
ALTER TABLE audit.audit_entries ADD CONSTRAINT ck_audit_entries_resource_type CHECK (resource_type IN ('ALERT',
    'ALERT_RECIPIENT', 'TASK', 'TASK_ASSIGNMENT', 'GROUP', 'GROUP_MEMBERSHIP', 'OPERATION', 'ROLE_ASSIGNMENT', 'USER',
    'SESSION', 'INVITATION', 'LOCATION', 'NOTIFICATION', 'MEETING_POINT', 'PUBLIC_ANNOUNCEMENT'));
