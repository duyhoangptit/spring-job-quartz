-- src/main/resources/db/migration/V12__create_company_pgp_key_config.sql
-- Cấu hình PGP key theo company, dùng bởi DecryptCompanyFileUseCase để decrypt + verify file công
-- ty gửi sang trước khi các JobAction (BANK_SALARY_PAYROLL, ...) đọc file.
-- Xem docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md.
CREATE TABLE company_pgp_key_config (
    id                             UUID PRIMARY KEY,
    company_code                   VARCHAR(30) NOT NULL,
    bank_private_key_encrypted     TEXT NOT NULL,
    bank_key_passphrase_encrypted  TEXT NOT NULL,
    company_public_key_armored     TEXT NOT NULL,
    key_fingerprint                VARCHAR(64),
    active                         BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted                     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_company_pgp_key_config_company UNIQUE (company_code)
);
