# Spring Batch user export — Design Spec

## 1. Mục tiêu & bối cảnh

`system-report-job` hiện chỉ có các `JobAction` đơn giản (`HTTP_CALL`, `ECHO`) — không có ví dụ nào minh
hoạ việc xử lý dữ liệu lớn theo kiểu chunk-oriented. Mục tiêu của tính năng này:

1. Thêm bảng `users` chứa dữ liệu mock (tới 1 triệu bản ghi) làm nguồn dữ liệu mẫu.
2. Thêm một `JobAction` mới dùng **Spring Batch** thật sự (thay vì chỉ log) để đọc `users` theo chunk và
   ghi kết quả export ra bảng `user_exports`.
3. Chứng minh Spring Batch tích hợp được vào kiến trúc hiện có (Quartz trigger → `JobAction` registry)
   mà không đổi domain/usecase — đúng kết luận đã thống nhất ở phần feasibility trước đó.

Đây là bổ sung độc lập, không thay thế `HTTP_CALL`/`ECHO` — cả hai vẫn giữ nguyên.

## 2. Data model & Migration

Theo đúng convention hiện có (`id UUID PRIMARY KEY`, sinh ở tầng Java — xem `BaseEntity`; timestamp
`TIMESTAMPTZ`). Không dùng `is_deleted`/soft-delete cho hai bảng này — đây là dữ liệu mẫu/kết quả export,
không phải aggregate nghiệp vụ của service.

### `V5__create_users_table.sql`

```sql
CREATE TABLE users (
    id             UUID PRIMARY KEY,
    username       VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    full_name      VARCHAR(255) NOT NULL,
    phone_number   VARCHAR(20),
    address        VARCHAR(500),
    gender         VARCHAR(10),
    dob            DATE,
    description    VARCHAR(500),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'INACTIVE', 'DRAFT', 'LOCKED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email)
);
CREATE INDEX idx_users_status ON users (status);
```

> Giả định: `draff` trong yêu cầu gốc là gõ nhầm của `DRAFT`. Nếu ý là một trạng thái khác, cần đổi
> `CHECK` constraint trước khi migrate.

### `V6__create_user_exports_table.sql`

```sql
CREATE TABLE user_exports (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL,
    username       VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    full_name      VARCHAR(255) NOT NULL,
    phone_number   VARCHAR(20),
    address        VARCHAR(500),
    gender         VARCHAR(10),
    dob            DATE,
    description    VARCHAR(500),
    status         VARCHAR(20) NOT NULL,
    exported_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_exports_user_id ON user_exports (user_id);
CREATE INDEX idx_user_exports_exported_at ON user_exports (exported_at);
```

Mỗi lần job chạy tạo thêm bản ghi mới (snapshot theo thời điểm export), không upsert — giữ writer đơn
giản (`INSERT`-only), tránh logic dedupe không cần thiết cho mục tiêu minh hoạ này.

### `V7__create_spring_batch_tables.sql`

