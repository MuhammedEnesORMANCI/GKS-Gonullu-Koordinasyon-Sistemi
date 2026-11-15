-- ADR-014: ADM/KOR Android'de de TOTP ile yönetim oturumu açar. MFA bileti, doğrulama sonrasında açılacak
-- oturumun istemci türünü ve (Android'de) kurulum kimliğini taşır; web biletleri değişmez.
ALTER TABLE identity.mfa_tickets ADD COLUMN client text NOT NULL DEFAULT 'WEB';
ALTER TABLE identity.mfa_tickets ADD COLUMN installation_id text;
ALTER TABLE identity.mfa_tickets ADD CONSTRAINT ck_mfa_tickets_client CHECK (client IN ('WEB', 'ANDROID'));
ALTER TABLE identity.mfa_tickets ADD CONSTRAINT ck_mfa_tickets_installation
    CHECK ((client = 'ANDROID') = (installation_id IS NOT NULL));

-- ADR-013'ün veritabanı kopyası (MFA'lı oturum yalnızca web) ADR-014 ile değişir: MFA'lı oturum Android'de de
-- açılabilir, ancak hiçbir MFA'lı zincir 12 saati aşamaz (web LD-19 ile aynı mutlak sınır; döndürme bitişi
-- devraldığından her halef satırında da expires_at - issued_at <= 12 sa). MFA'sız Android oturumu 30 gün kalır.
ALTER TABLE identity.refresh_tokens DROP CONSTRAINT ck_refresh_tokens_mfa_web_only;
ALTER TABLE identity.refresh_tokens ADD CONSTRAINT ck_refresh_tokens_mfa_absolute
    CHECK (NOT mfa_verified OR expires_at <= issued_at + interval '12 hours');
