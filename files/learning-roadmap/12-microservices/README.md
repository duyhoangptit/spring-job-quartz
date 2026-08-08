# 12. Microservices Patterns — Saga, CQRS, Event Sourcing

## Mục tiêu
Thiết kế hệ thống banking phân tán mà vẫn giữ được tính nhất quán dữ liệu (consistency) dù không dùng distributed transaction (2PC) truyền thống.

## Kiến thức cốt lõi
- **Saga Pattern**: chuỗi transaction cục bộ, mỗi bước có compensating transaction để rollback logic khi bước sau thất bại.
  - **Choreography-based Saga**: các service tự lắng nghe event và phản ứng, không có nhạc trưởng — đơn giản nhưng khó theo dõi luồng khi hệ thống lớn.
  - **Orchestration-based Saga**: có 1 orchestrator điều phối rõ ràng từng bước — dễ theo dõi, dễ debug hơn cho quy trình phức tạp như chuyển tiền liên ngân hàng.
- **CQRS (Command Query Responsibility Segregation)**: tách model ghi (command) khỏi model đọc (query) — cho phép scale độc lập, tối ưu riêng cho từng loại tải (banking: ghi giao dịch ít field, đọc sao kê cần join nhiều bảng).
- **Event Sourcing**: lưu trạng thái dưới dạng chuỗi sự kiện thay vì chỉ lưu trạng thái hiện tại — cho phép **audit trail tự nhiên tuyệt đối** (rất phù hợp yêu cầu compliance banking) và replay lại trạng thái tại bất kỳ thời điểm nào.
- **Eventual Consistency**: chấp nhận dữ liệu giữa các service không đồng bộ tức thì, thiết kế UI/UX và business logic phù hợp (VD: hiển thị trạng thái "đang xử lý").
- **Domain-Driven Design (DDD) cơ bản**: Bounded Context để xác định ranh giới service hợp lý — sai lầm phổ biến là chia service theo tầng kỹ thuật (UI/Service/DB) thay vì theo domain nghiệp vụ.

## Điểm cần chú ý
- Saga compensating transaction **không phải rollback thật** — nó là 1 transaction bù trừ mới (VD: hoàn tiền là 1 giao dịch mới, không phải "undo"), phải thiết kế idempotent và tự chịu được lỗi khi compensating transaction cũng thất bại.
- Event Sourcing tăng độ phức tạp đáng kể — chỉ áp dụng cho phần thực sự cần audit trail nghiêm ngặt (VD: `transaction-service`), không áp dụng tràn lan cho mọi service (VD: `notification-service` không cần).
- CQRS không đồng nghĩa phải tách database vật lý ngay từ đầu — có thể bắt đầu bằng tách model trong cùng DB, tách hạ tầng khi thực sự cần scale riêng.
- Chia microservices sai ranh giới (quá nhỏ, phụ thuộc chằng chịt) tạo ra "distributed monolith" — tệ hơn cả monolith vì vừa phức tạp vận hành vừa không độc lập deploy được.

## Ứng dụng vào Banking High-Concurrency
- **Saga Orchestration** cho luồng chuyển tiền liên ngân hàng: `reserve fund` (account-service) → `call external bank API` (interbank-service) → `confirm/compensate` — mỗi bước có timeout và compensating action rõ ràng.
- **Event Sourcing** cho `transaction-service`: mọi thay đổi số dư là 1 event immutable (`MoneyDeposited`, `MoneyWithdrawn`), số dư hiện tại là kết quả replay/aggregate các event — đáp ứng yêu cầu audit tuyệt đối của banking.
- **CQRS**: command side (ghi giao dịch) dùng PostgreSQL tối ưu ghi; query side (sao kê, dashboard) dùng read model denormalized (có thể là Elasticsearch hoặc materialized view) cập nhật bất đồng bộ qua event — giúp API sao kê không làm chậm giao dịch chính dù có hàng nghìn request đọc đồng thời.

## Bài tập thực hành
Thiết kế và implement Saga (orchestration-based) cho luồng "chuyển tiền liên ngân hàng" gồm 3 bước với 1 bước cố tình fail để test compensating transaction; sau đó thiết kế read model riêng cho API sao kê, cập nhật qua Kafka consumer.

## Tài nguyên
- microservices.io (Chris Richardson) — nguồn gốc các pattern này, đọc trực tiếp
- "Building Event-Driven Microservices" — Adam Bellemare
- "Domain-Driven Design" — Eric Evans (ít nhất đọc phần Bounded Context)
