```sql
-- Thao tác tạo ENUM cho phân loại ngày lễ
CREATE TYPE holiday_category AS ENUM (
    'fixed',       -- Ngày cố định dương lịch (VD: 30/4, 1/5, 2/9)
    'lunar',       -- Ngày tính theo âm lịch (VD: Tết Nguyên Đán, Giỗ tổ)
    'substituted', -- Ngày nghỉ bù (Do ngày lễ chính thức trùng vào cuối tuần)
    'bridge'       -- Ngày hoán đổi (Nghỉ cầu nối theo công văn Chính phủ)
);

-- Bảng quản lý ngày lễ chính thức và ngày nghỉ thực tế
CREATE TABLE holidays (
    id BIGSERIAL PRIMARY KEY,
    holiday_date DATE NOT NULL,
    holiday_name VARCHAR(150) NOT NULL,
    fiscal_year INT NOT NULL, -- Tách năm để phân vùng (Partition) hoặc index phục vụ báo cáo đầu/cuối năm
    country_code VARCHAR(2) NOT NULL DEFAULT 'VN', -- Tiêu chuẩn ISO 3166-1 alpha-2
    branch_id VARCHAR(20) DEFAULT 'ALL', -- 'ALL' hoặc mã chi nhánh cụ thể (nếu nghỉ cục bộ theo vùng)
    category holiday_category NOT NULL DEFAULT 'fixed',
    
    -- Mối quan hệ log: Ngày nghỉ bù/hoán đổi thuộc về ngày lễ gốc nào
    parent_holiday_id BIGINT REFERENCES holidays(id) ON DELETE SET NULL,
    
    -- Luồng nghiệp vụ Ngân hàng
    is_clearing_day BOOLEAN NOT NULL DEFAULT FALSE, -- Cờ quan trọng: Hệ thống liên ngân hàng (CITAD/NAPAS) có chạy không?
    is_working_day BOOLEAN NOT NULL DEFAULT FALSE,  -- Bằng TRUE nếu là ngày đi làm bù vào Thứ 7/CN
    
    -- Audit logs phục vụ kiểm toán ngân hàng
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NOT NULL,
    
    -- Ràng buộc dữ liệu không trùng lặp ngày theo quốc gia và chi nhánh
    CONSTRAINT unique_holiday_date_per_branch UNIQUE (holiday_date, country_code, branch_id)
);

-- 2. Tối ưu hóa Index cho các câu lệnh kiểm tra giao dịch (Transaction Processing)
-- Index này giúp Core Banking kiểm tra 1 ngày có phải ngày lễ hay không với tốc độ O(1)
CREATE INDEX idx_holidays_lookup 
ON holidays (holiday_date, country_code, branch_id, is_clearing_day, is_working_day);

-- Index phục vụ việc load lịch theo năm của phòng ban Vận hành (Operations)
CREATE INDEX idx_holidays_year ON holidays (fiscal_year, country_code);
```

```sql
-- Dùng TRANSACTION để đảm bảo toàn vẹn dữ liệu khi lấy ID cho ngày nghỉ bù
BEGIN;

-- 1. Chèn ngày Mùng 1 Tết Âm Lịch (Giả sử trùng vào Thứ Bảy)
INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, is_clearing_day, created_by, updated_by)
VALUES ('2026-02-14', 'Mùng 1 Tết Nguyên Đán', 2026, 'VN', 'lunar', FALSE, 'SYSTEM_ADMIN', 'SYSTEM_ADMIN');

-- 2. Chèn ngày nghỉ bù vào Thứ Hai tuần sau đó (Liên kết qua parent_holiday_id)
INSERT INTO holidays (holiday_date, holiday_name, fiscal_year, country_code, category, parent_holiday_id, is_clearing_day, created_by, updated_by)
VALUES (
    '2026-02-16', 
    'Nghỉ bù Mùng 1 Tết Nguyên Đán', 
    2026, 
    'VN', 
    'substituted', 
    (SELECT id FROM holidays WHERE holiday_date = '2026-02-14' AND country_code = 'VN'), 
    FALSE, 
    'SYSTEM_ADMIN', 
    'SYSTEM_ADMIN'
);

COMMIT;

```

```sql
CREATE OR REPLACE FUNCTION get_next_working_day(
    p_start_date DATE,
    p_country_code VARCHAR(2) DEFAULT 'VN',
    p_branch_id VARCHAR(20) DEFAULT 'ALL'
) 
RETURNS DATE AS $$
DECLARE
    v_current_date DATE := p_start_date + INTERVAL '1 day';
    v_is_holiday BOOLEAN;
    v_day_of_week INT;
BEGIN
    LOOP
        v_day_of_week := EXTRACT(ISODOW FROM v_current_date); -- 1: Thứ 2, ..., 6: Thứ 7, 7: Chủ nhật
        
        -- Kiểm tra xem ngày này có nằm trong bảng holidays không
        SELECT EXISTS (
            SELECT 1 FROM holidays 
            WHERE holiday_date = v_current_date 
              AND country_code = p_country_code 
              AND branch_id = p_branch_id
              AND is_working_day = FALSE -- Nếu là ngày đi làm bù thì không tính là ngày lễ
        ) INTO v_is_holiday;

        -- Điều kiện là ngày làm việc:
        -- Không phải ngày lễ TRỪ KHI ngày lễ đó có cờ đi làm bù (is_working_day = true)
        -- HOẶC nếu là cuối tuần (T7, CN) thì phải không có lịch đi làm bù
        IF (v_is_holiday = FALSE AND v_day_of_week < 6) OR 
           (SELECT is_working_day FROM holidays WHERE holiday_date = v_current_date AND country_code = p_country_code AND branch_id = p_branch_id) = TRUE THEN
            RETURN v_current_date;
        END IF;

        -- Nếu là ngày nghỉ, tăng thêm 1 ngày để kiểm tra tiếp
        v_current_date := v_current_date + INTERVAL '1 day';
    END LOOP;
END;
$$ LANGUAGE plpgsql;
```