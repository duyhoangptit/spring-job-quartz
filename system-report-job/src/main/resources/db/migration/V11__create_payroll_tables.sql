-- Bảng dữ liệu cho sample BANK_SALARY_PAYROLL (chuyển lương hàng loạt FPT Software), xem
-- docs/bank-salary-sample/bank-salary-sample.md và
-- docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md.

-- Tài khoản nguồn của công ty (FPT Software) tại TPBank.
CREATE TABLE fpt_company_account (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(30) NOT NULL UNIQUE,
    account_number VARCHAR(20) NOT NULL,
    balance NUMERIC(18, 2) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tài khoản trung gian nội bộ TPBank, giữ tiền đã trừ của công ty trước khi giải ngân xong hết.
CREATE TABLE gl_suspense_account (
    id BIGSERIAL PRIMARY KEY,
    account_code VARCHAR(30) NOT NULL UNIQUE,
    balance NUMERIC(18, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 1 dòng / kỳ lương (company_code + target_pay_date). unique constraint đảm bảo không giữ tiền
-- 2 lần cho cùng 1 kỳ nếu job vô tình chạy lại đúng ngày.
CREATE TABLE payroll_batch_run (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(30) NOT NULL,
    target_pay_date DATE NOT NULL,
    total_employees INT NOT NULL,
    total_amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- HOLD_SUCCESS, COMPLETED
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT unique_payroll_run_per_period UNIQUE (company_code, target_pay_date)
);

-- 1 dòng / nhân viên / kỳ lương — mọi dòng trong CSV đầu vào đều có đúng 1 dòng kết quả ở đây,
-- kể cả những dòng bị skip do lỗi (status = SKIPPED), không dòng nào bị bỏ qua âm thầm.
CREATE TABLE payroll_disbursement (
    id BIGSERIAL PRIMARY KEY,
    batch_run_id BIGINT NOT NULL REFERENCES payroll_batch_run (id),
    employee_id VARCHAR(30) NOT NULL,
    account_number VARCHAR(20) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- SUCCESS, SKIPPED
    error_reason VARCHAR(255),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payroll_disbursement_run ON payroll_disbursement (batch_run_id, status);

-- Seed: đủ số dư để giải ngân cho ~30.000 nhân viên (~60 triệu VND/người tối đa) chạy được ngay
-- sau "mvn spring-boot:run" mà không cần setup DB thủ công.
INSERT INTO fpt_company_account (company_code, account_number, balance)
VALUES ('FPT_SOFTWARE', '9999000111222', 500000000000.00);

-- Seed: tài khoản GL trung gian bắt đầu ở 0 — bắt buộc phải có sẵn dòng này vì Step 1/Step 2 chỉ
-- UPDATE (không INSERT ... ON CONFLICT), một UPDATE khớp 0 dòng sẽ âm thầm không giữ/trừ tiền.
INSERT INTO gl_suspense_account (account_code, balance)
VALUES ('PAYROLL_SUSPENSE_GL', 0);