Schema chuẩn của Spring Batch cho Postgres (`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`,
`BATCH_JOB_EXECUTION_PARAMS`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION`,
`BATCH_STEP_EXECUTION_CONTEXT` + 3 sequence) — copy nguyên văn từ
`org/springframework/batch/core/schema-postgresql.sql` trong `spring-batch-core`, không tự chế lại DDL.
`spring.batch.jdbc.initialize-schema=never` để Flyway là nguồn sự thật duy nhất, giống Quartz.

## 3. Mock data seeding (1 triệu bản ghi)

**Không** đưa vào Flyway migration chính. Mọi test dùng Testcontainers (persistence/scheduler/e2e) chạy
lại toàn bộ chain migration trên container Postgres mới mỗi lần — nếu 1 triệu dòng nằm trong `V*`, mỗi
lần `mvn test` sẽ tốn thêm hàng chục giây không liên quan đến logic được test.

→ Tách thành script SQL thường, **không phải Flyway migration**:

`src/main/resources/db/seed/seed_users_1m.sql`

```sql
INSERT INTO users (id, username, email, full_name, phone_number, address, gender, dob, description, status)
SELECT
    gen_random_uuid(),
    'user' || gs,
    'user' || gs || '@example.com',
    'Mock User ' || gs,
    '09' || lpad((floor(random() * 100000000))::text, 8, '0'),
    'Address ' || gs || ', District ' || (1 + floor(random() * 24))::int || ', HCMC',
    (ARRAY['MALE', 'FEMALE', 'OTHER'])[1 + floor(random() * 3)],
    date '1970-01-01' + (floor(random() * 18250))::int,
    'Mock user #' || gs || ' generated for load testing',
    (ARRAY['ACTIVE', 'INACTIVE', 'DRAFT', 'LOCKED'])[1 + floor(random() * 4)]
FROM generate_series(1, 1000000) AS gs;
```

`gen_random_uuid()` cần extension `pgcrypto` (`CREATE EXTENSION IF NOT EXISTS pgcrypto;` — chạy 1 lần,
đặt ở đầu script seed, **không** đặt trong Flyway migration vì đây là setup cho DB local/dev, không phải
schema của app).

Chạy thủ công khi cần data thật để đo hiệu năng job:

```bash
psql "$DATABASE_URL" -f src/main/resources/db/seed/seed_users_1m.sql
```

README của module sẽ có 1 mục ngắn ghi lại lệnh này.

## 4. Batch job design

### Package: `infrastructure/jobactions/batch/`

- `UserRecord` — Java record ánh xạ 1 dòng đọc từ `users` (id, username, email, fullName, phoneNumber,
  address, gender, dob, description, status). Chỉ dùng nội bộ trong package batch — **không** phải
  `domain/model`, vì `users` không phải aggregate nghiệp vụ của `system-report-job`.
- `UserExportRecord` — record tương ứng cho `user_exports`, thêm `exportedAt`.
- `UserExportBatchConfig` (`@Configuration`) khai báo:
  - `ItemReader<UserRecord> userItemReader(DataSource)` — `JdbcPagingItemReader`, `SELECT * FROM users
    ORDER BY id`, `PostgresPagingQueryProvider`, `pageSize = 1000`.
  - `ItemProcessor<UserRecord, UserExportRecord> userExportProcessor()` — map 1-1, sinh
    `id = UUID.randomUUID()`, `exportedAt = Instant.now()`.
  - `ItemWriter<UserExportRecord> userExportWriter(DataSource)` — `JdbcBatchItemWriter`, `INSERT INTO
    user_exports (...) VALUES (...)` với `BeanPropertyItemSqlParameterSourceProvider`.
  - `Step exportUsersStep(...)` — `chunk(1000, transactionManager)` (chunk size cấu hình qua
    `app.batch.export.chunk-size`, mặc định 1000).
  - `Job exportUsersJob(JobRepository, Step)` — 1 step duy nhất.
  - **Không** đặt `@EnableBatchProcessing` kèm `JobLauncherApplicationRunner` mặc định — job **chỉ**
    được chạy qua `SpringBatchJobAction`, không tự chạy lúc Boot khởi động
    (`spring.batch.job.enabled=false` đã chặn ở tầng cấu hình, đây là double-check ở tầng thiết kế).

- `SpringBatchJobAction implements JobAction`:
  - `matches(jobType)` → `"EXPORT_USERS".equals(jobType)`.
  - `execute(JobDefinition definition)`:
    - Chạy `jobLauncher.run(exportUsersJob, jobParameters)` **đồng bộ**, `jobParameters` gồm
      `taskId` + `runAt=System.currentTimeMillis()` (đảm bảo mỗi lần Quartz fire tạo 1
      `JobInstance` mới — Spring Batch coi hai `JobExecution` cùng `JobParameters` là "đã chạy rồi" và
      sẽ từ chối chạy lại nếu thiếu tham số phân biệt này).
    - Chạy trên `jobActionTaskExecutor` (virtual-thread executor đã có, tái dùng từ
      `HttpCallJobAction`) để không pin worker thread của Quartz trong lúc batch chạy.
    - Sau khi `JobExecution` hoàn tất: nếu `getStatus() != BatchStatus.COMPLETED` → ném
      `IllegalStateException` kèm `ExitStatus` — exception rơi đúng vào `QuartzJobListener` để ghi
      `TaskExecutionHistory` như mọi `JobAction` khác, không cần sửa gì ở tầng lịch sử.

### Không đổi domain/usecase

`domain/model`, `usecase/ports`, `usecase/service`, `ScheduledJobExecutor`, `JobActionRegistry`,
`QuartzJobListener` — **không file nào trong các package này bị sửa**. Thêm `jobType` mới chỉ cần thêm 1
`JobAction` bean, đúng nguyên tắc extensibility đã có sẵn của registry.

## 5. Wiring — tạo `JobDefinition` + `Task` demo

Không seed cứng bằng migration (giống `HTTP_CALL`/`ECHO` hiện tại cũng không có seed data). Tạo qua
chính API hiện có, ghi lại làm ví dụ trong implementation plan / README:

```bash
curl -X POST localhost:8080/system-report-job/api/job-definitions \
  -d '{"name":"Export users","jobType":"EXPORT_USERS","expression":"{}"}'

curl -X POST localhost:8080/system-report-job/api/tasks \
  -d '{"name":"export-users-hourly","jobDefinitionId":"<id-ở-trên>",
       "triggerType":"CRON","cronExpression":"0 0 * * * ?"}'
```

## 6. Tech stack / pom.xml

Thêm:
- `org.springframework.boot:spring-boot-starter-batch`
- `org.springframework.batch:spring-batch-test` (scope `test`) — cho `JobLauncherTestUtils`.

`application.yml` thêm:
```yaml
spring:
  batch:
    job:
      enabled: false
    jdbc:
      initialize-schema: never
app:
  batch:
    export:
      chunk-size: 1000
```

## 7. Testing strategy

- **Unit** (`usecase`-style, Mockito thuần, không Spring context): `SpringBatchJobActionTest` — mock
  `JobLauncher`, verify `execute()` gọi đúng `Job`/`JobParameters`, verify exception khi
  `BatchStatus != COMPLETED`.
- **Integration** (Testcontainers Postgres, giống các test khác trong `infrastructure/`):
  `UserExportBatchConfigTest` — seed **20-30 dòng** `users` trực tiếp trong test (không đụng file seed
  1 triệu dòng), chạy job thật qua `JobLauncherTestUtils`, assert số dòng và nội dung trong
  `user_exports`.
- File seed 1 triệu dòng **không** được test suite tự động chạy — chỉ chạy tay khi cần đo hiệu năng.

## 8. Ngoài phạm vi (Out of scope)

- Không thêm domain model / port / controller cho `users` — đây thuần là dữ liệu nguồn cho batch job,
  không phải resource được quản lý qua API như `Task`/`JobDefinition`.
- Không xử lý trùng lặp/merge giữa các lần export (`user_exports` là append-only theo thiết kế).
- Không thêm cơ chế filter (`expression` của `JobDefinition` giữ nguyên `{}`, không parse) — nếu sau
  này cần export có điều kiện (theo `status`, khoảng ngày...), sẽ là một thay đổi riêng.
