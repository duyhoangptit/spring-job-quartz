# system-report-job — Design Spec

**Date:** 2026-08-09 (di dời + đổi tên 2026-08-10)
**Status:** Approved
**Author:** Brainstormed with Claude Code

> **Ghi chú di dời:** Spec này được brainstorm ban đầu trong repo `microservice-java/system-report-job` (monorepo cũ, groupId `vn.tiger`, tên gọi ban đầu `system-report-job-v2`). Sau khi chốt thiết kế, đã quyết định đặt service mới này làm 1 service con trong umbrella repo `core-banking-10000tps` (ngang hàng `fraud-detection-service/`, `bank-timezone/`), và đổi groupId sang `com.corebanking` + bỏ hậu tố `-v2` cho khớp quy ước của repo này. Bản spec dưới đây đã được cập nhật theo tên gọi mới; bản gốc (tên cũ) vẫn còn lưu tại `microservice-java/system-report-job/docs/superpowers/specs/` làm lịch sử.

## 1. Mục tiêu & bối cảnh

`system-report-job` (module trong monorepo `vn.tiger:microservice-java` — **một repo hoàn toàn khác**, không liên quan đến `core-banking-10000tps`) là hệ thống điều phối job động qua Quartz Scheduler: người dùng tạo `Task` (định nghĩa lịch chạy), hệ thống lên lịch bằng Quartz, và khi trigger bắn thì gọi HTTP sang service khác.

Module hiện tại chạy trên **Spring Boot 3.4.5**, kế thừa gián tiếp qua parent pom của monorepo (đang pin cứng version này cho ~30 module khác) — không thể nâng cấp riêng module này lên Spring Boot 4.1 mà không ảnh hưởng cả monorepo.

**Mục tiêu của dự án này:** xây dựng **`system-report-job`**, một repository độc lập hoàn toàn, chạy trên **Spring Boot 4.1 (Spring Framework 7) + Java 21**, với mục đích **sẽ thay thế module hiện tại trong tương lai** — do đó cần giữ đúng parity nghiệp vụ, đồng thời sửa các vấn đề kỹ thuật đã phát hiện ở bản cũ, và đạt chất lượng đủ cho production (kiến trúc rõ ràng, có test).

### Các quyết định đã chốt

| Chủ đề | Quyết định |
|---|---|
| Mục đích | Thay thế module `system-report-job` hiện tại trong tương lai → cần parity đầy đủ + chất lượng production |
| Kiến trúc | Clean Architecture (`domain` / `usecase` / `infrastructure`), dependency rule: infra → usecase → domain |
| Phạm vi | Port đúng parity nghiệp vụ **+ sửa các vấn đề đã biết** ở bản cũ (liệt kê ở mục 7) |
| Thư viện dùng chung (`common-cores`) | Viết lại gọn trong project mới, không phụ thuộc thư viện nội bộ monorepo |
| Mô hình thực thi job | Ngoài gọi HTTP ra ngoài (như bản cũ), hỗ trợ thêm job chạy logic nội bộ (in-process) |
| Tên gọi | groupId `com.corebanking` (giữ), artifactId `system-report-job`, package `com.corebanking.systemreportjob` |
| Persistence | Spring Data JPA + PostgreSQL (giữ nguyên công nghệ) |
| Auth | Chưa làm Spring Security thật trong phạm vi này — giữ nguyên giả định như bản cũ (do gateway/service khác đảm nhiệm), chỉ giữ swagger annotation làm tài liệu |
| Java | Java 21 LTS |
| `TaskConfig` | Bản cũ gần như là code chết (không có FK thật, không service nào đọc/ghi). Xác nhận với chủ dự án: đây **là** nơi cấu hình job (thêm job, rồi start/pause/stop) → thiết kế lại thành `JobDefinition` có nghiệp vụ thật (mục 4, Cách B) |

### Đối chiếu 3 cách tiếp cận cho lõi Task/JobDefinition (đã chọn Cách B)

