Step 1: Trữ tiền tài khoản FPT Software (Tài khoản nguồn)

Nghiệp vụ: Kiểm tra số dư tài khoản của FPT Software có đủ để trả tổng lượng cho 30.000 người không. Nếu đủ, thực hiện khoảnh giữ số tiền (Hold/Block Amount) hoặc trừ thẳng vào tài khoản FPT và treo vào một tài khoản trung gian (Internal GL Account) của TPBank.

Lý do: Trảnh tính trạng đang trả lượng cho nhân viên giữa chúng thì tài khoản công ty bị hết tiền hoặc bị phong tỏa do một lý do khác.

Step 2: Giải ngân vào tài khoản 30.000 nhân viên (Chunk Processing)

Cấu hình Chunk size = 100 hoặc 500:
Hệ thống sẽ đọc và xử lý theo từng khối (vd: mỗi một 500 nhân viên).

Transaction Management: Nếu người thứ 499 trong Chunk bị lỗi (vd: tài khoản bị khóa), chỉ có 500 người trong Chunk đó bị rollback, các Chunk 500 người trước đó đã thành công và đã được commit vào DB. Spring Batch sẽ tự động đánh dấu và xử lý lại (Retry) hoặc bỏ qua (Skip) người lỗi để chạy tiếp, không làm ảnh hưởng đến 29.500 người còn lại.

Phân tích: Đây là một thiết kế xử lý batch khối lượng lớn với cơ chế:

Trữ tiền trước — đảm bảo fund availability trước khi bắt đầu giải ngân
Chunk processing — xử lý theo batch nhỏ để quản lý memory + transaction scope
Transactional isolation — lỗi chỉ ảnh hưởng Chunk hiện tại, không ảnh hưởng những Chunk đã hoàn tất
Retry/Skip logic — tự động recovery cho những case lỗi không phải retry được

[Job: FPT_Payroll_Job]
│
├── Step 1: Kiểm tra & Trừ tiền FPT Software (Single Tasklet)
│      └── Thành công -> Chuyển sang Step 2
│
└── Step 2: Giải ngân cho 30.000 Nhân viên (Chunk-oriented: Reader -> Processor -> Writer)
├── Reader: Đọc danh sách 30.000 dòng từ File/DB tạm
├── Processor: Kiểm tra hợp lệ, mã hóa dữ liệu, tạo log giao dịch
└── Writer: Ghi nhận cộng tiền vào tài khoản và trigger hệ thống thông báo số dư