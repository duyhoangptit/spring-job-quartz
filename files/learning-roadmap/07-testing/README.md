# 07. Testing — Unit, Integration, Contract, Load Test

## Mục tiêu
Xây dựng test pyramid đầy đủ cho hệ thống phân tán, đủ tự tin deploy tự động mà không cần test thủ công.

## Kiến thức cốt lõi
- **Unit Test**: JUnit 5, Mockito — test business logic thuần, mock hết dependency ngoài (DB, service khác).
- **Integration Test**: `@SpringBootTest` + **Testcontainers** (chạy PostgreSQL/Kafka thật trong Docker khi test, không dùng H2 giả lập vì hành vi SQL khác nhau).
- **Contract Testing**: Pact hoặc Spring Cloud Contract — đảm bảo `account-service` và `transaction-service` không phá vỡ hợp đồng API của nhau khi mỗi team deploy độc lập.
- **Load/Performance Testing**: k6 hoặc Gatling — mô phỏng tải thực tế, đo P50/P95/P99 latency, throughput, tìm breaking point.
- **Chaos Testing** (nâng cao): mô phỏng service chết, network partition — quan trọng để verify circuit breaker/retry hoạt động đúng.
- **Test Data Management**: builder pattern cho test fixture, tránh test phụ thuộc lẫn nhau (test order independence).

## Điểm cần chú ý
- Dùng H2 in-memory thay PostgreSQL khi test là **anti-pattern nguy hiểm** trong banking — nhiều lỗi (isolation level, kiểu dữ liệu, index behavior) chỉ xuất hiện trên PostgreSQL thật. Luôn dùng Testcontainers.
- Test coverage cao (%) không đồng nghĩa test tốt — ưu tiên test các **kịch bản concurrency và edge case tài chính** (chuyển tiền âm, chuyển vượt hạn mức, 2 request trùng idempotency key) hơn là coverage đường thẳng.
- Thiếu contract test giữa các microservices khiến lỗi chỉ phát hiện ở integration environment hoặc production — quá muộn.
- Load test chỉ chạy 1 lần trước go-live rồi bỏ quên — nên đưa vào CI/CD chạy định kỳ để phát hiện performance regression sớm.

## Ứng dụng vào Banking High-Concurrency
- Viết test **concurrency-specific**: 100 thread cùng gọi API chuyển tiền với cùng idempotency-key → chỉ 1 giao dịch được thực hiện, 99 request còn lại nhận lại kết quả cached.
- Load test kịch bản **giờ cao điểm** (VD: đầu tháng lương) với traffic pattern thực tế (spike, không phải tải đều), xác định điểm nghẽn (DB connection pool, thread pool, hay network).
- Contract test đảm bảo khi `account-service` đổi field trong response, `transaction-service` (consumer) phát hiện breaking change ngay ở CI, trước khi deploy.

## Bài tập thực hành
Viết bộ test cho `transaction-service` gồm: unit test cho validate hạn mức, integration test với Testcontainers cho luồng chuyển tiền đầy đủ, và load test bằng k6 mô phỏng 1000 VU (virtual users) trong 5 phút, xuất báo cáo P99 latency.

## Tài nguyên
- Testcontainers official docs
- "xUnit Test Patterns" — Gerard Meszaros (cho phần thiết kế test tốt, không chỉ công cụ)
- k6 documentation — phần "Load testing patterns"
