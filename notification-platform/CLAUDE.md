# CLAUDE.md — Notification Platform

> File này là **nguồn context chính** cho Claude Code khi sinh source code cho dự án.
> Mọi quyết định kiến trúc, schema, naming convention đã được chốt tại đây — khi generate code, bám sát đúng những gì mô tả dưới đây, không tự ý đổi tên bảng/topic/package đã quy ước.

---

## 0. Trạng thái code hiện tại & Lệnh thường dùng

> Đọc mục này trước phần thiết kế bên dưới — nó nói rõ code hiện có khác gì so với thiết kế đích (mục 1–14), và cách build/test/run repo Maven đa module đang có.

### 0.1 Code hiện tại vs. tài liệu thiết kế

Mục 1–14 bên dưới là **thiết kế đích** cho hệ thống Notification Platform đầy đủ (multi-channel Kafka retry/DLQ, budget theo client, template MongoDB, circuit breaker đa provider...). Code trong repo triển khai thiết kế đó dần theo **11 sub-project** (thứ tự ở mục 14); xem `docs/superpowers/specs/` và `docs/superpowers/plans/` cho spec/plan chi tiết của từng sub-project đã/đang làm.

Repo từng có một starter vertical-slice cũ không khớp thiết kế (`accept → outbox → dispatch → email`, bảng `notification`/`notification_outbox`, package `request/`, `delivery/`) — slice đó **đã bị xoá** ở commit `1519052 refactor: remove obsolete accept/dispatch-email flow` cùng lúc schema Postgres được thay bằng đúng 5 bảng ở mục 7.1 (`ea9911a feat(schema): replace Postgres schema...`). **Sub-project 1** (domain aggregate + `port/out` + Postgres/Mongo adapter + Liquibase schema, spec: `docs/superpowers/specs/2026-08-22-sub-project-1-entity-repository-design.md`) đã xong (5 commit `feat(persistence)/feat(domain)/feat(schema)` gần nhất). Kết quả: package/module layout vẫn khác mục 5 (`gateway-api/`, `core/`, `provider-sms/`, `kafka/listener`, `admin/`... chưa tồn tại), nhưng **tên bảng, tên cột, tên method port** đã khớp đúng mục 4/7 — không tự ý đổi khi build tiếp sub-project 2 trở đi. Sub-project #2 (`core/template` cache 2 tầng) đã xong: `TemplateService` (`com.platform.notification.application.template`, Caffeine → Redis → Mongo read-through với write-through population khi miss), `TemplateRenderer` (fail-fast render placeholder), `TemplateAdminService` (write path, chỉ evict Redis L2 khi save), port mới `TemplateCacheRepository`, adapter mới `RedisTemplateCacheRepositoryAdapter` (`com.platform.notification.adapter.redis`, package mới), domain type mới `TemplateKey` và `RenderedContent`, và 2 exception mới `TemplateNotFoundException`/`MissingTemplateVariableException` (`com.platform.notification.exception.nonretriable`, package mới) — spec: `docs/superpowers/specs/2026-08-23-sub-project-2-template-cache-design.md`.

