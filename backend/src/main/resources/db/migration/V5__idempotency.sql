-- HTTP idempotency kaydı (database-physical-design.md §8.3; backend-api-design.md §3.6, §15.4; LD-04, LD-42, LD-45).
-- Satır iş işleminin başında aynı işlemde eklenir (response_status NULL), yanıt kodu commit'ten önce yazılır.
-- Sır içeren hiçbir değer yazılmaz: istek yalnızca anahtarlı ve sürümlü özetle (request_hash, hash_key_version) tutulur.

CREATE TABLE platform.idempotency_keys (
    principal_key    text        NOT NULL,
    idem_key         text        NOT NULL,
    method           text        NOT NULL,
    route_template   text        NOT NULL,
    request_hash     text        NOT NULL,
    hash_key_version smallint    NOT NULL,
    response_status  integer,
    response_body    text,
    body_redacted    boolean     NOT NULL DEFAULT false,
    resource_type    text,
    resource_id      uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (principal_key, idem_key),
    -- NULL yalnızca işlem sürerken geçerlidir; commit anındaki denetim ct_idempotency_keys_completed'dadır.
    CONSTRAINT ck_idempotency_keys_status CHECK (response_status BETWEEN 200 AND 299),
    CONSTRAINT ck_idempotency_keys_key_len CHECK (char_length(idem_key) BETWEEN 8 AND 64),
    CONSTRAINT ck_idempotency_keys_redacted CHECK (NOT body_redacted OR response_body IS NULL),
    CONSTRAINT ck_idempotency_keys_hash_key_version CHECK (hash_key_version > 0)
);

CREATE INDEX ix_idempotency_keys_expires ON platform.idempotency_keys (expires_at);

-- LD-45: commit anında satır birincil anahtarla yeniden okunur. NEW'e güvenilmez (eklemede durum hep NULL'dur).
-- Satır yoksa (aynı işlemde silindi) geçer; durum 2xx ise geçer; NULL kaldıysa commit reddedilir.
CREATE FUNCTION platform.idempotency_keys_completed_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    current_status integer;
BEGIN
    SELECT response_status INTO current_status
      FROM platform.idempotency_keys
     WHERE principal_key = NEW.principal_key AND idem_key = NEW.idem_key;
    IF FOUND AND current_status IS NULL THEN
        RAISE EXCEPTION 'ct_idempotency_keys_completed: idempotency satırı yanıt kodu yazılmadan commit edilemez'
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ct_idempotency_keys_completed';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ct_idempotency_keys_completed
    AFTER INSERT OR UPDATE OF response_status ON platform.idempotency_keys
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION platform.idempotency_keys_completed_check();
