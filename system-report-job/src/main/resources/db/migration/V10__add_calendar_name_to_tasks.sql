-- Cho phép gắn 1 Quartz Calendar đã đăng ký (vd "bankHolidays", xem HolidayCalendarLoader) vào
-- trigger của 1 Task, để Quartz tự động không fire trigger vào các ngày calendar loại trừ
-- (cuối tuần/ngày lễ). NULL = không gắn calendar nào (hành vi hiện tại, không đổi).
ALTER TABLE tasks ADD COLUMN calendar_name VARCHAR(100);