**Quan trọng — code hiện tại chưa chạy được nghiệp vụ end-to-end nào:** không có REST controller, không có Kafka listener, không có `port/in` nào được gọi từ bên ngoài (`notification-application` vẫn chưa có use case interface cho REST/Kafka, nhưng từ sub-project #2 đã có core service nội bộ `application.template.*` — xem trên), không có logic gửi SMS/Email/Push. Toàn bộ code hiện có là **nền persistence** (sub-project #1: domain aggregate + repository port + adapter + Liquibase) cộng **cache template 2 tầng** (sub-project #2) — nền tảng cho sub-project 3 trở đi (provider, Kafka, gateway-api...), không phải luồng end-to-end.

Version vẫn lệch so với mục 2: `pom.xml` gốc khai báo **Spring Boot 3.5.0** + **Spring Modulith 1.4.0**, không phải Spring Boot 4.1 — luôn kiểm tra `pom.xml` thay vì tin theo bảng version ở mục 2.

### 0.2 Build / Test / Run

Maven multi-module, Java 21. Thứ tự dependency trong reactor: `notification-contracts` → `notification-domain` → `notification-application` → (`notification-adapter-postgres`, `notification-adapter-kafka`, `notification-adapter-aws`, `notification-adapter-redis`, `notification-adapter-mongo`, `test-support`) → `apps/notification-app`. Chạy mọi lệnh từ thư mục gốc repo.

| Việc cần làm | Lệnh |
|---|---|
| Build + test toàn bộ | `mvn clean install` |
| Build, bỏ qua test | `mvn clean install -DskipTests` |
| Test toàn bộ | `mvn test` |
| Test 1 module (kèm module phụ thuộc) | `mvn -pl libs/notification-domain -am test` |
| Test 1 class | `mvn -pl libs/notification-domain -am test -Dtest=NotificationRequestTest` |
| Test 1 method | `mvn -pl libs/notification-domain -am test -Dtest=NotificationRequestTest#shouldFollowHappyPath` |
| Chạy app Spring Boot local | `mvn -pl apps/notification-app -am spring-boot:run` |
| Package jar chạy được | `mvn -pl apps/notification-app -am package` |

Mọi integration test (`apps/notification-app/src/test/.../persistence/*IT.java`, `.../template/*IT.java`) kế thừa `support.AbstractIntegrationTest`, tự spin Postgres 16 + Mongo 7 qua Testcontainers (`@ServiceConnection` tự trỏ datasource/mongo URI, không cần cấu hình tay) — không cần Postgres/Mongo chạy sẵn trên máy. Chỉ khi **chạy app thật** (`spring-boot:run`) mới cần hạ tầng sẵn có: `docker-compose.yml` ở root dựng Postgres (`localhost:5432`, db/user/pass = `notification`), MongoDB (`localhost:27017/notification`), Redis (`localhost:6379`), Kafka (`localhost:9092`), MinIO (`localhost:9000`) — chạy `docker compose up -d`. Hiện tại chỉ Postgres + Mongo + Redis thực sự được code dùng tới (qua `application.yml`, Redis từ sub-project #2 cho template cache tầng 2); Kafka/MinIO đã có sẵn trong compose cho sub-project sau nhưng chưa có code nào gọi tới. Kafka listener tắt mặc định (`notification.kafka.enabled=false`) — cờ này hiện **chưa gate gì cả** vì `notification-adapter-kafka` chưa có class Java nào (chỉ có `pom.xml`). Schema Postgres quản lý bằng Liquibase (`apps/notification-app/src/main/resources/db/changelog/`), Hibernate set `ddl-auto: validate` — muốn đổi schema phải thêm changeset Liquibase mới, không sửa tay DB rồi để Hibernate tự validate qua.

**Quan trọng — `mvn test` KHÔNG chạy `*IT.java`:** `*IT.java` chạy qua `maven-failsafe-plugin`, bind vào phase `integration-test`/`verify` — không phải `test` (Surefire chỉ chạy `*Test.java`). Muốn chạy IT phải dùng `mvn verify` hoặc `mvn clean install` (chạy xuyên qua `verify`); `mvn test` chỉ chạy unit test. `support.AbstractIntegrationTest` dùng Testcontainers theo pattern "singleton container" — Postgres/Mongo/Redis (Redis thêm từ sub-project #2 cho `TemplateCacheRepositoryIT`) được khởi tạo một lần trong khối `static {}` (không dùng annotation `@Testcontainers`/`@Container`) — nên cùng 3 container này sống xuyên suốt mọi IT class trong 1 fork Maven, dùng chung Spring `ApplicationContext` cache thay vì start/stop container lặp lại cho từng class.

### 0.3 Kiến trúc code thật (Clean Architecture / Hexagonal — mỗi layer là 1 module Maven riêng)

- `libs/notification-domain` — không phụ thuộc Spring/framework nào. Aggregate + state machine: `NotificationRequest` (`NotificationRequestStatus`: `PENDING → SUCCESS`/`FAILED_PERMANENT`/`IN_DLQ`, mọi transition chỉ hợp lệ từ `PENDING`, qua `markSuccess()`/`markFailedPermanent()`/`markInDlq()`); `NotificationDlq` (`DlqStatus`: `PENDING → REPROCESSING → RESOLVED`, hoặc `PENDING → ABANDONED`, qua `startReprocessing()`/`resolve()`/`reprocessFailed()`/`abandon()`, `reprocessCount` tự tăng ở `startReprocessing()`). Value object immutable: `NotificationAttempt` (chỉ có factory method `success()`/`failed()`/`timeout()`/`circuitOpen()`, không có setter — khớp nguyên tắc insert-only mục 3.8/12), `ClientBudgetConfig` (record), `TemplateContent` (record, validate `body` không blank). Mutable đơn giản: `UserChannelSubscription` (`enable()`/`disable()`), `NotificationTemplate`. Enum: `Channel`, `NotificationType`, `Priority`, `Period`, `DlqStatus`, `NotificationRequestStatus`. Sub-project #2 thêm value object `TemplateKey` (định danh cache/lookup theo `templateCode`+`channel`+`locale`) và `RenderedContent`, cùng package exception mới `com.platform.notification.exception.nonretriable` (`TemplateNotFoundException`, `MissingTemplateVariableException`) — package `exception.retriable`/`nonretriable` ở mục 12 giờ đã bắt đầu có class thật, không chỉ là quy ước trên giấy.
- `libs/notification-application` — vẫn **chưa có `port/in`** (chưa có use case interface nào được REST/Kafka gọi vào), nhưng từ sub-project #2 đã có core service nội bộ ở `application.template`: `TemplateService` (Caffeine L1 → Redis L2 → Mongo L3 read-through, write-through populate cache khi miss), `TemplateRenderer` (render placeholder `{{xxx}}`, fail-fast khi thiếu biến), `TemplateAdminService` (write path — lưu template, chỉ evict Redis L2, **không** invalidate Caffeine L1 của pod khác — xem ghi chú giới hạn cross-pod bên dưới). `port/out` gồm: `NotificationRequestRepository`, `NotificationAttemptRepository` (`save()` + `existsSuccessfulAttempt()` + `findByRequestId()`, không có `update()`), `NotificationDlqRepository` (`findByIdForUpdate` — pessimistic lock cho reprocess), `UserChannelSubscriptionRepository`, `ClientBudgetConfigRepository`, `NotificationTemplateRepository`, `ClockPort`, và port mới `TemplateCacheRepository` (sub-project #2, đọc/ghi cache tầng 2 Redis). Test đầu tiên của module này cũng tới từ sub-project #2: `TemplateServiceTest`, `TemplateAdminServiceTest`, `TemplateRendererTest` (`application.template`) và `TemplateKeyTest`/`TemplateExceptionsTest` (`notification-domain`) — dùng `org.assertj:assertj-core` + 2 fake in-memory tự viết (`InMemoryTemplateCacheRepository`, `InMemoryNotificationTemplateRepository`), không dùng Mockito.
- `libs/notification-adapter-postgres` — JPA entity + `SpringData*Repository` + `Jpa*RepositoryAdapter` cho 5 port ở trên (trừ template). `payload`/`provider_response` (jsonb) map qua `String` field, serialize/deserialize thủ công bằng `ObjectMapper` trong adapter (không dùng Hibernate JSON type). `JpaNotificationDlqRepositoryAdapter.findByIdForUpdate` dùng pessimistic lock ở tầng `SpringDataNotificationDlqRepository`.
- `libs/notification-adapter-mongo` — `MongoNotificationTemplateRepositoryAdapter` implement `NotificationTemplateRepository`; `NotificationTemplateDocument` (package-private) map đúng `_id = tpl_{templateCode}_{channel}_{locale}` như mục 7.2.
- `libs/notification-adapter-aws` — mới chỉ có `AwsAdapterConfiguration` (bean `SesV2Client` điều kiện `notification.provider.mode=ses`) + `SesProperties`; **chưa có** class gửi email nào (không có `EmailProvider`/`LogEmailProvider`).
- `libs/notification-adapter-kafka` — chỉ có `pom.xml`, **0 class Java**; đã lên reactor và đã là dependency của `apps/notification-app` nhưng chưa implement gì.
- `libs/notification-adapter-redis` — không còn rỗng từ sub-project #2: `TemplateCacheProperties`, `RedisAdapterConfiguration`, `TemplateCacheEntry`, `RedisTemplateCacheRepositoryAdapter` (implement `TemplateCacheRepository`, package `com.platform.notification.adapter.redis`, package mới) — cache tầng 2 cho template.
- `libs/notification-contracts` — `EmailRequestedEvent`, `NotificationStatusChangedEvent`: DTO còn sót lại từ slice cũ đã xoá (field `tenantId`/`businessKey`/`templateCode` không khớp `NotificationRequest` hiện tại), hiện **không có code nào tham chiếu tới**.
- `apps/notification-app` — `NotificationApplication` (`@SpringBootApplication` trơn), `delivery/package-info.java` (đánh dấu 1 Spring Modulith module "Notification Delivery" nhưng package này hiện **rỗng**, chưa có class), `application.yml`, Liquibase changelog (1 changeset tạo đúng 5 bảng mục 7.1, **chưa partition theo `business_date`** — có comment trong SQL nói rõ việc này để dành cho sub-project 11). Test nằm ở package `persistence/` (`NotificationRequestRepositoryIT`, `NotificationAttemptRepositoryIT`, `NotificationDlqRepositoryIT`, `ConfigRepositoriesIT` cho subscription+budget, `NotificationTemplateRepositoryIT`, `NotificationPlatformSchemaIT` kiểm tra đủ 5 bảng tồn tại), `template/` (`TemplateCacheRepositoryIT` — IT mới của sub-project #2 cho `RedisTemplateCacheRepositoryAdapter`, cũng qua Testcontainers Redis) và `support/` (`AbstractIntegrationTest`, `ApplicationContextSmokeTest`).
- `test-support` (artifactId Maven là `notification-test-support`, thư mục vẫn là `test-support/`) — `MutableClock` implement `ClockPort`; hiện là nơi duy nhất dùng `ClockPort` vì chưa có service nào trong `notification-application` cần đến nó.

**Giới hạn đã biết, chấp nhận tạm thời (sub-project #2):** `TemplateAdminService.save()` chỉ evict cache tầng 2 (Redis) của chính pod đang xử lý request admin — **không** invalidate Caffeine tầng 1 (in-process) của các pod khác. Trade-off này là chủ đích (spec §9), không phải bug: cửa sổ stale tối đa bị chặn bởi TTL Caffeine 10 phút (mục 8). Việc invalidate cross-pod đúng nghĩa (Redis Pub/Sub, `TemplateInvalidationPublisher`/`Subscriber`) để dành cho sub-project #8.

Bảng `notification_attempt`/`notification_dlq` và document `notification_template` (mục 4/7) **đã có schema + repository CRUD** (sub-project 1); template giờ đã có thêm cache 2 tầng + render (sub-project 2). Vẫn **chưa có**: circuit breaker, SMS/Push/Email provider thật, budget check, Kafka listener, gateway-api, admin reprocess (mục 5–11) — đây là phần cần build tiếp theo lộ trình sub-project 3–11 ở mục 14.

---

## 1. Tổng quan dự án

**Tên:** Notification Platform
**Domain:** Banking
**Mục tiêu:** Hệ thống gửi thông báo đa kênh (SMS, Email, Firebase Push) cho OTP, biến động số dư, cảnh báo bảo mật (đăng nhập thiết bị lạ), và broadcast marketing/chính sách. Có quản lý template tập trung, kiểm soát budget theo client nội bộ, retry + DLQ tự động qua Kafka, cho phép admin reprocess thủ công.

**Target scale:** 5,000–10,000 CCU (concurrent active users trên toàn hệ thống ngân hàng). Throughput thực tế cần thiết kế cho nhóm Transactional (OTP + balance-change) là **~1,000–1,500 msg/s ở giờ cao điểm** — đây là con số dùng để size hạ tầng (Kafka partitions, consumer pool, connection pool), không phải 10,000 msg/s. Broadcast (marketing/policy) không tính vào SLA real-time, xử lý theo batch.

---

## 2. Tech Stack & Version

| Thành phần | Công nghệ | Version | Ghi chú |
|---|---|---|---|
| Framework | Spring Boot | 4.1 | |
| Ngôn ngữ | Java | 21 | Bật **Virtual Threads** (`spring.threads.virtual.enabled=true`) cho các tác vụ I/O-bound (gọi 3rd party) |
| Message broker | Apache Kafka | — | Retry/DLQ dùng Spring Kafka `@RetryableTopic` (non-blocking retry) |
| Cache / ephemeral store | Redis | — | Cache 2 tầng (Local Caffeine + Redis), Pub/Sub, Streams, budget counter |
| Relational DB | PostgreSQL | — | Nguồn sự thật cho request/attempt/DLQ/budget/subscription |
| Document DB | MongoDB | — | Lưu template (schema linh hoạt, versioned) |
| Object storage | MinIO | — | Banner khuyến mãi, attachment email |
| Resilience | Resilience4j | — | Circuit Breaker + TimeLimiter per-provider |
| Local cache | Caffeine | — | Cache template tầng 1, trong JVM |

**Không dùng:** Redis Streams để thay thế Kafka làm nguồn durable nghiệp vụ. Không dùng Pub/Sub cho việc cần đảm bảo delivery.

---

## 3. Nguyên tắc kiến trúc (non-negotiable)

1. **Kafka là nguồn durable chính** cho toàn bộ luồng gửi + retry + DLQ. Redis chỉ hỗ trợ cache/budget/broadcast phụ trợ, không bao giờ là nơi lưu trữ business event chính thức.
2. **Non-blocking retry**: không sleep/block trong consumer. Dùng Spring Kafka `@RetryableTopic` với backoff qua topic riêng.
3. **Phân loại lỗi retriable / non-retriable rõ ràng** ngay từ exception hierarchy — non-retriable (sai input) không bao giờ được retry.
4. **OTP luôn fail-fast** — không qua flow retry chuẩn của balance-change/email (OTP hết hạn nhanh, retry vô nghĩa).
5. **Budget check xảy ra ở Gateway API, trước khi publish Kafka** — không publish rồi mới reject ở consumer.
6. **OTP/security notification không bao giờ bị chặn bởi budget của client khác** — tách quota theo `priority_tier`, không dùng chung bucket.
7. **Idempotency guard 2 lớp** cho mọi luồng reprocess: (a) trước khi publish lại ở service admin, (b) trong consumer trước khi gọi 3rd party.
8. **`notification_attempt` là bảng immutable** — chỉ INSERT, không UPDATE. Đây là audit trail phục vụ compliance.
9. **Không nhúng binary vào Kafka message** — asset (banner, attachment) lưu MinIO, message chỉ mang reference key.
10. **Không over-provision tài nguyên** — xem mục 11 (Capacity Planning) trước khi cấu hình pool/thread/partition.

---

## 4. Domain Model

### 4.1 Notification Types

| Type | Nhóm | Kênh | Cho phép user tắt? | Retry chuẩn? |
|---|---|---|---|---|
| `OTP` | Transactional | SMS, EMAIL | Không | Không — fail-fast |
| `BALANCE_CHANGE` | Transactional | SMS, EMAIL, PUSH | Có (theo subscription) | Có (3 lần) |
| `NEW_DEVICE_LOGIN` | Transactional (security) | PUSH, EMAIL | Không | Có (3 lần) |
| `PROMOTION` | Broadcast | PUSH, EMAIL | Có | Có (3 lần, xử lý batch) |
| `POLICY_ANNOUNCEMENT` | Broadcast | PUSH, EMAIL | Có | Có (3 lần, xử lý batch) |

### 4.2 Channel

Enum `Channel { SMS, EMAIL, PUSH }`

### 4.3 Client nội bộ (caller)

`client_id` ví dụ: `core-banking`, `auth-service`, `marketing-service` — dùng để tính budget, KHÔNG phải end-user.

---

## 5. Module / Package Structure (canonical — dùng đúng path này khi generate)

```
notification-platform/
├── gateway-api/
│   ├── validation/                          # Chain of Responsibility
│   │   ├── NotificationValidationHandler.java
│   │   ├── IdempotencyCheckHandler.java
│   │   ├── SubscriptionCheckHandler.java
│   │   └── BudgetCheckHandler.java
│   ├── budget/
│   │   ├── BudgetGuardService.java           # gọi Lua script Redis
│   │   └── lua/budget_check.lua
│   └── controller/
│       └── NotificationApiController.java    # POST /notifications
│
├── core/
│   ├── template/
│   │   ├── TemplateService.java              # local cache -> redis -> mongo
│   │   ├── TemplateAdminService.java         # CRUD + publish invalidate event
│   │   └── TemplateRenderer.java             # render placeholder {{xxx}}
│   ├── sender/
│   │   ├── NotificationSender.java           # interface
│   │   ├── NotificationSenderFactory.java    # Factory Method
│   │   └── AbstractNotificationProcessor.java # Template Method (skeleton chung)
│   └── subscription/
│       └── SubscriptionService.java          # cache Redis + fallback Postgres
│
├── provider-sms/
│   ├── SmsProviderFactory.java               # abstract — Abstract Factory
│   ├── providera/
│   │   ├── ProviderAFactory.java
│   │   ├── ProviderAHttpClient.java
│   │   ├── ProviderAResponseParser.java
│   │   └── ProviderAErrorMapper.java
│   ├── providerb/ (tương tự providera)
│   └── SmsGatewayRouter.java                 # failover + circuit breaker
│
├── provider-email/
│   └── EmailProviderClient.java
│
├── provider-push/
│   ├── FcmSingleSendClient.java
│   └── FcmMulticastClient.java               # dùng cho PROMOTION/POLICY_ANNOUNCEMENT
│
├── kafka/
│   ├── config/
│   │   ├── KafkaRetryTopicConfig.java        # RetryTopicConfiguration per channel
│   │   └── KafkaConsumerConfig.java          # virtual thread executor
│   ├── listener/
│   │   ├── OtpNotificationListener.java      # maxAttempts=1, fail-fast
│   │   ├── SmsNotificationListener.java
│   │   ├── EmailNotificationListener.java
│   │   └── PushNotificationListener.java     # + batch multicast cho broadcast
│   └── dlt/
│       └── NotificationDltHandler.java
│
├── admin/
│   ├── DlqAdminController.java               # ad-hoc reprocess
│   ├── DlqReprocessService.java              # pessimistic lock + idempotency guard
│   ├── TemplateAdminController.java
│   └── BudgetConfigController.java
│
├── repository/
│   ├── postgres/
│   │   ├── NotificationRequestRepository.java
│   │   ├── NotificationAttemptRepository.java
│   │   ├── NotificationDlqRepository.java
│   │   ├── UserChannelSubscriptionRepository.java
│   │   └── ClientBudgetConfigRepository.java
│   └── mongo/
│       └── NotificationTemplateRepository.java
│
├── entity/ (JPA — Postgres)
├── document/ (MongoDB documents)
├── dto/
│   ├── NotificationMessage.java              # Kafka payload chung
│   ├── NotificationRequest.java              # REST request
│   └── ReprocessResult.java
│
├── exception/
│   ├── retriable/
│   │   ├── SmsGatewayTimeoutException.java
│   │   ├── SmsGatewayConnectionException.java
│   │   └── SmsGatewayUnavailableException.java   # circuit open
│   └── nonretriable/
│       ├── InvalidPhoneNumberException.java
│       ├── TemplateNotFoundException.java
│       └── BudgetExceededException.java
│
└── infra/
    ├── redis/
    │   ├── RedisCacheConfig.java
    │   ├── TemplateInvalidationPublisher.java    # Pub/Sub publisher (admin side)
    │   ├── TemplateInvalidationSubscriber.java   # Pub/Sub subscriber (mọi pod)
    │   └── DlqEventStreamPublisher.java          # Redis Stream XADD
    ├── minio/
    │   └── MinioAssetService.java
    └── config/
        ├── VirtualThreadConfig.java
        ├── CircuitBreakerConfig.java
        └── CaffeineCacheConfig.java
```

---

## 6. Kafka Design

### 6.1 Topic naming convention

```
topic.notif.otp
topic.notif.sms            (+ tự sinh: sms-retry-0, sms-retry-1, sms-retry-2, sms-dlt)
topic.notif.email          (+ tự sinh tương tự)
topic.notif.push           (+ tự sinh tương tự)
```

### 6.2 Retry policy theo channel

| Channel | maxAttempts | Backoff | Ghi chú |
|---|---|---|---|
| OTP | 1 (không dùng `@RetryableTopic`) | — | fail-fast, xử lý trong try/catch thường |
| SMS | 4 (1 gốc + 3 retry) | initial=2s, multiplier=2, max=30s | |
| EMAIL | 4 | initial=5s, multiplier=3, max=45s | SLA lỏng hơn |
| PUSH | 4 | initial=2s, multiplier=2, max=30s | |

### 6.3 Header quy ước cho `NotificationMessage`

| Header | Kiểu | Mô tả |
|---|---|---|
| `requestId` | UUID string | Định danh duy nhất |
| `idempotencyKey` | String (SHA-256) | Chống trùng |
| `retry-count` | Int | Spring Kafka tự set |
| `reprocessed-from-dlq-id` | Long, optional | Khi admin reprocess |
| `reprocessed-by` | String, optional | operatorId |

### 6.4 Exception → retriable mapping (dùng trong `@RetryableTopic(exclude = {...})`)

- **Retriable** (throw để trigger retry): `SmsGatewayTimeoutException`, `SmsGatewayConnectionException`, `SmsGatewayUnavailableException`
- **Non-retriable** (catch, log, KHÔNG throw): `InvalidPhoneNumberException`, `TemplateNotFoundException`

### 6.5 Broadcast (PROMOTION/POLICY_ANNOUNCEMENT) — không publish 1 message/user

Publish **1 "campaign job" message** chứa segment/batch reference. Consumer đọc theo batch 500 user, gọi `FcmMulticastClient` (tối đa 500 token/request).

---

## 7. Database Design

### 7.1 PostgreSQL — DDL đầy đủ

```sql
CREATE TABLE notification_request (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    client_id       VARCHAR(50) NOT NULL,
    notification_type VARCHAR(30) NOT NULL,     -- OTP, BALANCE_CHANGE, NEW_DEVICE_LOGIN, PROMOTION, POLICY_ANNOUNCEMENT
    channel         VARCHAR(20) NOT NULL,        -- SMS, EMAIL, PUSH
    recipient       VARCHAR(255) NOT NULL,
    template_id     VARCHAR(50),
    payload         JSONB NOT NULL,
    priority        VARCHAR(10) DEFAULT 'NORMAL',
    status          VARCHAR(20) NOT NULL,        -- PENDING, SUCCESS, FAILED_PERMANENT, IN_DLQ
    business_date   DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (business_date);            -- partition theo tháng, xem mục 11.5

CREATE TABLE notification_attempt (
    id                 BIGSERIAL PRIMARY KEY,
    request_id         UUID NOT NULL REFERENCES notification_request(request_id),
    attempt_number     INT NOT NULL,
    provider_name      VARCHAR(50),
    status             VARCHAR(20) NOT NULL,     -- SUCCESS, FAILED, TIMEOUT, CIRCUIT_OPEN
    provider_response  JSONB,
    error_code         VARCHAR(50),
    error_message      TEXT,
    called_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_ms        INT
);
CREATE INDEX idx_attempt_request_status ON notification_attempt(request_id, status);

CREATE TABLE notification_dlq (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          UUID NOT NULL REFERENCES notification_request(request_id),
    channel             VARCHAR(20) NOT NULL,
    payload             JSONB NOT NULL,
    failure_reason      TEXT,
    dlq_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, REPROCESSING, RESOLVED, ABANDONED
    reprocess_count     INT NOT NULL DEFAULT 0,
    moved_to_dlq_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),
    resolve_note        TEXT
);
CREATE INDEX idx_dlq_status ON notification_dlq(dlq_status);

CREATE TABLE user_channel_subscription (
    id                BIGSERIAL PRIMARY KEY,
    user_id           UUID NOT NULL,
    notification_type VARCHAR(30) NOT NULL,      -- BALANCE_CHANGE, PROMOTION, POLICY_ANNOUNCEMENT
    channel           VARCHAR(20) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, notification_type, channel)
);

CREATE TABLE client_budget_config (
    id            BIGSERIAL PRIMARY KEY,
    client_id     VARCHAR(50) NOT NULL,
    channel       VARCHAR(20) NOT NULL,
    period        VARCHAR(10) NOT NULL,          -- DAILY, MONTHLY
    quota_limit   INT NOT NULL,
    priority_tier VARCHAR(10) DEFAULT 'NORMAL',  -- HIGH, NORMAL, LOW
    UNIQUE (client_id, channel, period)
);
```

**Quy tắc:** `OTP`/`NEW_DEVICE_LOGIN` KHÔNG tra `user_channel_subscription` — luôn gửi.

### 7.2 MongoDB — Template document

Collection: `notification_template`

```json
{
  "_id": "tpl_{templateCode}_{channel}_{locale}",
  "templateCode": "BALANCE_CHANGE",
  "channel": "SMS",
  "locale": "vi-VN",
  "version": 3,
  "status": "ACTIVE",
  "content": { "subject": null, "body": "...", "htmlBody": null },
  "placeholders": ["accountNo", "direction", "amount", "balance"],
  "assetRef": null,
  "createdBy": "admin01",
  "updatedAt": "ISODate"
}
```

### 7.3 MinIO — bucket layout

```
notification-assets/
  ├── promotion/{campaignId}/banner.png
  └── email-attachment/{requestId}/invoice.pdf
```

---

## 8. Redis — Key convention & cơ chế theo use case

| Use case | Key pattern | Cơ chế | TTL |
|---|---|---|---|
| Template cache (tầng 2) | `template:{templateId}` | String/Hash | 60 phút |
| Local cache template (tầng 1, trong JVM) | Caffeine key = `templateId` | — | 10 phút, maxSize giới hạn (~2000 entry) |
| Subscription cache | `subscription:{userId}` | Hash | 20 phút |
| Budget counter | `budget:{clientId}:{channel}:{yyyyMMdd}` | INCR + EXPIRE (Lua atomic) | Hết ngày (DAILY) hoặc hết tháng (MONTHLY) |
| **Cache invalidation** | Pub/Sub channel `tpl:invalidate` | Pub/Sub (không persist) | — |
| **DLQ live feed cho dashboard** | Stream key `dlq:events` | XADD/XREAD, consumer group `dashboard-viewers` | MAXLEN ~5000 (trim) |
| Idempotency short-circuit (optional, tầng Gateway) | `idem:{idempotencyKey}` | SETNX + TTL ngắn | 5-10 phút |

**Nguyên tắc chọn Pub/Sub vs Stream:** Pub/Sub khi mất message cũng chấp nhận được (cache tự invalidate lại theo TTL). Stream khi cần đảm bảo không miss event (dashboard reconnect vẫn phải thấy được sự kiện đã xảy ra trong lúc mất kết nối).

---

## 9. Design Patterns áp dụng — mapping cụ thể (bắt buộc theo khi generate code)

| Pattern | Áp dụng ở đâu | Class chính |
|---|---|---|
| **Abstract Factory** | Multi-provider SMS — mỗi provider là 1 họ sản phẩm (client + parser + error mapper) | `SmsProviderFactory` (abstract), `ProviderAFactory`, `ProviderBFactory` |
| **Factory Method** | Chọn `NotificationSender` theo channel enum | `NotificationSenderFactory` |
| **Template Method** | Khung xử lý chung: idempotency check → load template → render → send → record attempt | `AbstractNotificationProcessor` |
| **Chain of Responsibility** | Pipeline validate ở Gateway API trước khi publish Kafka | `NotificationValidationHandler` → `IdempotencyCheckHandler` → `SubscriptionCheckHandler` → `BudgetCheckHandler` |
| **Decorator** (qua Resilience4j) | Circuit breaker + TimeLimiter bọc quanh lời gọi 3rd party | `CircuitBreaker.decorateSupplier(...)` trong `SmsGatewayRouter` |
| **Observer** (qua Redis Pub/Sub) | Cache invalidation đa pod khi admin cập nhật template | `TemplateInvalidationPublisher` / `TemplateInvalidationSubscriber` |

---

## 10. Multi-provider SMS — Failover Logic

`SmsGatewayRouter.sendWithFailover(providerPriorityList, recipient, content)`:
1. Duyệt qua danh sách provider theo priority (config, ví dụ `[providerA, providerB]`).
2. Với mỗi provider: lấy `CircuitBreaker` riêng theo tên provider (`cbRegistry.circuitBreaker(providerId)`).
3. Nếu circuit OPEN (`CallNotPermittedException`) hoặc gọi thất bại (`SmsGatewayException`) → log warning, thử provider tiếp theo.
4. Nếu hết danh sách vẫn fail → throw `AllSmsProvidersUnavailableException` (retriable — để Kafka retry flow xử lý tiếp).

### Resilience4j config mẫu

```yaml
resilience4j:
  circuitbreaker:
    instances:
      providerA:
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 15s
      providerB:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 15s
  timelimiter:
    instances:
      providerA:
        timeout-duration: 3s
      providerB:
        timeout-duration: 3s
```

---

## 11. Capacity Planning (áp dụng khi cấu hình, KHÔNG over-provision)

| Thành phần | Cấu hình khuyến nghị | Lý do |
|---|---|---|
| Kafka partitions (topic transactional) | 8–12 partition/topic | Đủ cho ~1,000-1,500 msg/s đỉnh, bottleneck thật sự là latency gọi 3rd party chứ không phải Kafka |
| Consumer instance | Bắt đầu 3-4 pod/channel, scale theo consumer lag (HPA) | Tránh giữ sẵn pod lớn khi tải thấp |
| JVM concurrency | Virtual Threads (JDK 21) cho listener container | Giảm RAM so với platform thread pool lớn truyền thống, phù hợp workload I/O-bound |
| Giới hạn gọi 3rd party đồng thời | Semaphore hoặc Resilience4j `permitted-number-of-calls` | Bottleneck thật là rate limit của provider ngoài, không phải khả năng tạo thread |
| Redis connection | Lettuce, vài chục connection | Multiplexing, không cần pool lớn như JDBC |
| Caffeine local cache | `maximumSize` giới hạn ~2000 entry | Số template thực tế trong hệ thống banking thường vài trăm, tránh phình cache |
| Postgres insert `notification_attempt` | Batch insert (micro-batch 50-100ms hoặc Spring Batch) | Giảm round-trip DB ở giờ cao điểm |
| Postgres partition | `notification_request`/`notification_attempt` partition theo `business_date` | Giữ index nhỏ, dễ archive/drop partition cũ |
| Push broadcast | FCM multicast batch 500 token/call | 1 triệu user → ~2,000 call thay vì 1 triệu call |

---

## 12. Coding Conventions

- **Exception hierarchy:** tách rõ package `exception.retriable` và `exception.nonretriable` — mọi exception mới phải được đặt đúng package này để `@RetryableTopic(exclude=...)` áp dụng chính xác.
- **DTO Kafka payload:** `NotificationMessage` dùng chung cho mọi channel, có field `channel`, `notificationType`, `requestId`, `idempotencyKey`, `recipient`, `templateId`, `payload` (Map placeholder).
- **Không update `notification_attempt`** — service layer chỉ có method `save()`/`insert()`, không có `update()`.
- **Mọi log chứa số điện thoại/email phải mask** (ví dụ `0912***678`, `ab***@gmail.com`) — áp dụng ở tầng logging filter/formatter chung, không rải rác từng nơi gọi log.
- **Response API chuẩn:** `202 Accepted` khi publish thành công vào Kafka, `429 Too Many Requests` khi budget vượt, `409 Conflict` khi reprocess DLQ record không ở trạng thái `PENDING`.

---

## 13. Out of scope / chưa triển khai ở phase này

- In-app notification center (Redis Stream `notif:feed:{userId}`) — đã note trong thiết kế nhưng chưa nằm trong scope code hiện tại, để mở rộng sau.
- Đa ngôn ngữ ngoài `vi-VN` — schema template đã hỗ trợ `locale` nhưng chưa cần implement thêm locale khác ở phase đầu.
- Cost reconciliation với nhà cung cấp SMS (đối soát chi phí) — có ghi nhận `provider_name` trong `notification_attempt` làm nền tảng, nhưng module đối soát chưa nằm trong phase này.

---

## 14. Thứ tự gợi ý khi generate code (để Claude Code triển khai tuần tự, tránh phụ thuộc ngược)

1. `entity/` + `document/` + `repository/` (Postgres + Mongo) + DDL migration (Flyway/Liquibase).
2. `core/template/` (TemplateService cache 2 tầng) — vì mọi luồng gửi đều phụ thuộc template.
3. `provider-sms/`, `provider-email/`, `provider-push/` (Abstract Factory + client cơ bản, chưa cần failover phức tạp).
4. `kafka/config/` + `kafka/listener/` (bắt đầu với SMS, dùng làm mẫu cho Email/Push).
5. `core/sender/` (`AbstractNotificationProcessor`, `NotificationSenderFactory`) — refactor listener để dùng lại skeleton chung.
6. `gateway-api/` (Chain of Responsibility: idempotency → subscription → budget) + `infra/redis/` budget Lua script.
7. `kafka/dlt/NotificationDltHandler` + `admin/DlqReprocessService` (idempotency guard 2 lớp).
8. `infra/redis/TemplateInvalidationPublisher/Subscriber` (Pub/Sub) + `admin/TemplateAdminController`.
9. `infra/redis/DlqEventStreamPublisher` (Stream) cho dashboard live feed.
10. Broadcast flow: `FcmMulticastClient` + campaign batch job cho `PROMOTION`/`POLICY_ANNOUNCEMENT`.
11. Capacity tuning: Virtual Thread config, Caffeine size limit, Postgres batch insert, partition table.
