-- Bảng quản lý ngày lễ/ngày nghỉ ngân hàng, dùng để nạp Quartz AnnualCalendar
-- (xem HolidayCalendarLoader) và tính ngày làm việc tiếp theo cho các luồng
-- nghiệp vụ liên ngân hàng (CITAD/NAPAS). Xem docs/bank-salary-sample/holiday-table-design.md.

-- 1. ENUM phân loại ngày lễ
CREATE TYPE holiday_category AS ENUM (
    'FIXED',       -- Ngày cố định dương lịch (VD: 30/4, 1/5, 2/9)
    'LUNAR',       -- Ngày tính theo âm lịch (VD: Tết Nguyên Đán, Giỗ tổ)
    'SUBSTITUTED', -- Ngày nghỉ bù (do ngày lễ chính thức trùng vào cuối tuần)
    'BRIDGE'       -- Ngày hoán đổi (nghỉ cầu nối theo công văn Chính phủ)
);

-- 2. Bảng quản lý ngày lễ chính thức và ngày nghỉ thực tế
CREATE TABLE holidays (
    id BIGSERIAL PRIMARY KEY,
    holiday_date DATE NOT NULL,
    holiday_name VARCHAR(150) NOT NULL,
    fiscal_year INT NOT NULL,                       -- tách năm để phân vùng (partition)/index phục vụ báo cáo đầu-cuối năm
    country_code VARCHAR(2) NOT NULL DEFAULT 'VN',   -- tiêu chuẩn ISO 3166-1 alpha-2
    branch_id VARCHAR(20) NOT NULL DEFAULT 'ALL',    -- 'ALL' hoặc mã chi nhánh cụ thể (nếu nghỉ cục bộ theo vùng)
    category holiday_category NOT NULL DEFAULT 'FIXED',

    -- Ngày nghỉ bù/hoán đổi thuộc về ngày lễ gốc nào
    parent_holiday_id BIGINT REFERENCES holidays (id) ON DELETE SET NULL,

    -- Luồng nghiệp vụ ngân hàng
    is_clearing_day BOOLEAN NOT NULL DEFAULT FALSE,  -- hệ thống liên ngân hàng (CITAD/NAPAS) có chạy không?
    is_working_day BOOLEAN NOT NULL DEFAULT FALSE,   -- TRUE nếu là ngày đi làm bù vào Thứ 7/CN

    -- Audit phục vụ kiểm toán ngân hàng
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_by VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_by VARCHAR(50) NOT NULL,

    CONSTRAINT unique_holiday_date_per_branch UNIQUE (holiday_date, country_code, branch_id)
);

-- Index phục vụ kiểm tra 1 ngày có phải ngày lễ hay không trên đường đi của giao dịch (transaction path)
CREATE INDEX idx_holidays_lookup
    ON holidays (holiday_date, country_code, branch_id, is_clearing_day, is_working_day);

-- Index phục vụ load lịch theo năm của phòng Vận hành (Operations)
CREATE INDEX idx_holidays_year ON holidays (fiscal_year, country_code);

-- 3. Hàm tính ngày làm việc tiếp theo: bỏ qua ngày lễ/cuối tuần, trừ khi ngày đó có cờ đi làm bù
CREATE OR REPLACE FUNCTION get_next_working_day(
    p_start_date DATE,
    p_country_code VARCHAR(2) DEFAULT 'VN',
    p_branch_id VARCHAR(20) DEFAULT 'ALL'
)
RETURNS DATE AS $$
DECLARE
    v_current_date DATE := p_start_date + INTERVAL '1 day';
    v_is_holiday BOOLEAN;
    v_is_working_day BOOLEAN;
    v_day_of_week INT;
