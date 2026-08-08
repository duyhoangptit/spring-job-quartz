# Case Study: Thiết kế hệ thống Core Banking chịu tải 10,000 TPS

> Bài toán tổng hợp, dùng để đánh giá bạn đã tích hợp được toàn bộ kiến thức từ mục 01 → 13 hay chưa. Đây là dạng câu hỏi thường gặp trong system design interview cho vị trí Technical Lead/Architect tại các ngân hàng/fintech lớn.

---

## 1. Đề bài

Thiết kế hệ thống xử lý chuyển tiền nội bộ cho một ngân hàng số với các ràng buộc:
- **10,000 giao dịch/giây** ở giờ cao điểm (đầu tháng, giờ hành chính)
- **P99 latency < 200ms** cho giao dịch chuyển tiền
- **Zero data loss, zero double-spending** — tuyệt đối không được mất tiền hoặc trùng tiền
- **Audit trail đầy đủ** theo yêu cầu compliance (SBV/quốc tế tương đương)
- **99.99% uptime** (khoảng 52 phút downtime/năm)
- Hỗ trợ **mở rộng theo chiều ngang** khi lượng người dùng tăng

## 2. Phân tích bài toán theo từng layer (liên kết với các mục đã học)

### 2.1. Application Layer (mục 01, 02, 04)
- Dùng **Virtual Threads** (Java 21+) cho tầng service I/O-bound để phục vụ hàng chục nghìn connection đồng thời mà không cần thread pool khổng lồ theo kiểu truyền thống.
- Service **stateless hoàn toàn**, mọi state nằm ở DB/cache, cho phép scale ngang tự do.
- Transaction boundary ngắn gọn (`@Transactional` chỉ bao quanh phần ghi DB thuần, không bao gồm gọi network ra ngoài).

### 2.2. Data Layer (mục 03, 04)
- **Optimistic locking** (`@Version`) cho bảng `account` — tránh giữ pessimistic lock lâu làm giảm throughput ở mức 10k TPS.
- **Sharding theo `account_id`** nếu 1 instance PostgreSQL không đủ tải — cân nhắc Citus hoặc partition ở tầng application theo range/hash của account_id.
- Tách **read replica** cho các truy vấn sao kê/báo cáo, không để tải đọc ảnh hưởng tải ghi giao dịch.
- **Partition bảng transaction_log theo tháng**, giữ bảng active nhỏ gọn cho hiệu năng ghi tối ưu.

### 2.3. Concurrency & Consistency (mục 01, 03, 04, 12)
- **Idempotency key bắt buộc** ở API layer — client (hoặc gateway) gửi kèm `idempotency-key`, service cache kết quả xử lý theo key này (TTL vài giờ) để retry an toàn.
- **Atomic balance update**: `UPDATE account SET balance = balance - :amount, version = version + 1 WHERE id = :id AND balance >= :amount AND version = :version` — 1 câu SQL atomic thay vì đọc-tính-ghi 3 bước.
- Với giao dịch giữa 2 tài khoản: **Saga pattern** (orchestration) thay vì distributed transaction 2PC — vì 2PC không scale tốt ở mức 10k TPS do phải giữ lock xuyên suốt các participant.

### 2.4. Event-Driven & Audit (mục 06/12-appendix, 12)
- **Event Sourcing** cho `transaction-service`: mọi thay đổi số dư là 1 sự kiện immutable ghi vào **Transactional Outbox** trong cùng transaction với việc update balance — đảm bảo atomic giữa "ghi DB" và "phát sự kiện", tránh dual-write problem.
- Outbox poller (hoặc Debezium CDC) đẩy event lên **Kafka**, partition theo `account_id` để đảm bảo thứ tự xử lý trên cùng 1 tài khoản.
- Các service downstream (notification, fraud-detection, reporting) tiêu thụ event bất đồng bộ — không nằm trên critical path của giao dịch, giúp giữ P99 latency thấp.

### 2.5. Resilience (mục 06, 09)
- **Circuit Breaker** (Resilience4j) giữa các service — nếu `fraud-detection-service` chậm/lỗi, `transaction-service` vẫn xử lý giao dịch bình thường (đánh dấu cần review sau) thay vì block toàn bộ.
- **Bulkhead pattern**: cô lập connection pool/thread pool riêng cho từng dependency downstream, tránh 1 dependency chậm làm cạn kiệt tài nguyên toàn service.
- **Rate limiting đa tầng**: WAF → API Gateway (theo user/IP) → business logic (theo pattern hành vi bất thường).