- **Cách A** — vá tối thiểu, giữ reflection nạp `Class<? extends Job>` từ `className`/`packageName` lưu trong DB, `TaskConfig` chỉ là bảng phụ có FK thật. Bị loại vì giữ nguyên rủi ro bảo mật (dữ liệu DB điều khiển việc khởi tạo class tuỳ ý) và có 2 cơ chế song song để xác định "chạy gì".
- **Cách B (đã chọn)** — hợp nhất `TaskConfig` → `JobDefinition`, đóng vai trò "chạy gì" (qua registry Strategy `JobAction` theo `jobType`), `Task` chỉ còn giữ "khi nào" và tham chiếu `JobDefinition` bằng FK thật. Loại bỏ hoàn toàn reflection nạp class từ dữ liệu DB.
- **Cách C** — `TaskConfig` là CRUD độc lập, không tham gia dispatch thực thi. Bị loại vì không khớp mô tả nghiệp vụ thật (`TaskConfig` phải là nơi cấu hình + thêm job + start/pause/stop).

## 2. Kiến trúc tổng thể

Clean Architecture 3 tầng. Dependency rule: `infrastructure → usecase → domain`. `domain` không import bất kỳ package Spring/Quartz/JPA nào.

```text
system-report-job/
├── pom.xml                                  # standalone, spring-boot-starter-parent 4.1.x, Java 21
└── src/main/java/com/corebanking/systemreportjob/
    ├── JobApplication.java
    │
    ├── domain/                              [DOMAIN]
    │   ├── model/
    │   │   ├── ScheduledTask.java           (record — phần "khi nào")
    │   │   ├── JobDefinition.java           (record — phần "chạy gì", thay cho TaskConfig)
    │   │   ├── TriggerDefinition.java       (sealed interface: Cron/Simple/CalendarInterval/DailyTimeInterval)
    │   │   ├── TaskExecutionRecord.java     (1 lần chạy: start/end/exception)
    │   │   └── TriggerState.java            (enum trạng thái trigger, độc lập Quartz)
    │   └── exception/
    │       ├── ErrorCode.java               (enum, thuần domain — mã lỗi + message key i18n)
    │       ├── BusinessException.java
    │       ├── TaskNotFoundException.java
    │       └── JobDefinitionNotFoundException.java
    │
    ├── usecase/                             [USECASE]
    │   ├── ports/
    │   │   ├── in/
    │   │   │   ├── TaskManagementUseCase.java     (create/start/pause/resume/delete/search/getDetail/startAll)
    │   │   │   ├── JobDefinitionUseCase.java      (create/update/delete)
    │   │   │   ├── TaskHistoryQueryUseCase.java   (search)
    │   │   │   └── ExecuteScheduledJobUseCase.java (execute(taskId) — Quartz gọi vào đây)
    │   │   └── out/
    │   │       ├── TaskRepositoryPort.java
    │   │       ├── JobDefinitionRepositoryPort.java
    │   │       ├── TaskExecutionHistoryRepositoryPort.java
    │   │       ├── SchedulerGatewayPort.java      (schedule/unschedule/pause/resume/getTriggerState)
    │   │       └── JobActionExecutorPort.java     (dispatch theo jobType tới JobAction)
    │   └── service/
    │       ├── TaskOrchestrator.java              (implements TaskManagementUseCase)
    │       ├── JobDefinitionService.java          (implements JobDefinitionUseCase)
    │       ├── TaskHistoryQueryService.java       (implements TaskHistoryQueryUseCase)
    │       └── JobExecutionOrchestrator.java      (implements ExecuteScheduledJobUseCase)
    │
    └── infrastructure/                      [INFRASTRUCTURE]
        ├── config/
        │   ├── QuartzClusterConfig.java     (AutowiringSpringBeanJobFactory, isClustered thật theo profile)
        │   └── VirtualThreadConfig.java     (executor cho JobAction kiểu HTTP-call)
        ├── scheduler/
        │   ├── ScheduledJobExecutor.java    (Quartz Job DUY NHẤT trong toàn hệ thống)
        │   ├── QuartzSchedulerGatewayAdapter.java   (implements SchedulerGatewayPort)
        │   ├── QuartzTriggerFactory.java            (TriggerDefinition → org.quartz.Trigger, switch pattern-matching)
        │   └── listeners/
        │       ├── QuartzJobListener.java           (ghi TaskExecutionHistory qua port)
        │       └── QuartzJobTriggerListener.java
        ├── jobactions/                      (Strategy pattern — implements JobAction, key = jobType)
        │   ├── JobAction.java
        │   ├── JobActionRegistry.java        (implements JobActionExecutorPort, inject List<JobAction>)
        │   ├── HttpCallJobAction.java        (jobType = "HTTP_CALL")
        │   └── sample/EchoInProcessJobAction.java
        ├── persistence/
        │   ├── entity/ (TaskEntity, JobDefinitionEntity, TaskExecutionHistoryEntity, BaseEntity)
        │   ├── repository/ (Spring Data JPA repositories)
        │   └── adapter/ (implement *RepositoryPort, map entity ⇄ domain model)
        ├── web/
        │   ├── controller/ (TaskController, JobDefinitionController, TaskHistoryController)
        │   └── dto/ (request/, response/)
        └── common/ (ApiResponse, PageResponse, GlobalExceptionHandler, ValidCron/CronExpressionValidator)
```

