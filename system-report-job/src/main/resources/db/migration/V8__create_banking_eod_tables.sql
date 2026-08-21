-- Bảng dữ liệu giả lập cho sample job BANKING_EOD (xem docs/batch-banking/banking_batch_test_guide.md).

-- 1. Bảng kiểm tra trạng thái hệ thống Core Banking
CREATE TABLE sys_status (
    sys_key VARCHAR(50) PRIMARY KEY,
    status_value VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO sys_status (sys_key, status_value) VALUES ('CORE_SYSTEM', 'READY');

-- 2. Bảng lưu trữ giao dịch từ ATM/POS
CREATE TABLE atm_transactions (
    txn_id SERIAL PRIMARY KEY,
    account_number VARCHAR(20) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    txn_type VARCHAR(10) NOT NULL, -- DEPOSIT, WITHDRAWAL
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

INSERT INTO atm_transactions (account_number, amount, txn_type) VALUES
    ('ACC1001', 5000000.00, 'DEPOSIT'),
    ('ACC1002', 2000000.00, 'WITHDRAWAL'),
    ('ACC1003', 1500000.00, 'DEPOSIT');

-- 3. Bảng danh sách tài khoản tiết kiệm tính lãi
CREATE TABLE saving_accounts (
    account_number VARCHAR(20) PRIMARY KEY,
    balance NUMERIC(15, 2) NOT NULL,
    interest_rate NUMERIC(5, 4) NOT NULL, -- ví dụ: 0.0550 = 5.5%
    accrued_interest NUMERIC(15, 2) NOT NULL DEFAULT 0.00
);

INSERT INTO saving_accounts (account_number, balance, interest_rate) VALUES
    ('ACC1001', 100000000.00, 0.0550),
    ('ACC1002', 500000000.00, 0.0600);

-- 4. Bảng tổng hợp báo cáo cuối ngày (EOD)
CREATE TABLE eod_summary_report (
    report_date DATE PRIMARY KEY DEFAULT CURRENT_DATE,
    total_atm_txns INT NOT NULL DEFAULT 0,
    total_interest_paid NUMERIC(15, 2) NOT NULL DEFAULT 0.00,
    execution_status VARCHAR(20)
);
