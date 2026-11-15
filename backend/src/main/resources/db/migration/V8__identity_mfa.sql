-- identity: TOTP girişi (backend-api-design.md §2.3; K-19; LD-57).

-- MFA bileti: parola doğru, TOTP bekleniyor. 5 dk, tek kullanımlık, en fazla 5 hatalı kod. Yalnızca SHA-256 özeti
-- saklanır (bilet yüksek entropilidir; yenileme belirteci gibi, DB §3.4).
CREATE TABLE identity.mfa_tickets (
    id          uuid        NOT NULL,
    user_id     uuid        NOT NULL,
    token_hash  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    used_at     timestamptz,
    CONSTRAINT pk_mfa_tickets PRIMARY KEY (id),
    CONSTRAINT fk_mfa_tickets_user FOREIGN KEY (user_id) REFERENCES identity.users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_mfa_tickets_hash UNIQUE (token_hash),
    CONSTRAINT ck_mfa_tickets_attempts CHECK (attempts BETWEEN 0 AND 5)
);

CREATE INDEX ix_mfa_tickets_expires ON identity.mfa_tickets (expires_at);

-- Aynı TOTP kodu (30 sn adımı) ikinci kez kabul edilmez (gözlenen kodun tekrarına karşı).
ALTER TABLE identity.users ADD COLUMN totp_last_used_step bigint;