## 3. Domain model

Toàn bộ record dưới đây nằm trong `domain/model/`, **không phụ thuộc Spring/JPA/Quartz**.

```java
public record ScheduledTask(
    UUID id,
    String name,
    String group,
    UUID jobDefinitionId,        // FK thật tới JobDefinition
    TriggerDefinition trigger,
    String timezoneId,
    Integer priority,
    String description
) {
    public ScheduledTask {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tên task không được rỗng");
        if (jobDefinitionId == null) throw new IllegalArgumentException("Task phải gắn với một JobDefinition");
    }
}

public record JobDefinition(
    UUID id,
    String jobType,     // actionKey tra trong JobActionExecutorPort, vd "HTTP_CALL", "PRODUCT_SYNC"
    String expression,  // tham số JSON truyền cho JobAction lúc thực thi
    String description
) {}

public sealed interface TriggerDefinition {
    record Cron(String cronExpression) implements TriggerDefinition {}
    record Simple(int intervalInSeconds, int repeatCount) implements TriggerDefinition {}
    record CalendarInterval(int intervalInDays) implements TriggerDefinition {}
    record DailyTimeInterval(LocalTime startingDailyAt, LocalTime endingDailyAt, int intervalInMinutes) implements TriggerDefinition {}
}

public record TaskExecutionRecord(
    UUID id,
    UUID taskId,
    Instant startTime,
    Instant endTime,
    String exceptionMessage
) {}

public enum TriggerState { NONE, NORMAL, PAUSED, COMPLETE, ERROR, BLOCKED }

// Read-model tổng hợp cho GET /api/tasks/{id} — không phải entity, chỉ ghép từ các model trên
public record TaskDetail(ScheduledTask task, JobDefinition jobDefinition, TriggerState state) {}

// Kiểu phân trang dùng chung cho mọi usecase trả danh sách, thay PageResponse<T> cũ
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
```

> `TriggerTypeEnum.CUSTOM_TRIGGER` của bản cũ **chủ động không mang sang** — nó chưa từng có `TriggerBuildService` implementation nào ở bản cũ (dead enum value), nên `TriggerDefinition` chỉ định nghĩa 4 loại đã thật sự hoạt động. Nếu sau này cần loại trigger tuỳ biến, thêm 1 `record` mới vào sealed interface (compiler sẽ báo mọi nơi `switch` cần cập nhật).

