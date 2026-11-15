-- tasks modülü: görev, kişi bazlı atama ve durum geçmişi (database-physical-design.md §5.3 – §5.5; uzunluklar §1.4, LD-51).

CREATE TABLE tasks.tasks (
    id                      uuid        NOT NULL,
    operation_id            uuid        NOT NULL,
    title                   text        NOT NULL,
    description             text,
    priority                text        NOT NULL DEFAULT 'NORMAL',
    meeting_point           text,
    time_window             tstzrange,
    capacity                integer,
    accepted_count          integer     NOT NULL DEFAULT 0,
    response_window_minutes integer     NOT NULL DEFAULT 60,
    target_expr             jsonb,
    state                   text        NOT NULL DEFAULT 'DRAFT',
    version                 integer     NOT NULL DEFAULT 1,
    created_by              uuid        NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    published_at            timestamptz,
    completed_at            timestamptz,
    cancelled_at            timestamptz,
    state_changed_by        uuid,
    CONSTRAINT pk_tasks PRIMARY KEY (id),
    CONSTRAINT fk_tasks_operation FOREIGN KEY (operation_id)
        REFERENCES organization.operations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_created_by FOREIGN KEY (created_by) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_state_changed_by FOREIGN KEY (state_changed_by)
        REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tasks_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_tasks_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_tasks_capacity_positive CHECK (capacity IS NULL OR capacity > 0),
    -- K-12'nin veritabanı güvencesi (E-1, E-2): kabul sayısı kapasiteyi hiçbir koşulda aşamaz.
    CONSTRAINT ck_tasks_capacity_not_exceeded CHECK (capacity IS NULL OR accepted_count <= capacity),
    CONSTRAINT ck_tasks_accepted_nonneg CHECK (accepted_count >= 0),
    CONSTRAINT ck_tasks_response_window CHECK (response_window_minutes BETWEEN 5 AND 1440),
    CONSTRAINT ck_tasks_time_window CHECK (time_window IS NULL
        OR (NOT isempty(time_window) AND NOT lower_inf(time_window))),
    CONSTRAINT ck_tasks_published CHECK (state = 'DRAFT' OR published_at IS NOT NULL
        OR state = 'CANCELLED'),
    CONSTRAINT ck_tasks_target_expr CHECK (target_expr IS NULL OR jsonb_typeof(target_expr) = 'object'),
    CONSTRAINT ck_tasks_lengths CHECK (
        char_length(title) BETWEEN 3 AND 120
        AND (description IS NULL OR char_length(description) BETWEEN 1 AND 2000)
        AND (meeting_point IS NULL OR char_length(meeting_point) BETWEEN 1 AND 300))
);

CREATE INDEX ix_tasks_operation ON tasks.tasks (operation_id, id DESC);
CREATE INDEX ix_tasks_operation_open ON tasks.tasks (operation_id) WHERE state IN ('PUBLISHED', 'IN_PROGRESS');

CREATE TABLE tasks.task_assignments (
    id           uuid        NOT NULL,
    task_id      uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    via_group_id uuid,
    source       text        NOT NULL DEFAULT 'SNAPSHOT',
    status       text        NOT NULL DEFAULT 'PENDING',
    assigned_at  timestamptz NOT NULL DEFAULT now(),
    assigned_by  uuid        NOT NULL,
    respond_by   timestamptz NOT NULL,
    responded_at timestamptz,
    removed_at   timestamptz,
    removed_by   uuid,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_task_assignments PRIMARY KEY (id),
    CONSTRAINT fk_task_assignments_task FOREIGN KEY (task_id) REFERENCES tasks.tasks (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_assignments_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_assignments_assigned_by FOREIGN KEY (assigned_by)
        REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_assignments_removed_by FOREIGN KEY (removed_by)
        REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_assignments_via_group FOREIGN KEY (via_group_id)
        REFERENCES groups.groups (id) ON DELETE RESTRICT,
    -- Tek atama (DM §5B); yeniden atama mevcut satırın UPDATE ile PENDING'e dönmesidir (§5.4).
    CONSTRAINT uq_task_assignments_task_user UNIQUE (task_id, user_id),
    CONSTRAINT ck_task_assignments_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CAPACITY_FULL', 'REMOVED')),
    CONSTRAINT ck_task_assignments_source CHECK (source IN ('SNAPSHOT', 'MANUAL')),
    CONSTRAINT ck_task_assignments_responded
        CHECK (status NOT IN ('ACCEPTED', 'REJECTED') OR responded_at IS NOT NULL),
    CONSTRAINT ck_task_assignments_removed CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL))
);

CREATE INDEX ix_task_assignments_user ON tasks.task_assignments (user_id, task_id);
CREATE INDEX ix_task_assignments_pending ON tasks.task_assignments (task_id, respond_by) WHERE status = 'PENDING';

-- AC-15.2: görev durum geçmişi (LD-24). Yalnızca eklenir; durum değişikliğiyle aynı işlemde yazılır.
CREATE TABLE tasks.task_state_changes (
    id         uuid        NOT NULL,
    task_id    uuid        NOT NULL,
    from_state text,
    to_state   text        NOT NULL,
    changed_by uuid        NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_task_state_changes PRIMARY KEY (id),
    CONSTRAINT fk_task_state_changes_task FOREIGN KEY (task_id) REFERENCES tasks.tasks (id) ON DELETE RESTRICT,
    CONSTRAINT fk_task_state_changes_changed_by FOREIGN KEY (changed_by)
        REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_state_changes_states CHECK (
        (from_state IS NULL OR from_state IN ('DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
        AND to_state IN ('DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
);

CREATE INDEX ix_task_state_changes_task ON tasks.task_state_changes (task_id, id);
