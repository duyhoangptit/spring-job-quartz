# 08. Docker & Container — Image, Layer, Multi-stage Build

## Mục tiêu
Đóng gói service Java thành image tối ưu (nhỏ, an toàn, khởi động nhanh) — quan trọng cho hệ thống cần scale nhanh theo tải.

## Kiến thức cốt lõi
- **Multi-stage build**: tách build stage (Maven/Gradle) khỏi runtime stage để image cuối không chứa toolchain build.
- **Base image lựa chọn**: `eclipse-temurin` JRE (không phải JDK) cho runtime, hoặc distroless image để giảm attack surface.
- **Layer caching**: thứ tự COPY trong Dockerfile ảnh hưởng cache — copy `pom.xml`/`build.gradle` trước, download dependency, rồi mới copy source code.
- **JVM trong container**: hiểu `-XX:+UseContainerSupport` (mặc định từ Java 10+), container-aware memory limit — tránh OOMKilled do JVM không nhận đúng memory limit của container.
- **Health check**: `HEALTHCHECK` trong Dockerfile hoặc dựa vào Actuator endpoint, tích hợp với orchestrator.
- **Image scanning**: Trivy/Grype để scan lỗ hổng bảo mật — bắt buộc trong pipeline CI của hệ thống banking.

## Điểm cần chú ý
- Image build từ JDK full thay vì JRE làm image nặng không cần thiết và tăng attack surface — banking cần tối thiểu hoá attack surface theo nguyên tắc least privilege.
- Chạy container với user `root` mặc định là lỗ hổng bảo mật phổ biến — luôn tạo non-root user trong Dockerfile.
- Quên set memory limit hợp lý cho JVM trong container → JVM cố dùng hết memory host, bị orchestrator kill (OOMKilled) một cách khó hiểu nếu không biết nguyên nhân.
- Bake secret (DB password, API key) vào image là lỗi bảo mật nghiêm trọng — luôn inject qua environment variable hoặc secret manager tại runtime.

## Ứng dụng vào Banking High-Concurrency
- Image nhỏ (JRE + distroless) giúp **auto-scaling nhanh hơn** khi traffic tăng đột biến (giờ cao điểm giao dịch) — thời gian pull image và cold start ảnh hưởng trực tiếp đến khả năng đáp ứng tải.
- Health check chuẩn (readiness vs liveness probe riêng biệt) đảm bảo orchestrator không route traffic vào instance chưa sẵn sàng nhận giao dịch, tránh lỗi giao dịch ở giai đoạn khởi động.
- Image scanning trong CI là một phần của **compliance requirement** (PCI-DSS) đối với hệ thống xử lý thông tin tài chính.

## Bài tập thực hành
Viết multi-stage Dockerfile cho `transaction-service`: stage build bằng Maven, stage runtime dùng JRE distroless, non-root user, health check qua Actuator `/actuator/health`, so sánh kích thước image trước/sau tối ưu.

## Tài nguyên
- Docker official docs — "Multi-stage builds"
- "Container Security" — Liz Rice (đặc biệt phần non-root, capabilities)