**Lý do đổi so với bản gốc:**
- `triggerType` (String) + field rời rạc (`cronExpression`/`intervalInDays`/`intervalInMinutes`/`repeatCount`) trên `Task` → gộp thành `TriggerDefinition` sealed interface, tránh set nhầm field không thuộc loại trigger đang dùng; Java 21 pattern matching xử lý gọn tại `QuartzTriggerFactory`.
- `taskConfigId: String` → `jobDefinitionId: UUID` (FK thật, validate ngay tại constructor).
- Không có domain model cho `HttpTypeTrigger`/`HttpTypeRequest`/`HttpTypeResponse` — đó là chi tiết triển khai riêng của `HttpCallJobAction` (infrastructure), domain không cần biết.

### Exception & Error code

```java
// domain/exception/ErrorCode.java — thuần domain, KHÔNG import Spring
public enum ErrorCode {
    TASK_NOT_FOUND("SRJ-001"),
    JOB_DEFINITION_NOT_FOUND("SRJ-002"),
    CRON_INVALID("SRJ-003"),
    VALIDATION_ERROR("SRJ-004");
    // messageKey khớp key trong resources/i18n/messages*.properties
}

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object[] messageArgs;   // nội suy message, thay cho MessageUtils.mapAttributes cũ
}
```

## 4. Presentation / API layer

Controller REST là "driving adapter", nằm trong `infrastructure/web/`, chỉ gọi vào `usecase/ports/in/*` — không bao giờ gọi thẳng repository hay Quartz. DTO ⇄ domain record được map ngay trong controller; domain model không lộ trực tiếp ra HTTP layer.

| Method | Path | Usecase gọi | Ghi chú |
|---|---|---|---|
| `POST` | `/api/tasks` | `TaskManagementUseCase.create` | |
| `GET` | `/api/tasks/search` | `TaskManagementUseCase.search` | |
| `GET` | `/api/tasks/{id}` | `TaskManagementUseCase.getDetail` | kèm `TriggerState` hiện tại |
| `POST` | `/api/tasks/start/{id}` | `TaskManagementUseCase.start` | |
| `PUT` | `/api/tasks/pause/{id}` | `TaskManagementUseCase.pause` | |
| `PUT` | `/api/tasks/resume/{id}` | `TaskManagementUseCase.resume` | |
| `DELETE` | `/api/tasks/{id}` | `TaskManagementUseCase.delete` | |
| `POST` | `/api/job-definitions` | `JobDefinitionUseCase.create` | thay `/api/task-config`, có nghiệp vụ thật |
| `PUT` | `/api/job-definitions/{id}` | `JobDefinitionUseCase.update` | **sửa bug**: bản cũ gọi nhầm `delete` |
| `DELETE` | `/api/job-definitions/{id}` | `JobDefinitionUseCase.delete` | mới thêm, bản cũ chưa có |
| `GET` | `/api/task-history/search` | `TaskHistoryQueryUseCase.search` | |

**Xử lý lỗi:** `GlobalExceptionHandler` (`infrastructure/common/`, `@RestControllerAdvice`) bắt `BusinessException`, map `ErrorCode → HttpStatus` qua bảng tra cục bộ (`*_NOT_FOUND` → 404, `VALIDATION_ERROR`/`CRON_INVALID` → 400), resolve message qua `MessageSource` chuẩn của Spring (đọc `resources/i18n/messages*.properties` sẵn có — không cần viết lại `Translator` riêng như bản cũ).

**Swagger/OpenAPI:** giữ `@Tag`/`@SecurityRequirement(bearerAuth)` như tài liệu mô tả (chưa làm auth thật, theo quyết định ở mục 1).

## 5. Usecase ports & services

