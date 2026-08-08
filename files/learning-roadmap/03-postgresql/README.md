# 03. PostgreSQL — Indexing, Isolation Level, Locking

## Mục tiêu
Thiết kế schema và query chịu được tải OLTP cao, hiểu rõ transaction isolation để tránh lỗi tài chính (mất tiền, trùng tiền).

## Kiến thức cốt lõi
- **Indexing**: B-Tree (mặc định), Hash, GIN/GiST, partial index, covering index (`INCLUDE`). Đọc `EXPLAIN ANALYZE` thành thạo — phân biệt Seq Scan / Index Scan / Index Only Scan / Bitmap Heap Scan.
- **Transaction Isolation Levels**: `READ COMMITTED` (mặc định) → `REPEATABLE READ` → `SERIALIZABLE`. Hiểu rõ **Dirty Read, Non-repeatable Read, Phantom Read, Lost Update** — banking thường cần tối thiểu `REPEATABLE READ` cho giao dịch chuyển tiền.
- **Locking**: Row-level lock (`SELECT ... FOR UPDATE`), Advisory Lock, Deadlock detection & retry strategy.
- **MVCC (Multi-Version Concurrency Control)**: vì sao PostgreSQL không block đọc khi đang ghi, và hệ quả (bloat, cần `VACUUM`).
- **Connection Pooling**: PgBouncer — transaction pooling mode vs session mode, vì sao cần thiết khi có hàng nghìn connection đồng thời từ nhiều service instance.
- **Partitioning**: range partitioning theo thời gian cho bảng transaction log (hàng trăm triệu dòng/năm trong banking).

## Điểm cần chú ý
- **Lost Update** là lỗi kinh điển trong banking: đọc số dư → tính toán ở application → ghi lại, giữa 2 bước có transaction khác ghi đè. Giải pháp: `SELECT FOR UPDATE`, optimistic locking (`@Version`), hoặc atomic update (`UPDATE account SET balance = balance - ? WHERE id = ? AND balance >= ?`).
- Index không phải luôn tốt — mỗi index làm chậm INSERT/UPDATE và tốn storage; với bảng transaction ghi liên tục, cần cân nhắc kỹ index nào thực sự cần cho query pattern.
- `SERIALIZABLE` an toàn nhất nhưng có thể gây serialization failure cần retry ở application — đừng chọn mức isolation cao nhất mặc định mà không đo hiệu năng.
- Quên connection pooling khi scale service theo horizontal → PostgreSQL max_connections bị vượt, toàn hệ thống sập dây chuyền.
- Long-running transaction giữ lock quá lâu là nguyên nhân phổ biến gây nghẽn ở giờ cao điểm.

## Ứng dụng vào Banking High-Concurrency
- Thiết kế bảng `account_balance` với **optimistic locking** (`version` column) cho update tần suất cao, kết hợp retry với exponential backoff ở tầng application.
- Bảng `transaction_log` **append-only, partition theo tháng**, index theo `account_id + created_at` để truy vấn sao kê nhanh mà không ảnh hưởng bảng balance đang hot.
- Dùng `SELECT ... FOR UPDATE SKIP LOCKED` cho hàng đợi xử lý giao dịch bất đồng bộ (outbox pattern) để nhiều worker xử lý song song không giẫm chân nhau.

## Bài tập thực hành
Mô phỏng 100 request chuyển tiền đồng thời giữa 2 tài khoản, so sánh 3 cách xử lý: (1) không có cơ chế bảo vệ (sẽ ra lỗi), (2) `SELECT FOR UPDATE`, (3) optimistic locking với retry — đo throughput và tỷ lệ lỗi retry của từng cách.

## Tài nguyên
- PostgreSQL Official Docs — chương "Concurrency Control" (đọc gốc)
- "Designing Data-Intensive Applications" — Martin Kleppmann, chương Transactions
