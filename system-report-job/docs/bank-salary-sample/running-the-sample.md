# Chạy thử sample BANK_SALARY_PAYROLL

Xem thiết kế đầy đủ tại `docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md` và
mô tả nghiệp vụ gốc tại `bank-salary-sample.md` trong cùng thư mục này.

## 1. Sinh file CSV lương mẫu (30.000 nhân viên)

`PayrollJobAction` chỉ thực sự chạy vào đúng "target pay date" của tháng: ngày 19 nếu là ngày
làm việc, hoặc ngày làm việc gần nhất sau đó nếu 19 rơi vào cuối tuần/ngày lễ (`bankHolidays`).
File CSV phải được đặt tên khớp đúng ngày đó:

```bash
mkdir -p docs/bank-salary-sample/sample-data
python3 scripts/generate-fpt-payroll-csv.py \
  --count 30000 \
  --out docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv
```

Ví dụ tháng 9/2026: ngày 19 rơi vào Thứ Bảy → kỳ lương thực tế là Thứ Hai 21/09/2026 → file
phải tên `FPT_PAYROLL_2026-09-21.csv`.

**Lưu ý:** File CSV mẫu đã được sinh sẵn tại `docs/bank-salary-sample/sample-data/FPT_PAYROLL_2026-09-21.csv` (30.000 dòng) trong quá trình thiết lập sample này. Bạn có thể dùng luôn file này để test ngay, hoặc chạy lại lệnh trên nếu muốn tạo dataset riêng.

## 2. Tạo JobDefinition

```bash
curl --location 'http://localhost:8081/api/job-definitions' \
--header 'Content-Type: application/json' \
--data '{
    "jobType": "BANK_SALARY_PAYROLL",
    "expression": "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"docs/bank-salary-sample/sample-data\",\"countryCode\":\"VN\",\"branchId\":\"ALL\",\"payDayOfMonth\":25}",
    "description": "Chuyển lương hàng loạt FPT Software"
}'
```

Trường `payDayOfMonth` là tùy chọn (mặc định là 19); bạn có thể thay đổi nó để phù hợp với chính sách lương của công ty khác.

Lấy `data.id` trong response JSON, dùng làm `jobDefinitionId` ở bước sau.

## 3. Tạo Task (Cron hàng ngày, gắn calendar bankHolidays)

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "fpt-payroll-monthly",
    "group": "payroll",
    "jobDefinitionId": "<id-từ-bước-2>",
    "triggerType": "CRON",
    "cronExpression": "0 0 8 * * ?",
    "calendarName": "bankHolidays",
    "timezoneId": "Asia/Ho_Chi_Minh",
    "description": "Chạy 08:00 hàng ngày; chỉ thực sự giải ngân đúng ngày 19 (hoặc ngày làm việc kế tiếp)"
  }'
```

`calendarName: "bankHolidays"` khiến Quartz hoàn toàn không fire trigger vào cuối tuần/ngày lễ
(xem `HolidayCalendarLoader` + `QuartzTriggerFactory`). `PayrollJobAction` tự kiểm tra thêm điều
kiện "hôm nay có đúng target pay date không" trên mỗi lần fire còn lại.

## 4. Kích hoạt + chạy thử ngay

```bash
curl -X POST http://localhost:8080/api/tasks/start/<task-id>
curl -X POST http://localhost:8080/api/tasks/trigger-now/<task-id>
```

`trigger-now` vẫn đi qua toàn bộ logic của `PayrollJobAction`, bao gồm cả việc kiểm tra "hôm
nay có phải target pay date không" — nếu không phải, job chỉ log rồi bỏ qua và
`payroll_disbursement` sẽ không có dữ liệu mới. Muốn test end-to-end thật, chạy đúng vào ngày
mục tiêu hoặc tạm sửa ngày hệ thống.

## 5. Theo dõi kết quả

```sql
SELECT * FROM payroll_batch_run ORDER BY started_at DESC;
SELECT status, COUNT(*) FROM payroll_disbursement WHERE batch_run_id = <id> GROUP BY status;
SELECT balance FROM fpt_company_account WHERE company_code = 'FPT_SOFTWARE';
SELECT balance FROM gl_suspense_account WHERE account_code = 'PAYROLL_SUSPENSE_GL';
```

Log ứng dụng in các dòng `[BANK_SALARY_PAYROLL] holdFundsStep - ...`,
`[BANK_SALARY_PAYROLL] disburseStep - bỏ qua nhân viên ... ` (1 dòng / record bị skip), và
`[BANK_SALARY_PAYROLL] notifyStep - Kỳ lương ...` (thông báo giả lập, tổng kết cuối job).
`gl_suspense_account.balance` phải quay về 0 (hoặc rất gần 0, chỉ còn phần chênh lệch của các
record bị skip không được giải ngân) sau khi job chạy xong.