```java
public interface TaskManagementUseCase {
    ScheduledTask create(CreateTaskCommand command);
    void start(UUID taskId);
    void pause(UUID taskId);
    void resume(UUID taskId);
    void delete(UUID taskId);
    void startAll();
    PageResult<ScheduledTask> search(String keyword, Pageable pageable);
    TaskDetail getDetail(UUID taskId);
}

public interface JobDefinitionUseCase {
    JobDefinition create(CreateJobDefinitionCommand command);
    JobDefinition update(UUID id, UpdateJobDefinitionCommand command);
    void delete(UUID id);
}

public interface TaskHistoryQueryUseCase {
    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}

public interface ExecuteScheduledJobUseCase {
    void execute(UUID taskId);
}

public interface TaskRepositoryPort {
    ScheduledTask save(ScheduledTask task);
    Optional<ScheduledTask> findById(UUID id);
    List<ScheduledTask> findAll();
    PageResult<ScheduledTask> search(String keyword, Pageable pageable);
    void delete(UUID id);
}

public interface JobDefinitionRepositoryPort {
    JobDefinition save(JobDefinition definition);
    Optional<JobDefinition> findById(UUID id);
    void delete(UUID id);
}

public interface TaskExecutionHistoryRepositoryPort {
    TaskExecutionRecord save(TaskExecutionRecord record);
    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}

public interface SchedulerGatewayPort {
    void scheduleTask(ScheduledTask task);
    void unscheduleTask(UUID taskId);
    void pauseTask(UUID taskId);
    void resumeTask(UUID taskId);
    TriggerState getTriggerState(UUID taskId);
}

public interface JobActionExecutorPort {
    void execute(JobDefinition definition);
}
```

**Service implementation:**

- `TaskOrchestrator implements TaskManagementUseCase` — trách nhiệm tương đương `TaskService` cũ, gọi `SchedulerGatewayPort` thay vì `org.quartz.Scheduler` trực tiếp.
- `JobDefinitionService implements JobDefinitionUseCase` — CRUD `JobDefinition`; đây là nơi sửa bug update (bản cũ `TaskConfigController.update` gọi nhầm `delete`).
- `TaskHistoryQueryService implements TaskHistoryQueryUseCase`.
- `JobExecutionOrchestrator implements ExecuteScheduledJobUseCase` — load `ScheduledTask` → load `JobDefinition` tương ứng → gọi `JobActionExecutorPort.execute(definition)`. Tách riêng khỏi `TaskOrchestrator` vì khác mối quan tâm (vòng đời task vs. thực thi job khi Quartz gọi tới). `ScheduledJobExecutor` (infra, `implements org.quartz.Job`) chỉ được phép gọi vào usecase in-port này — không tự ý load repository hay registry `JobAction` trực tiếp, giữ đúng dependency rule kể cả chiều "Quartz gọi ngược vào code".

## 6. Infrastructure layer

**Scheduler:**

```java
public class ScheduledJobExecutor extends QuartzJobBean {
    private ExecuteScheduledJobUseCase executeScheduledJobUseCase;  // autowired qua AutowiringSpringBeanJobFactory

    protected void executeInternal(JobExecutionContext context) {
        UUID taskId = UUID.fromString(context.getMergedJobDataMap().getString("taskId"));
        executeScheduledJobUseCase.execute(taskId);
    }
}

class QuartzSchedulerGatewayAdapter implements SchedulerGatewayPort {
    // luôn dùng ScheduledJobExecutor.class làm JobDetail, JobDataMap chỉ chứa taskId
    // unschedule/pause/resume/getTriggerState: logic tương đương JobService cũ
}

class QuartzTriggerFactory {
    Trigger build(ScheduledTask task) {
        return switch (task.trigger()) {
            case TriggerDefinition.Cron c -> /* CronScheduleBuilder, tương đương CronBuildService cũ */;
            case TriggerDefinition.Simple s -> /* SimpleScheduleBuilder */;
            case TriggerDefinition.CalendarInterval ci -> /* CalendarIntervalScheduleBuilder */;
            case TriggerDefinition.DailyTimeInterval d -> /* DailyTimeIntervalScheduleBuilder */;
        };
    }
}
```

`QuartzJobListener`/`QuartzJobTriggerListener` giữ vai trò global listener như bản cũ, chỉ đổi sang gọi `TaskExecutionHistoryRepositoryPort.save(...)`.

