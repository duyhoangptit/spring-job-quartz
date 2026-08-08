# 06. Tích hợp hệ thống (Linking) — REST, Message Broker, API Gateway

> Ghi chú: "Linkin" trong lộ trình gốc được hiểu là **Linking/Integration** — giai đoạn kết nối các module/service riêng lẻ thành một hệ thống hoàn chỉnh, đặt giữa "Project" và "Testing" vì đây là lúc project từ 1 service tách thành nhiều service cần giao tiếp với nhau.

## Mục tiêu
Biết chọn đúng phương thức giao tiếp giữa các service (đồng bộ vs bất đồng bộ) và thiết kế contract rõ ràng giữa chúng.

## Kiến thức cốt lõi
- **Giao tiếp đồng bộ**: REST (OpenAPI spec-first), gRPC (khi cần latency thấp + strong typing giữa các service nội bộ).
- **Giao tiếp bất đồng bộ**: Kafka (event streaming, ordering theo partition key), RabbitMQ (message queue, routing linh hoạt) — hiểu khi nào dùng cái nào.
- **Outbox Pattern**: giải quyết bài toán "ghi DB + publish event" phải atomic — cực kỳ quan trọng trong banking để tránh mất event khi service crash giữa chừng.
- **API Gateway**: routing, rate limiting, request aggregation (Spring Cloud Gateway hoặc Kong).
- **Service Discovery**: Eureka/Consul hoặc Kubernetes-native (DNS-based) — hiểu trade-off.
- **Idempotency ở tầng tích hợp**: mọi API nhận tiền phải idempotent theo `idempotency-key`, vì network retry là chuyện bình thường, không phải ngoại lệ.
- **Circuit Breaker & Bulkhead** (Resilience4j): cô lập lỗi giữa các service để 1 service chết không kéo sập toàn hệ thống.

## Điểm cần chú ý
- **Dual-write problem**: ghi DB xong rồi publish message riêng lẻ (2 bước không atomic) là nguồn lỗi nghiêm trọng nhất trong hệ thống banking phân tán — luôn dùng Outbox Pattern hoặc Change Data Capture (Debezium).
- Đồng bộ hoá quá mức (service A gọi B gọi C gọi D trong 1 request) tạo ra latency cộng dồn và single point of failure ẩn — cân nhắc bất đồng bộ hoá những bước không cần phản hồi ngay.
- Thiếu idempotency key ở API nhận tiền → retry từ client hoặc gateway gây double charge.
- Circuit breaker cấu hình sai threshold có thể gây "cascading failure" ngược — quá nhạy sẽ cắt cả traffic bình thường.

## Ứng dụng vào Banking High-Concurrency
- `transaction-service` sau khi ghi transaction vào DB, publish event `TransactionCompleted` qua **Outbox Pattern + Kafka** để các service khác (notification, fraud-detection, reporting) xử lý bất đồng bộ mà không ảnh hưởng latency của giao dịch chính.
- Dùng **Kafka partition key = account_id** để đảm bảo thứ tự xử lý sự kiện trên cùng 1 tài khoản (tránh xử lý sai thứ tự gây lệch số dư).
- API Gateway enforce **idempotency-key bắt buộc** cho mọi endpoint ghi tiền, cache response theo key trong khoảng thời gian nhất định.

## Bài tập thực hành
Implement Outbox Pattern cho `transaction-service`: khi tạo transaction, ghi cả bản ghi transaction và outbox event trong cùng 1 DB transaction, sau đó dùng 1 poller (hoặc Debezium) đọc outbox và publish lên Kafka, đảm bảo at-least-once delivery.

## Tài nguyên
- "Designing Data-Intensive Applications" — chương Distributed Transactions & Consensus
- microservices.io — pattern: Transactional Outbox, Saga
