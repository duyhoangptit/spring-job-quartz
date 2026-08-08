# 02. Spring Boot — DI/IoC, Auto-configuration, Actuator

## Mục tiêu
Hiểu Spring Boot "bên trong hộp đen" để debug được khi auto-configuration không hoạt động như mong đợi, và để thiết kế module hợp lý cho hệ thống banking.

## Kiến thức cốt lõi
- **IoC Container & Bean Lifecycle**: `BeanFactory` → `ApplicationContext`, các bean scope (`singleton`, `prototype`, `request`), thứ tự khởi tạo (`@PostConstruct`, `InitializingBean`, `@DependsOn`).
- **Dependency Injection**: constructor injection (khuyến nghị) vs field injection — vì sao constructor injection giúp test dễ hơn và tránh circular dependency ẩn.
- **Auto-configuration**: cơ chế `@ConditionalOnClass`, `@ConditionalOnMissingBean`, cách Spring Boot Starter hoạt động — quan trọng khi bạn cần viết custom starter cho các service dùng chung trong tổ chức (VD: `banking-audit-starter`).
- **AOP (Aspect-Oriented Programming)**: dùng cho cross-cutting concerns như logging, audit trail, transaction — bắt buộc trong banking để tách business logic khỏi compliance logic.
- **Spring Boot Actuator**: health check, metrics (`/actuator/prometheus`), custom `HealthIndicator` — nền tảng cho observability.
- **Profiles & externalized config**: `application-{profile}.yml`, `@ConfigurationProperties`, Spring Cloud Config cho microservices.

## Điểm cần chú ý
- **Field injection (`@Autowired` trên field)** làm class khó test và che giấu dependency thật sự — dùng constructor injection triệt để, đặc biệt trong service xử lý tiền.
- Circular dependency giữa các `@Service` là dấu hiệu thiết kế sai, không phải vấn đề kỹ thuật cần "workaround" bằng `@Lazy`.
- Đừng lạm dụng `@ComponentScan` phạm vi rộng trong monorepo nhiều module — dễ load nhầm bean, đặc biệt nguy hiểm khi module audit/security bị load sai context.
- Bean singleton mặc định + service không stateless = race condition khó phát hiện trong môi trường concurrent (banking service tuyệt đối phải stateless).

## Ứng dụng vào Banking High-Concurrency
- Dùng AOP để implement **audit trail bắt buộc theo quy định** (mọi thay đổi số dư phải được log immutable) mà không làm bẩn business logic.
- Actuator health check tích hợp với load balancer để tự động loại bỏ instance không khỏe ra khỏi traffic — quan trọng khi cần zero-downtime trong hệ thống 24/7.
- `@ConfigurationProperties` + Spring Cloud Config để thay đổi tham số nghiệp vụ (VD: hạn mức giao dịch/ngày) mà không cần deploy lại.

## Bài tập thực hành
Viết `@Aspect` tự động ghi audit log (ai, khi nào, thay đổi gì) cho mọi method trong `AccountService` có annotation `@Auditable`, output ra bảng `audit_log` riêng biệt append-only.

## Tài nguyên
- Spring Framework Reference Docs — phần "Core Technologies" (đọc gốc, không đọc tutorial)
- "Spring in Action" (bản mới nhất) — chương IoC & AOP