> Gộp 4 class `*BuildService` cũ thành 1 `QuartzTriggerFactory` dùng `switch` pattern-matching Java 21 (compiler đảm bảo xử lý đủ 4 case của sealed interface) — đơn giản hoá cấu trúc code, hành vi/kết quả vẫn giữ parity.

**Job actions (registry Strategy pattern):**

```java
public interface JobAction {
    boolean matches(String jobType);
    void execute(JobDefinition definition);
}

@Component
class JobActionRegistry implements JobActionExecutorPort {
    private final List<JobAction> actions;   // Spring inject tất cả bean implements JobAction
    public void execute(JobDefinition definition) {
        actions.stream().filter(a -> a.matches(definition.jobType())).findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, definition.jobType()))
            .execute(definition);
    }
}

@Component
class HttpCallJobAction implements JobAction {
    // matches("HTTP_CALL"); parse definition.expression() (JSON: url, method, headers...)
    // thực thi bằng RestClient chạy trên Virtual Thread executor — thay HttpCallFactory/HttpCallService/SystemCallService/UserCallService cũ
}
```

**Persistence:** Spring Data JPA — `TaskEntity`, `JobDefinitionEntity` (thay `TaskConfigEntity`, có FK thật từ `TaskEntity.jobDefinitionId`), `TaskExecutionHistoryEntity`; mỗi entity có 1 `*RepositoryAdapter implements *RepositoryPort` map entity ⇄ domain record.

**Config:** `QuartzClusterConfig` (giữ `AutowiringSpringBeanJobFactory`, bật `isClustered: true` thật theo profile production); `VirtualThreadConfig` cho executor gọi HTTP trong `HttpCallJobAction`.

## 7. Data model & Migration

Chuyển từ `ddl-auto: update` sang **Flyway** cho cả bảng nghiệp vụ lẫn bảng Quartz (`QRTZ_*`), gộp về 1 công cụ migration duy nhất — tránh việc `spring.quartz.jdbc.initialize-schema: always` không an toàn để chạy lại nhiều lần.

```text
src/main/resources/db/migration/
├── V1__create_quartz_tables.sql        # script chính thức QRTZ_* (PostgreSQL)
├── V2__create_job_definitions.sql
├── V3__create_tasks.sql
└── V4__create_task_execution_history.sql
```

```sql
-- V2
CREATE TABLE job_definitions (
    id UUID PRIMARY KEY,
    job_type VARCHAR(100) NOT NULL,
    expression TEXT,
    description VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE
);

-- V3
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    "group" VARCHAR(100) NOT NULL,
    job_definition_id UUID NOT NULL REFERENCES job_definitions(id),
    trigger_type VARCHAR(30) NOT NULL,
    cron_expression VARCHAR(100),
    interval_in_seconds INTEGER,
    repeat_count INTEGER,
    interval_in_days INTEGER,
    interval_in_minutes INTEGER,
    starting_daily_at TIME,
    ending_daily_at TIME,
    timezone_id VARCHAR(50),
    priority INTEGER,
    description VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_tasks_name ON tasks (name) WHERE is_deleted = FALSE;

-- V4
CREATE TABLE task_execution_history (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks(id),
    start_time TIMESTAMP WITH TIME ZONE,
    end_time TIMESTAMP WITH TIME ZONE,
    exception_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_execution_history_task_id ON task_execution_history (task_id);
```

## 8. Danh sách vấn đề của bản cũ được sửa trong project mới