BEGIN
    LOOP
        v_day_of_week := EXTRACT(ISODOW FROM v_current_date); -- 1: Thứ 2, ..., 6: Thứ 7, 7: Chủ nhật

        -- Ngày này có phải ngày lễ thực sự (không tính ngày đi làm bù) hay không
        SELECT EXISTS (
            SELECT 1 FROM holidays
            WHERE holiday_date = v_current_date
              AND country_code = p_country_code
              AND branch_id = p_branch_id
              AND is_working_day = FALSE
        ) INTO v_is_holiday;

        -- Ngày này có được đánh dấu đi làm bù hay không (VD: đi làm bù vào Thứ 7)
        SELECT is_working_day INTO v_is_working_day
        FROM holidays
        WHERE holiday_date = v_current_date
          AND country_code = p_country_code
          AND branch_id = p_branch_id;

        -- Điều kiện là ngày làm việc:
        -- Không phải ngày lễ VÀ không phải cuối tuần (Thứ 7, CN)
        -- HOẶC ngày đó có cờ đi làm bù (is_working_day = TRUE)
        IF (v_is_holiday = FALSE AND v_day_of_week < 6) OR v_is_working_day = TRUE THEN
            RETURN v_current_date;
        END IF;

        v_current_date := v_current_date + INTERVAL '1 day';
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- 4. Dữ liệu mẫu: Tết Nguyên Đán 2026 (Mùng 1 giả định trùng Thứ Bảy) + ngày nghỉ bù tương ứng.
-- Flyway đã bọc mỗi migration trong 1 transaction nên không cần BEGIN/COMMIT thủ công ở đây.
INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, is_clearing_day, created_by, updated_by)
VALUES ('2026-02-14', 'Mùng 1 Tết Nguyên Đán', 2026, 'VN', 'LUNAR', FALSE, 'SYSTEM_ADMIN', 'SYSTEM_ADMIN');

INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, parent_holiday_id, is_clearing_day, created_by, updated_by)
VALUES (
    '2026-02-16',
    'Nghỉ bù Mùng 1 Tết Nguyên Đán',
    2026,
    'VN',
    'SUBSTITUTED',
    (SELECT id FROM holidays WHERE holiday_date = '2026-02-14' AND country_code = 'VN'),
    FALSE,
    'SYSTEM_ADMIN',
    'SYSTEM_ADMIN'
);

-- 5. Dữ liệu test: Quốc khánh 2/9/2026 nghỉ hoán đổi, minh hoạ chuỗi 5 ngày nghỉ liên tục
-- Thứ Bảy 29/8 - Chủ Nhật 30/8 (cuối tuần, không cần lưu - hàm get_next_working_day tự loại qua
-- ISODOW) - Thứ Hai 31/8 (nghỉ cầu nối) - Thứ Ba 1/9 - Thứ Tư 2/9 (Quốc khánh), đổi lại đi làm bù
-- vào Thứ Bảy 22/8.
INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, is_clearing_day, created_by, updated_by)
VALUES ('2026-09-02', 'Quốc khánh 2/9', 2026, 'VN', 'FIXED', FALSE, 'SYSTEM_ADMIN', 'SYSTEM_ADMIN');

INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, parent_holiday_id, is_clearing_day, created_by, updated_by)
VALUES (
    '2026-09-01',
    'Nghỉ Quốc khánh 2/9 (ngày liền kề)',
    2026,
    'VN',
    'BRIDGE',
    (SELECT id FROM holidays WHERE holiday_date = '2026-09-02' AND country_code = 'VN'),
    FALSE,
    'SYSTEM_ADMIN',
    'SYSTEM_ADMIN'
);

INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, parent_holiday_id, is_clearing_day, created_by, updated_by)
VALUES (
    '2026-08-31',
    'Nghỉ cầu nối dịp Quốc khánh 2/9',
    2026,
    'VN',
    'BRIDGE',
    (SELECT id FROM holidays WHERE holiday_date = '2026-09-02' AND country_code = 'VN'),
    FALSE,
    'SYSTEM_ADMIN',
    'SYSTEM_ADMIN'
);

-- Thứ Bảy 22/8 đi làm bù cho ngày nghỉ cầu nối 31/8: is_working_day = TRUE để
-- get_next_working_day() và HolidayCalendarLoader không loại ngày này ra khỏi lịch làm việc.
INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, parent_holiday_id, is_clearing_day, is_working_day, created_by, updated_by)
VALUES (
    '2026-08-22',
    'Đi làm bù (hoán đổi cho ngày nghỉ 31/8)',
    2026,
    'VN',
    'BRIDGE',
    (SELECT id FROM holidays WHERE holiday_date = '2026-08-31' AND country_code = 'VN'),
    TRUE,
    TRUE,
    'SYSTEM_ADMIN',
    'SYSTEM_ADMIN'
);