### 2.6. Infrastructure & Deployment (mục 08, 09, 11)
- **Auto-scaling** (Kubernetes HPA) dựa trên custom metric (số request/giây, độ trễ queue Kafka consumer lag) thay vì chỉ CPU — vì banking traffic có pattern spike rõ rệt theo giờ, không đều.
- **Multi-AZ** cho cả compute (EKS node group) và database (RDS Multi-AZ) để đạt 99.99% uptime.
- **Canary deployment** cho `transaction-service` — service quan trọng nhất, rủi ro cao nhất khi deploy sai.
- Toàn bộ hạ tầng bằng **Terraform**, review qua Pull Request — tạo audit trail tự nhiên cho thay đổi hạ tầng.

### 2.7. Security & Compliance (mục 10)
- **mTLS** giữa mọi service nội bộ, **OAuth2 Client Credentials** cho service-to-service authentication.
- **Field-level encryption** cho dữ liệu định danh, key quản lý qua KMS riêng.
- Audit log **append-only**, tách biệt hoàn toàn khỏi quyền ghi của application service thông thường (chỉ có quyền INSERT, không có UPDATE/DELETE).

### 2.8. AI Layer (mục 13)
- **Fraud scoring bất đồng bộ**: sau khi giao dịch hoàn tất, event được gửi tới `fraud-detection-service` — kết hợp rule engine (chặn nhanh pattern rõ ràng) và mô hình AI cho anomaly detection phức tạp hơn, có **explainability** để đội vận hành hiểu vì sao 1 giao dịch bị gắn cờ.
- Không để AI nằm trên critical path của giao dịch chính (tránh AI latency ảnh hưởng SLA 200ms).

### 2.9. Business Logic Extensibility (mục 04b)
- **Fee/Interest Calculation** dùng **Strategy pattern** (`Map<AccountType, FeeStrategy>` auto-wire qua Spring) — thêm hạng tài khoản/sản phẩm mới không sửa code cũ, không cần deploy lại các phần khác.
- **Fraud rule pipeline** dùng **Chain of Responsibility + Specification pattern** (hoặc Drools nếu đội compliance cần tự sửa rule không qua dev) — tách rule khỏi code, có audit trail riêng cho mọi thay đổi rule (ai đổi, khi nào) tương tự audit trail giao dịch.
- **Payment Gateway integration** dùng **Abstract Factory + Adapter** — thêm đối tác thanh toán/ngân hàng mới chỉ cần thêm 1 adapter, không đụng service hiện có.
- Nguyên tắc chọn pattern: chỉ áp dụng nơi có **biến thiên nghiệp vụ thật sự và có khả năng tăng** (hạng tài khoản mới, đối tác mới, luật fraud mới) — tránh overengineering ở phần logic ổn định.

## 3. Sơ đồ luồng xử lý tổng quát

```
Client → API Gateway (rate limit, idempotency check)
       → transaction-service
            ├─ [sync, atomic] Update account balance (optimistic lock)
            ├─ [sync, same DB tx] Write to Outbox table
            └─ return response (< 200ms)

       Outbox Poller / Debezium CDC
            └─ Publish event → Kafka (partition by account_id)
                    ├─ notification-service (async)
                    ├─ fraud-detection-service (async, rule + AI)
                    └─ reporting-service (async, cập nhật read model CQRS)
```

## 4. Câu hỏi tự đánh giá (dùng để mock system design interview)

1. Nếu `fraud-detection-service` down hoàn toàn 10 phút, hệ thống có tiếp tục xử lý giao dịch được không? Đánh đổi gì?
2. Điểm nào trong luồng trên là **single point of failure**? Cách khắc phục?
3. Nếu 2 request cùng idempotency-key đến gần như đồng thời (race condition ở chính bước check-idempotency), thiết kế hiện tại có xử lý đúng không?
4. Khi cần scale từ 10k lên 50k TPS, layer nào sẽ là nút thắt cổ chai đầu tiên? (Gợi ý: thường là DB write, không phải application layer nhờ stateless + virtual threads).
5. Compensating transaction trong Saga thất bại thì sao? Thiết kế retry/dead-letter queue thế nào?

## 5. Gợi ý dùng Claude Code
Copy case study này vào `CLAUDE.md` của dự án `05-project-thuc-hanh`, sau đó yêu cầu Claude Code:
- Sinh sơ đồ kiến trúc chi tiết hơn cho từng service
- Review code hiện tại của bạn theo checklist "Điểm cần chú ý" ở mỗi README
- Sinh test case cho các câu hỏi tự đánh giá ở mục 4