1. **Reflection nạp class tuỳ ý từ DB** (`TaskUtil.getClazz(packageName, className)`) — thay bằng registry `JobAction` theo `jobType` (mục 6).
2. **`TaskConfigController.update` gọi nhầm `taskService.delete`** — sửa đúng thành update `JobDefinition` (mục 4, 5).
3. **`TaskConfig` là code chết** (không FK thật, không service nào dùng) — thiết kế lại thành `JobDefinition` có nghiệp vụ thật, FK thật từ `Task` (mục 1, 3, 7).
4. **Soft-delete thiếu filter đọc** (`@SQLDelete` có nhưng không có `@SQLRestriction`/`@Where`, bản ghi đã xoá vẫn có thể được query trả về) — thêm `@SQLRestriction("is_deleted = false")` trên entity (mục 7).
5. **`spring.jpa.hibernate.ddl-auto: update`** — chuyển sang Flyway migration (mục 7).
6. **`spring.quartz.jdbc.initialize-schema: always`** — chuyển sang Flyway migration cho bảng `QRTZ_*` luôn, bỏ auto-init của Quartz starter (mục 7).
7. **`isClustered: false`** dù hạ tầng dùng JDBC JobStore — bật `true` thật theo profile production (mục 6).
8. **`management.endpoints.web.exposure.include: '*'`** — siết lại chỉ expose `health,info,prometheus` mặc định.
9. **Thiếu test cho các thành phần lõi** (`JobService`/`TriggerFactory`/`AppCommonJob` cũ, tương đương `SchedulerGatewayPort`/`QuartzTriggerFactory`/`JobActionRegistry` mới) — bắt buộc có test ngay từ đầu (mục 9).

## 9. Testing strategy

| Tầng | Cách test | Công cụ |
|---|---|---|
| `usecase/service/*` | Unit test thuần Java, mock các port bằng Mockito — không cần Spring context | JUnit 5, Mockito |
| `infrastructure/persistence/*Adapter` | Integration test với DB thật, verify mapping + `@SQLRestriction` | Testcontainers PostgreSQL |
| `QuartzSchedulerGatewayAdapter` + `QuartzTriggerFactory` | Integration test với Quartz thật (`RAMJobStore`, profile test) — schedule trigger interval ngắn, assert `ExecuteScheduledJobUseCase` được gọi | Quartz RAMJobStore, Awaitility |
| `web/controller/*` | Verify mapping request/response + `GlobalExceptionHandler` | `@WebMvcTest`, mock usecase port |
| End-to-end | Tạo Task+JobDefinition qua REST → start → chờ trigger bắn → assert có `TaskExecutionHistory` | Full context + Testcontainers Postgres |

## 10. Tech stack / pom.xml

Project độc lập, **không** kế thừa parent pom của monorepo hiện tại.

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>
<groupId>com.corebanking</groupId>
<artifactId>system-report-job</artifactId>
<properties>
    <java.version>21</java.version>
</properties>
```

Dependencies chính: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-quartz`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core` + `flyway-database-postgresql`, `org.projectlombok:lombok`, `com.cronutils:cron-utils`, `org.springdoc:springdoc-openapi-starter-webmvc-ui` (để `@Tag`/`@SecurityRequirement` tiếp tục hoạt động). Test: `spring-boot-starter-test`, `org.testcontainers:postgresql` + `junit-jupiter`, `awaitility`.

Giữ `spotless-maven-plugin` (palantir-java-format, import order, tabs) như bản cũ để đồng bộ style.

> **Lưu ý khi bắt tay vào code:** cần kiểm tra release notes chính thức của Spring Boot 4.1 / Spring Framework 7 tại thời điểm triển khai (baseline Java tối thiểu, API/annotation bị loại bỏ so với 3.4.x, thay đổi ở Spring Data JPA/Hibernate, Quartz starter) vì đây là bản mới và chi tiết breaking-change có thể khác so với giả định trong spec này.

## 11. Ngoài phạm vi (Out of scope)

- Spring Security / xác thực JWT thật trong service này (giữ nguyên giả định gateway/service khác đảm nhiệm).
- Migrate dữ liệu thật từ database `db_system_job` cũ sang schema mới (cần một kế hoạch migration dữ liệu riêng nếu/khi thật sự thay thế module cũ).
- Thư viện `common-cores` dùng chung monorepo — không đụng tới, project mới độc lập hoàn toàn.
