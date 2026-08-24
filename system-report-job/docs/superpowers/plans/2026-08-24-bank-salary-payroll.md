# Bank Salary Payroll Sample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable `BANK_SALARY_PAYROLL` sample: a Quartz-scheduled, `bankHolidays`-aware, 3-step Spring Batch job that holds funds from a company account, disburses to ~30,000 employees from a CSV (skipping bad records without failing the run), then logs a summary notification.

**Architecture:** One small, generic addition to the shared Task/Quartz engine (`calendarName` on `ScheduledTask`, wired into `QuartzTriggerFactory` via `TriggerBuilder.modifiedByCalendar`) so the existing `bankHolidays` Quartz calendar can actually be attached to a trigger. On top of that, a new `JobAction` (`PayrollJobAction`, jobType `BANK_SALARY_PAYROLL`) fires on a daily Cron trigger, resolves whether today is the 19th-or-next-working-day via the existing `HolidayQueryUseCase.getNextWorkingDay(...)`, and — only on that day — launches a 3-step Spring Batch job (`PayrollBatchConfig`) via `JobOperator`, following the exact same shape as `BankingEodJobAction`/`SpringBatchJobAction`.

**Tech Stack:** Spring Boot 4.1, Java 21, Spring Batch **6.0.4** (packages under `org.springframework.batch.core.*` and `org.springframework.batch.infrastructure.*` — verified against the actual jars in `~/.m2`, not assumed from memory), Quartz 2.5.2, PostgreSQL/Flyway, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md`

## Global Constraints

- `domain/` must never import Spring, JPA, or Quartz types (CLAUDE.md).
- Schema changes go through `src/main/resources/db/migration/` only — never `ddl-auto` (CLAUDE.md).
- `mvn spotless:apply` must be run (and clean) before any commit that touches `.java` files — `spotless:check` gates the `compile` phase.
- New `ErrorCode` values must be added to `GlobalExceptionHandler.statusFor(ErrorCode)`'s exhaustive `switch` — not needed by this plan (no new `ErrorCode` is introduced; failures use `IllegalStateException`, matching `BankingEodJobAction`/`SpringBatchJobAction`).
- `ScheduledJobExecutor` remains the only `org.quartz.Job` in the system — the new job type is a `JobAction`, never a bespoke `QuartzJobBean`.
- All Spring Batch imports must come from the verified 6.0.4 packages listed per-task below — the pre-existing draft in `batch/banksalaryfxt/BatchReaderConfig.java` uses the wrong (pre-6.x) package names and would not compile; do not copy its imports.

---

## Task 1: Attach a Quartz calendar name to a Task (shared scheduling engine)

**Files:**
- Modify: `src/main/java/com/system/reportjob/domain/model/ScheduledTask.java`
- Modify: `src/main/java/com/system/reportjob/usecase/ports/in/CreateTaskCommand.java`
- Modify: `src/main/java/com/system/reportjob/infrastructure/web/dto/request/CreateTaskRequest.java`
- Modify: `src/main/java/com/system/reportjob/infrastructure/web/controller/TaskController.java`
- Modify: `src/main/java/com/system/reportjob/infrastructure/persistence/entity/TaskEntity.java`
- Modify: `src/main/java/com/system/reportjob/infrastructure/persistence/adapter/TaskRepositoryAdapter.java`
- Modify: `src/main/java/com/system/reportjob/infrastructure/scheduler/QuartzTriggerFactory.java`
- Create: `src/main/resources/db/migration/V10__add_calendar_name_to_tasks.sql`
- Test: `src/test/java/com/system/reportjob/infrastructure/scheduler/QuartzTriggerFactoryTest.java` (add one test)
- Test: `src/test/java/com/system/reportjob/infrastructure/persistence/adapter/TaskRepositoryAdapterTest.java` (add one test)

**Interfaces:**
- Produces: `ScheduledTask.calendarName()` (nullable `String`, new field after `trigger`); `ScheduledTask` keeps a secondary 8-arg constructor `(id, name, group, jobDefinitionId, trigger, timezoneId, priority, description)` that defaults `calendarName` to `null`, so none of the ~10 existing test call sites using the old constructor need to change. `CreateTaskCommand.calendarName()` the same way (secondary 7-arg constructor defaulting to `null`).
- Consumes (later tasks): nothing from this repo's payroll code — this task is pure shared-engine plumbing, consumed only via the documented API in Task 9 (`docs/bank-salary-sample/running-the-sample.md`, `"calendarName": "bankHolidays"`).

- [ ] **Step 1: Add `calendarName` to `ScheduledTask` with a backward-compatible constructor**

Replace the full file:

```java
package com.system.reportjob.domain.model;

import java.util.UUID;

public record ScheduledTask(
        UUID id,
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String calendarName,
        String timezoneId,
        Integer priority,
        String description) {
    public ScheduledTask {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên task không được rỗng");
        }
        if (jobDefinitionId == null) {
            throw new IllegalArgumentException("Task phải gắn với một JobDefinition");
        }
    }

    /**
     * Tương thích ngược với code hiện có chưa biết tới calendarName (Quartz Calendar đã đăng ký,
     * vd "bankHolidays" — xem HolidayCalendarLoader). Mặc định null = không gắn calendar nào,
     * đúng hành vi hiện tại.
     */
    public ScheduledTask(
            UUID id,
            String name,
            String group,
            UUID jobDefinitionId,
            TriggerDefinition trigger,
            String timezoneId,
            Integer priority,
            String description) {
        this(id, name, group, jobDefinitionId, trigger, null, timezoneId, priority, description);
    }
}
```

- [ ] **Step 2: Run the existing domain/scheduler/persistence test suites to confirm nothing broke**

Run: `mvn test -Dtest=ScheduledTaskTest,QuartzTriggerFactoryTest,TaskOrchestratorTest,QuartzSchedulerGatewayAdapterTest,TaskControllerTest`
Expected: PASS (all existing 8-arg `new ScheduledTask(...)` call sites still compile via the new secondary constructor).

- [ ] **Step 3: Write the failing test for `QuartzTriggerFactory` attaching the calendar**

Add to `QuartzTriggerFactoryTest.java` (after the existing `taskWith` helpers, before the first `@Test`):

```java
    private ScheduledTask taskWithCalendar(TriggerDefinition trigger, String calendarName) {
        return new ScheduledTask(
                UUID.randomUUID(), "t", "g", UUID.randomUUID(), trigger, calendarName, "UTC", 5, null);
    }
```

Add this test method:

```java
    @Test
    void attachesTheCalendarWhenTaskHasACalendarName() {
        Trigger trigger =
                factory.build(taskWithCalendar(new TriggerDefinition.Cron("0 0 8 * * ?"), "bankHolidays"));

        assertThat(trigger.getCalendarName()).isEqualTo("bankHolidays");
    }

    @Test
    void leavesCalendarNameNullWhenTaskHasNone() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.Cron("0 0 8 * * ?")));

        assertThat(trigger.getCalendarName()).isNull();
    }
```

- [ ] **Step 4: Run it to verify it fails**

Run: `mvn test -Dtest=QuartzTriggerFactoryTest#attachesTheCalendarWhenTaskHasACalendarName`
Expected: FAIL — `trigger.getCalendarName()` returns `null` (factory doesn't set it yet).

- [ ] **Step 5: Implement — `QuartzTriggerFactory.build()` attaches the calendar**

In `QuartzTriggerFactory.java`, change the start of `build(...)`:

```java
    public Trigger build(ScheduledTask task) {
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(QuartzIdentifiers.triggerKey(task.id()))
                .forJob(QuartzIdentifiers.jobKey(task.id()))
                .startAt(new Date());
        if (task.priority() != null) {
            builder = builder.withPriority(task.priority());
        }
        if (task.calendarName() != null && !task.calendarName().isBlank()) {
            builder = builder.modifiedByCalendar(task.calendarName());
        }

        return switch (task.trigger()) {
```

(the rest of the method — the `switch` over `TriggerDefinition` — is unchanged).

- [ ] **Step 6: Run it to verify it passes**

Run: `mvn test -Dtest=QuartzTriggerFactoryTest`
Expected: PASS (all 6 tests, including the 2 new ones).

- [ ] **Step 7: Add the `calendar_name` migration**

Create `src/main/resources/db/migration/V10__add_calendar_name_to_tasks.sql`:

```sql
-- Cho phép gắn 1 Quartz Calendar đã đăng ký (vd "bankHolidays", xem HolidayCalendarLoader) vào
-- trigger của 1 Task, để Quartz tự động không fire trigger vào các ngày calendar loại trừ
-- (cuối tuần/ngày lễ). NULL = không gắn calendar nào (hành vi hiện tại, không đổi).
ALTER TABLE tasks ADD COLUMN calendar_name VARCHAR(100);
```

- [ ] **Step 8: Add `calendarName` to `TaskEntity`**

In `TaskEntity.java`, add this field (anywhere among the other `@Column` fields, e.g. right after `triggerType`):

```java
    @Column(name = "calendar_name")
    private String calendarName;
```

(`@Getter`/`@Setter` on the class already generate `getCalendarName()`/`setCalendarName()` — no further code needed.)

- [ ] **Step 9: Write the failing persistence round-trip test**

Add to `TaskRepositoryAdapterTest.java`, right after `roundTripsACronTriggerTask()`:

```java
    @Test
    void roundTripsATaskWithACalendarName() {
        ScheduledTask saved = adapter.save(
                new ScheduledTask(
                        UUID.randomUUID(),
                        "daily-report",
                        "reports",
                        sample(new TriggerDefinition.Cron("0 0 8 * * ?")).jobDefinitionId(),
                        new TriggerDefinition.Cron("0 0 8 * * ?"),
                        "bankHolidays",
                        "UTC",
                        5,
                        "desc"));

        assertThat(adapter.findById(saved.id())).contains(saved);
    }
```

Note: `sample(trigger).jobDefinitionId()` is used only to get a valid, already-persisted `JobDefinition` id (calling `sample(...)` saves a fresh `JobDefinitionEntity` as a side effect, per the existing helper) — the `ScheduledTask` returned by `sample(...)` itself is discarded, only its `jobDefinitionId()` is reused.

- [ ] **Step 10: Run it to verify it fails**

Run: `mvn test -Dtest=TaskRepositoryAdapterTest#roundTripsATaskWithACalendarName`
Expected: FAIL — compile error (`TaskRepositoryAdapter.toEntity`/`toDomain` don't handle the 9-arg constructor's `calendarName` yet — actually it will compile since the 9-arg canonical constructor already exists after Step 1; it will instead fail at assertion time because the round-tripped task's `calendarName()` comes back `null`). Docker must be running (Testcontainers).

- [ ] **Step 11: Implement — map `calendarName` in `TaskRepositoryAdapter`**

In `toEntity(...)`, add one line after `entity.setDescription(task.description());`:

```java
        entity.setCalendarName(task.calendarName());
```

In `toDomain(...)`, add `entity.getCalendarName()` to the `ScheduledTask` constructor call, right after `trigger`:

```java
        return new ScheduledTask(
                entity.getId(),
                entity.getName(),
                entity.getTaskGroup(),
                entity.getJobDefinitionId(),
                trigger,
                entity.getCalendarName(),
                entity.getTimezoneId(),
                entity.getPriority(),
                entity.getDescription());
```

- [ ] **Step 12: Run it to verify it passes**

Run: `mvn test -Dtest=TaskRepositoryAdapterTest`
Expected: PASS (all tests, including the new one). Docker must be running.

- [ ] **Step 13: Wire `calendarName` through `CreateTaskCommand`, `CreateTaskRequest`, and `TaskController`**

Replace `CreateTaskCommand.java`:

```java
package com.system.reportjob.usecase.ports.in;

import java.util.UUID;

import com.system.reportjob.domain.model.TriggerDefinition;

public record CreateTaskCommand(
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String calendarName,
        String timezoneId,
        Integer priority,
        String description) {

    /** Tương thích ngược với code hiện có chưa truyền calendarName (mặc định null). */
    public CreateTaskCommand(
            String name,
            String group,
            UUID jobDefinitionId,
            TriggerDefinition trigger,
            String timezoneId,
            Integer priority,
            String description) {
        this(name, group, jobDefinitionId, trigger, null, timezoneId, priority, description);
    }
}
```

In `CreateTaskRequest.java`, add one field to the record (after `endingDailyAt`, before `timezoneId`):

```java
        LocalTime endingDailyAt,
        String calendarName,
        String timezoneId,
```

In `TaskController.java`, in `toCommand(...)`, change the `CreateTaskCommand` construction to pass it through:

```java
        return new CreateTaskCommand(
                request.name(),
                request.group(),
                request.jobDefinitionId(),
                trigger,
                request.calendarName(),
                request.timezoneId(),
                request.priority(),
                request.description());
```

In `TaskOrchestrator.java`, in `create(...)`, change the `ScheduledTask` construction the same way:

```java
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                command.name(),
                command.group(),
                command.jobDefinitionId(),
                command.trigger(),
                command.calendarName(),
                command.timezoneId(),
                command.priority(),
                command.description());
```

- [ ] **Step 14: Run the full existing task-related test suite**

Run: `mvn test -Dtest=ScheduledTaskTest,QuartzTriggerFactoryTest,TaskOrchestratorTest,TaskControllerTest,TaskRepositoryAdapterTest,QuartzSchedulerGatewayAdapterTest,JobExecutionOrchestratorTest`
Expected: PASS.

- [ ] **Step 15: Format and commit**

Run: `mvn spotless:apply`

```bash
git add src/main/java/com/system/reportjob/domain/model/ScheduledTask.java \
  src/main/java/com/system/reportjob/usecase/ports/in/CreateTaskCommand.java \
  src/main/java/com/system/reportjob/infrastructure/web/dto/request/CreateTaskRequest.java \
  src/main/java/com/system/reportjob/infrastructure/web/controller/TaskController.java \
  src/main/java/com/system/reportjob/infrastructure/persistence/entity/TaskEntity.java \
  src/main/java/com/system/reportjob/infrastructure/persistence/adapter/TaskRepositoryAdapter.java \
  src/main/java/com/system/reportjob/infrastructure/scheduler/QuartzTriggerFactory.java \
  src/main/java/com/system/reportjob/usecase/service/TaskOrchestrator.java \
  src/main/resources/db/migration/V10__add_calendar_name_to_tasks.sql \
  src/test/java/com/system/reportjob/infrastructure/scheduler/QuartzTriggerFactoryTest.java \
  src/test/java/com/system/reportjob/infrastructure/persistence/adapter/TaskRepositoryAdapterTest.java
git commit -m "feat: attach a Quartz calendar name to a Task's trigger

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 2: Payroll database schema

**Files:**
- Create: `src/main/resources/db/migration/V11__create_payroll_tables.sql`

**Interfaces:**
- Produces: tables `fpt_company_account`, `gl_suspense_account`, `payroll_batch_run`, `payroll_disbursement` — consumed by `PayrollBatchConfig` (Task 5) via raw SQL (`JdbcTemplate`), and by `PayrollDisbursementWriter` (Task 5).
- Consumes: nothing (pure schema).

- [ ] **Step 1: Create the migration**

Create `src/main/resources/db/migration/V11__create_payroll_tables.sql`:

```sql
-- Bảng dữ liệu cho sample BANK_SALARY_PAYROLL (chuyển lương hàng loạt FPT Software), xem
-- docs/bank-salary-sample/bank-salary-sample.md và
-- docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md.

-- Tài khoản nguồn của công ty (FPT Software) tại TPBank.
CREATE TABLE fpt_company_account (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(30) NOT NULL UNIQUE,
    account_number VARCHAR(20) NOT NULL,
    balance NUMERIC(18, 2) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tài khoản trung gian nội bộ TPBank, giữ tiền đã trừ của công ty trước khi giải ngân xong hết.
CREATE TABLE gl_suspense_account (
    id BIGSERIAL PRIMARY KEY,
    account_code VARCHAR(30) NOT NULL UNIQUE,
    balance NUMERIC(18, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 1 dòng / kỳ lương (company_code + target_pay_date). unique constraint đảm bảo không giữ tiền
-- 2 lần cho cùng 1 kỳ nếu job vô tình chạy lại đúng ngày.
CREATE TABLE payroll_batch_run (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(30) NOT NULL,
    target_pay_date DATE NOT NULL,
    total_employees INT NOT NULL,
    total_amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- HOLD_SUCCESS, COMPLETED
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT unique_payroll_run_per_period UNIQUE (company_code, target_pay_date)
);

-- 1 dòng / nhân viên / kỳ lương — mọi dòng trong CSV đầu vào đều có đúng 1 dòng kết quả ở đây,
-- kể cả những dòng bị skip do lỗi (status = SKIPPED), không dòng nào bị bỏ qua âm thầm.
CREATE TABLE payroll_disbursement (
    id BIGSERIAL PRIMARY KEY,
    batch_run_id BIGINT NOT NULL REFERENCES payroll_batch_run (id),
    employee_id VARCHAR(30) NOT NULL,
    account_number VARCHAR(20) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- SUCCESS, SKIPPED
    error_reason VARCHAR(255),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payroll_disbursement_run ON payroll_disbursement (batch_run_id, status);

-- Seed: đủ số dư để giải ngân cho ~30.000 nhân viên (~60 triệu VND/người tối đa) chạy được ngay
-- sau "mvn spring-boot:run" mà không cần setup DB thủ công.
INSERT INTO fpt_company_account (company_code, account_number, balance)
VALUES ('FPT_SOFTWARE', '9999000111222', 500000000000.00);

-- Seed: tài khoản GL trung gian bắt đầu ở 0 — bắt buộc phải có sẵn dòng này vì Step 1/Step 2 chỉ
-- UPDATE (không INSERT ... ON CONFLICT), một UPDATE khớp 0 dòng sẽ âm thầm không giữ/trừ tiền.
INSERT INTO gl_suspense_account (account_code, balance)
VALUES ('PAYROLL_SUSPENSE_GL', 0);
```

- [ ] **Step 2: Verify the migration applies cleanly**

Run: `mvn test -Dtest=HolidayRepositoryAdapterTest` (any Testcontainers-backed test boots Flyway against a fresh Postgres container, which exercises every migration file including this new one).
Expected: PASS. If it fails with a Flyway checksum/syntax error, fix the SQL and re-run.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/migration/V11__create_payroll_tables.sql
git commit -m "feat: add payroll database schema for BANK_SALARY_PAYROLL sample

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 3: Clean up the draft package, add payroll DTOs

**Files:**
- Delete: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt/BatchReaderConfig.java`
- Delete: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt/PayrollQuartzJob.java`
- Delete: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt/PayrollScheduler.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollCsvRecord.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollDisbursementRecord.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationException.java`

**Interfaces:**
- Produces: `PayrollCsvRecord(String employeeId, String accountNumber, String fullName, BigDecimal salaryAmount)`, `PayrollDisbursementRecord(String employeeId, String accountNumber, String fullName, BigDecimal amount)`, `PayrollValidationException extends RuntimeException`. Consumed by Tasks 4 and 5.
- Consumes: nothing.

- [ ] **Step 1: Delete the draft package**

```bash
git rm src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt/BatchReaderConfig.java \
  src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt/PayrollQuartzJob.java \
  src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt/PayrollScheduler.java
```

(These 3 files predate this plan and were never wired into any `@Configuration`/`@Component` scan path beyond themselves — deleting them does not affect any other class. They extend `QuartzJobBean` directly and use Spring Batch import paths that don't exist in this project's actual Spring Batch 6.0.4, so the module builds the same or better without them.)

- [ ] **Step 2: Create the CSV record DTO**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollCsvRecord.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;

/** 1 dòng thô đọc từ file CSV lương FPT Software — employeeId,accountNumber,fullName,salaryAmount. */
public record PayrollCsvRecord(String employeeId, String accountNumber, String fullName, BigDecimal salaryAmount) {}
```

- [ ] **Step 3: Create the processed record DTO**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollDisbursementRecord.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;

/** Record đã qua validate, sẵn sàng ghi vào payroll_disbursement với status = SUCCESS. */
public record PayrollDisbursementRecord(String employeeId, String accountNumber, String fullName, BigDecimal amount) {}
```

- [ ] **Step 4: Create the validation exception**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationException.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

/** Ném ra khi 1 dòng CSV không hợp lệ (số TK sai định dạng, lương <= 0) — kích hoạt skip ở disburseStep. */
public class PayrollValidationException extends RuntimeException {
    public PayrollValidationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Verify the module still compiles**

Run: `mvn compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Format and commit**

Run: `mvn spotless:apply`

```bash
git add -A src/main/java/com/system/reportjob/infrastructure/jobactions/batch/banksalaryfxt \
  src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll
git commit -m "refactor: replace banksalaryfxt draft with payroll package DTOs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 4: `PayrollValidationProcessor` (Step 2 validation logic)

**Files:**
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationProcessor.java`
- Test: `src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationProcessorTest.java`

**Interfaces:**
- Consumes: `PayrollCsvRecord`, `PayrollDisbursementRecord`, `PayrollValidationException` (Task 3).
- Produces: `PayrollValidationProcessor implements ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord>` — `process(PayrollCsvRecord)` returns `PayrollDisbursementRecord` or throws `PayrollValidationException`. Consumed by `PayrollBatchConfig` (Task 5) as a `@Bean`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationProcessorTest.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PayrollValidationProcessorTest {

    private final PayrollValidationProcessor processor = new PayrollValidationProcessor();

    @Test
    void mapsAValidRecordThrough() throws Exception {
        PayrollCsvRecord input =
                new PayrollCsvRecord("FPT000001", "9000000001234", "Nguyen Van A", new BigDecimal("15000000"));

        PayrollDisbursementRecord result = processor.process(input);

        assertThat(result.employeeId()).isEqualTo("FPT000001");
        assertThat(result.accountNumber()).isEqualTo("9000000001234");
        assertThat(result.fullName()).isEqualTo("Nguyen Van A");
        assertThat(result.amount()).isEqualByComparingTo("15000000");
    }

    @Test
    void rejectsAMalformedAccountNumber() {
        PayrollCsvRecord input = new PayrollCsvRecord("FPT000002", "BAD-ACCOUNT", "Nguyen Van B", new BigDecimal("15000000"));

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(PayrollValidationException.class)
                .hasMessageContaining("FPT000002");
    }

    @Test
    void rejectsANonPositiveSalary() {
        PayrollCsvRecord input = new PayrollCsvRecord("FPT000003", "9000000001234", "Nguyen Van C", BigDecimal.ZERO);

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(PayrollValidationException.class)
                .hasMessageContaining("FPT000003");
    }

    @Test
    void rejectsANullSalary() {
        PayrollCsvRecord input = new PayrollCsvRecord("FPT000004", "9000000001234", "Nguyen Van D", null);

        assertThatThrownBy(() -> processor.process(input)).isInstanceOf(PayrollValidationException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=PayrollValidationProcessorTest`
Expected: FAIL — `PayrollValidationProcessor` doesn't exist yet (compile error).

- [ ] **Step 3: Implement**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationProcessor.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Validate 1 dòng CSV lương: số tài khoản phải đúng định dạng, lương phải &gt; 0. Ném
 * {@link PayrollValidationException} khi không hợp lệ — disburseStep (xem PayrollBatchConfig)
 * cấu hình skip trên exception này nên 1 dòng lỗi không làm rollback cả chunk.
 */
public class PayrollValidationProcessor implements ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord> {

    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^\\d{10,14}$");

    @Override
    public PayrollDisbursementRecord process(PayrollCsvRecord item) {
        if (item.accountNumber() == null || !ACCOUNT_NUMBER_PATTERN.matcher(item.accountNumber()).matches()) {
            throw new PayrollValidationException(
                    "Số tài khoản không hợp lệ cho nhân viên " + item.employeeId() + ": " + item.accountNumber());
        }
        if (item.salaryAmount() == null || item.salaryAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayrollValidationException(
                    "Lương không hợp lệ cho nhân viên " + item.employeeId() + ": " + item.salaryAmount());
        }
        return new PayrollDisbursementRecord(
                item.employeeId(), item.accountNumber(), item.fullName(), item.salaryAmount());
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn test -Dtest=PayrollValidationProcessorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Format and commit**

Run: `mvn spotless:apply`

```bash
git add src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationProcessor.java \
  src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollValidationProcessorTest.java
git commit -m "feat: add PayrollValidationProcessor for payroll CSV row validation

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 5: `PayrollDisbursementWriter` and `PayrollBatchConfig` (the 3-step job)

**Files:**
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollDisbursementWriter.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollBatchConfig.java`

**Interfaces:**
- Consumes: `PayrollCsvRecord`, `PayrollDisbursementRecord`, `PayrollValidationException` (Task 3), `PayrollValidationProcessor` (Task 4), tables from Task 2.
- Produces: Spring bean `Job fptPayrollJob` — consumed by `PayrollJobAction` (Task 6) via constructor injection. Expected `JobParameters` (set by Task 6): `companyCode` (String), `targetPayDate` (String, ISO `yyyy-MM-dd`), `csvFilePath` (String, absolute or working-directory-relative path), `runAt` (Long).

No dedicated test for this task: like `BankingEodBatchConfig` (the existing sample this mirrors — checked, it has zero test coverage for its tasklet SQL), this task's tasklet/chunk SQL wiring is exercised indirectly through the (mocked) `PayrollJobAction` test in Task 6 and is not covered by a Testcontainers batch-execution test, matching established precedent in this codebase.

Note on the chunk-step API: verified against the actual `spring-batch-core-6.0.4.jar` — the single-arg `.chunk(chunkSize)` (as already used by `UserExportBatchConfig` in this repo) returns `ChunkOrientedStepBuilder<I, O>`, a **unified** builder (not the classic `SimpleStepBuilder`/`FaultTolerantStepBuilder` split from older Spring Batch). On it: `.faultTolerant()` returns itself; `.skip(Class<? extends Throwable>...)` is varargs; skip listeners are registered via a dedicated `.skipListener(SkipListener<? super I, ? super O>)` method — **not** `.listener(...)`, which is reserved for `StepListener`/generic listeners. The code below uses this exact surface.

- [ ] **Step 1: Create `PayrollDisbursementWriter`**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollDisbursementWriter.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ghi các record giải ngân thành công (SUCCESS) vào payroll_disbursement và trừ dần
 * gl_suspense_account. Đóng vai trò kép là SkipListener: khi processor skip 1 dòng lỗi, ghi luôn
 * dòng đó vào payroll_disbursement với status = SKIPPED — mọi dòng trong CSV đầu vào đều có đúng
 * 1 dòng kết quả, không dòng nào bị bỏ qua âm thầm.
 */
public class PayrollDisbursementWriter
        implements ItemWriter<PayrollDisbursementRecord>, SkipListener<PayrollCsvRecord, PayrollDisbursementRecord> {

    private static final Logger log = LoggerFactory.getLogger(PayrollDisbursementWriter.class);

    private final JdbcTemplate jdbcTemplate;
    private final String companyCode;
    private final LocalDate targetPayDate;
    private Long batchRunId;

    public PayrollDisbursementWriter(JdbcTemplate jdbcTemplate, String companyCode, LocalDate targetPayDate) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyCode = companyCode;
        this.targetPayDate = targetPayDate;
    }

    @Override
    public void write(Chunk<? extends PayrollDisbursementRecord> chunk) {
        BigDecimal chunkTotal = BigDecimal.ZERO;
        for (PayrollDisbursementRecord item : chunk) {
            jdbcTemplate.update(
                    "INSERT INTO payroll_disbursement "
                            + "(batch_run_id, employee_id, account_number, full_name, amount, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'SUCCESS')",
                    resolveBatchRunId(),
                    item.employeeId(),
                    item.accountNumber(),
                    item.fullName(),
                    item.amount());
            chunkTotal = chunkTotal.add(item.amount());
        }
        if (chunkTotal.signum() > 0) {
            jdbcTemplate.update(
                    "UPDATE gl_suspense_account SET balance = balance - ?, updated_at = now() "
                            + "WHERE account_code = 'PAYROLL_SUSPENSE_GL'",
                    chunkTotal);
        }
    }

    @Override
    public void onSkipInProcess(PayrollCsvRecord item, Throwable t) {
        jdbcTemplate.update(
                "INSERT INTO payroll_disbursement "
                        + "(batch_run_id, employee_id, account_number, full_name, amount, status, error_reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'SKIPPED', ?)",
                resolveBatchRunId(),
                item.employeeId(),
                item.accountNumber(),
                item.fullName(),
                item.salaryAmount() == null ? BigDecimal.ZERO : item.salaryAmount(),
                t.getMessage());
        log.warn("[BANK_SALARY_PAYROLL] disburseStep - bỏ qua nhân viên {}: {}", item.employeeId(), t.getMessage());
    }

    private Long resolveBatchRunId() {
        if (batchRunId == null) {
            batchRunId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payroll_batch_run WHERE company_code = ? AND target_pay_date = ?",
                    Long.class,
                    companyCode,
                    targetPayDate);
        }
        return batchRunId;
    }
}
```

- [ ] **Step 2: Create `PayrollBatchConfig`**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollBatchConfig.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.mapping.RecordFieldSetMapper;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Sample job cho BANK_SALARY_PAYROLL (chuyển lương hàng loạt FPT Software), theo
 * docs/bank-salary-sample/bank-salary-sample.md: holdFundsStep (tasklet) -&gt; disburseStep
 * (chunk, có skip) -&gt; notifyStep (tasklet). Đăng ký qua {@link PayrollJobAction}.
 */
@Configuration
public class PayrollBatchConfig {

    private static final Logger log = LoggerFactory.getLogger(PayrollBatchConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public PayrollBatchConfig(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Bean
    @StepScope
    public Tasklet holdFundsTasklet(
            @Value("#{jobParameters['companyCode']}") String companyCode,
            @Value("#{jobParameters['targetPayDate']}") String targetPayDate,
            @Value("#{jobParameters['csvFilePath']}") String csvFilePath) {
        return (contribution, chunkContext) -> {
            BigDecimal totalAmount = BigDecimal.ZERO;
            int employeeCount = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
                reader.readLine(); // bỏ dòng header
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    employeeCount++;
                    String[] cols = line.split(",", -1);
                    if (cols.length >= 4) {
                        try {
                            BigDecimal amount = new BigDecimal(cols[3].trim());
                            if (amount.signum() > 0) {
                                totalAmount = totalAmount.add(amount);
                            }
                        } catch (NumberFormatException ex) {
                            // Dòng lỗi định dạng số tiền - disburseStep sẽ skip khi xử lý, không
                            // tính vào tổng giữ tiền ở đây.
                        }
                    }
                }
            }

            BigDecimal balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM fpt_company_account WHERE company_code = ?", BigDecimal.class, companyCode);
            if (balance == null || balance.compareTo(totalAmount) < 0) {
                throw new IllegalStateException("Tài khoản " + companyCode + " không đủ số dư để trả lương: cần "
                        + totalAmount + ", hiện có " + balance);
            }

            jdbcTemplate.update(
                    "UPDATE fpt_company_account SET balance = balance - ?, updated_at = now() WHERE company_code = ?",
                    totalAmount,
                    companyCode);
            jdbcTemplate.update(
                    "UPDATE gl_suspense_account SET balance = balance + ?, updated_at = now() "
                            + "WHERE account_code = 'PAYROLL_SUSPENSE_GL'",
                    totalAmount);
            jdbcTemplate.update(
                    "INSERT INTO payroll_batch_run "
                            + "(company_code, target_pay_date, total_employees, total_amount, status) "
                            + "VALUES (?, ?, ?, ?, 'HOLD_SUCCESS')",
                    companyCode,
                    LocalDate.parse(targetPayDate),
                    employeeCount,
                    totalAmount);

            log.info(
                    "[BANK_SALARY_PAYROLL] holdFundsStep - đã giữ {} VND cho {} nhân viên (company={}, targetPayDate={})",
                    totalAmount,
                    employeeCount,
                    companyCode,
                    targetPayDate);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step holdFundsStep(
            JobRepository jobRepository, PlatformTransactionManager transactionManager, Tasklet holdFundsTasklet) {
        return new StepBuilder("holdFundsStep", jobRepository)
                .tasklet(holdFundsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<PayrollCsvRecord> csvEmployeeReader(
            @Value("#{jobParameters['csvFilePath']}") String csvFilePath) {
        return new FlatFileItemReaderBuilder<PayrollCsvRecord>()
                .name("csvEmployeeReader")
                .resource(new FileSystemResource(csvFilePath))
                .delimited()
                .delimiter(",")
                .names("employeeId", "accountNumber", "fullName", "salaryAmount")
                .fieldSetMapper(new RecordFieldSetMapper<>(PayrollCsvRecord.class))
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord> payrollValidationProcessor() {
        return new PayrollValidationProcessor();
    }

    @Bean
    @StepScope
    public PayrollDisbursementWriter payrollDisbursementWriter(
            @Value("#{jobParameters['companyCode']}") String companyCode,
            @Value("#{jobParameters['targetPayDate']}") String targetPayDate) {
        return new PayrollDisbursementWriter(jdbcTemplate, companyCode, LocalDate.parse(targetPayDate));
    }

    @Bean
    public Step disburseStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<PayrollCsvRecord> csvEmployeeReader,
            ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord> payrollValidationProcessor,
            PayrollDisbursementWriter payrollDisbursementWriter,
            @Value("${app.batch.payroll.chunk-size:500}") int chunkSize,
            @Value("${app.batch.payroll.skip-limit:1000}") int skipLimit) {
        return new StepBuilder("disburseStep", jobRepository)
                .<PayrollCsvRecord, PayrollDisbursementRecord>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(csvEmployeeReader)
                .processor(payrollValidationProcessor)
                .writer(payrollDisbursementWriter)
                .faultTolerant()
                .skip(PayrollValidationException.class)
                .skipLimit(skipLimit)
                .skipListener(payrollDisbursementWriter)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet notifyTasklet(
            @Value("#{jobParameters['companyCode']}") String companyCode,
            @Value("#{jobParameters['targetPayDate']}") String targetPayDate) {
        return (contribution, chunkContext) -> {
            LocalDate payDate = LocalDate.parse(targetPayDate);
            Long batchRunId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payroll_batch_run WHERE company_code = ? AND target_pay_date = ?",
                    Long.class,
                    companyCode,
                    payDate);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, COUNT(*) AS cnt FROM payroll_disbursement WHERE batch_run_id = ? GROUP BY status",
                    batchRunId);

            long success = 0;
            long skipped = 0;
            for (Map<String, Object> row : rows) {
                long count = ((Number) row.get("cnt")).longValue();
                if ("SUCCESS".equals(row.get("status"))) {
                    success = count;
                } else if ("SKIPPED".equals(row.get("status"))) {
                    skipped = count;
                }
            }

            log.info(
                    "[BANK_SALARY_PAYROLL] notifyStep - Kỳ lương {} ({}): {}/{} nhân viên nhận lương thành công,"
                            + " {} bị skip do lỗi",
                    targetPayDate,
                    companyCode,
                    success,
                    success + skipped,
                    skipped);

            jdbcTemplate.update(
                    "UPDATE payroll_batch_run SET status = 'COMPLETED', completed_at = now() WHERE id = ?",
                    batchRunId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step notifyStep(
            JobRepository jobRepository, PlatformTransactionManager transactionManager, Tasklet notifyTasklet) {
        return new StepBuilder("notifyStep", jobRepository)
                .tasklet(notifyTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job fptPayrollJob(JobRepository jobRepository, Step holdFundsStep, Step disburseStep, Step notifyStep) {
        return new JobBuilder("fptPayrollJob", jobRepository)
                .start(holdFundsStep)
                .next(disburseStep)
                .next(notifyStep)
                .build();
    }
}
```

- [ ] **Step 3: Verify the module compiles**

Run: `mvn compile`
Expected: BUILD SUCCESS. If any import doesn't resolve, double-check it against the package names given in this task's code (they were verified against the actual `spring-batch-core-6.0.4.jar`/`spring-batch-infrastructure-6.0.4.jar` in `~/.m2`, not assumed).

- [ ] **Step 4: Format and commit**

Run: `mvn spotless:apply`

```bash
git add src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollDisbursementWriter.java \
  src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollBatchConfig.java
git commit -m "feat: add PayrollBatchConfig (hold funds / disburse / notify job)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 6: `PayrollJobAction` (Quartz-facing entry point, pay-date resolution)

**Files:**
- Create: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobAction.java`
- Test: `src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobActionTest.java`

**Interfaces:**
- Consumes: `Job fptPayrollJob` (Task 5, injected — tests mock it directly, matching `SpringBatchJobActionTest`), `HolidayQueryUseCase.getNextWorkingDay(LocalDate, String, String)` (existing, `usecase/ports/in/HolidayQueryUseCase.java`), `JobDefinition.expression()` parsed as JSON `{"companyCode":"...","csvDirectory":"...","countryCode":"VN","branchId":"ALL"}`.
- Produces: `JobAction` bean matching jobType `BANK_SALARY_PAYROLL`; on the target pay date, launches `fptPayrollJob` with `JobParameters` `companyCode`, `targetPayDate`, `csvFilePath`, `runAt` — matching what `PayrollBatchConfig` (Task 5) reads via `#{jobParameters['...']}`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobActionTest.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.usecase.ports.in.HolidayQueryUseCase;

class PayrollJobActionTest {

    private final Job fptPayrollJob = mock(Job.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EXPRESSION =
            "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\",\"branchId\":\"ALL\"}";

    private PayrollJobAction newAction(JobOperator jobOperator, HolidayQueryUseCase holidayQueryUseCase) {
        return new PayrollJobAction(
                jobOperator,
                fptPayrollJob,
                holidayQueryUseCase,
                objectMapper,
                new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()),
                Duration.ofSeconds(30));
    }

    @Test
    void matchesOnlyBankSalaryPayrollJobType() {
        PayrollJobAction action = newAction(mock(JobOperator.class), mock(HolidayQueryUseCase.class));

        assertThat(action.matches("BANK_SALARY_PAYROLL")).isTrue();
        assertThat(action.matches("HTTP_CALL")).isFalse();
    }

    @Test
    void skipsLaunchingTheBatchJobWhenTodayIsNotTheTargetPayDate() {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now().plusDays(1));
        JobOperator jobOperator = mock(JobOperator.class);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", EXPRESSION, null);

        action.execute(definition);

        verify(jobOperator, never()).start(any(), any());
    }

    @Test
    void launchesTheBatchJobWhenTodayIsTheTargetPayDate() {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution completed = mock(JobExecution.class);
        when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(completed.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobOperator.start(eq(fptPayrollJob), any(JobParameters.class))).thenReturn(completed);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", EXPRESSION, null);

        action.execute(definition);

        verify(jobOperator).start(eq(fptPayrollJob), any(JobParameters.class));
    }

    @Test
    void throwsWhenTheBatchJobDoesNotComplete() {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution failed = mock(JobExecution.class);
        when(failed.getStatus()).thenReturn(BatchStatus.FAILED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(failed);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", EXPRESSION, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=PayrollJobActionTest`
Expected: FAIL — `PayrollJobAction` doesn't exist yet (compile error).

- [ ] **Step 3: Implement**

Create `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobAction.java`:

```java
package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.infrastructure.jobactions.JobAction;
import com.system.reportjob.usecase.ports.in.HolidayQueryUseCase;

/**
 * Job action cho jobType BANK_SALARY_PAYROLL. Trigger Quartz nên là 1 Cron chạy hàng ngày, gắn
 * calendar "bankHolidays" (xem Task 1 / QuartzTriggerFactory) để Quartz không fire vào cuối
 * tuần/ngày lễ; class này tự quyết định thêm xem HÔM NAY có đúng là ngày trả lương không (ngày
 * 19, hoặc ngày làm việc gần nhất sau đó nếu 19 rơi vào ngày nghỉ) trước khi thực sự chạy batch.
 */
@Component
public class PayrollJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(PayrollJobAction.class);
    private static final String PAY_DAY_OF_MONTH_START = "19";

    private final JobOperator jobOperator;
    private final Job fptPayrollJob;
    private final HolidayQueryUseCase holidayQueryUseCase;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor jobActionTaskExecutor;
    private final Duration executionTimeout;

    public PayrollJobAction(
            JobOperator jobOperator,
            Job fptPayrollJob,
            HolidayQueryUseCase holidayQueryUseCase,
            ObjectMapper objectMapper,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
            @Value("${app.batch.payroll.execution-timeout:15m}") Duration executionTimeout) {
        this.jobOperator = jobOperator;
        this.fptPayrollJob = fptPayrollJob;
        this.holidayQueryUseCase = holidayQueryUseCase;
        this.objectMapper = objectMapper;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean matches(String jobType) {
        return "BANK_SALARY_PAYROLL".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        PayrollExpression expression;
        try {
            expression = objectMapper.readValue(definition.expression(), PayrollExpression.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "BANK_SALARY_PAYROLL expression không hợp lệ: " + definition.id(), e);
        }
        String countryCode = expression.countryCode() != null ? expression.countryCode() : "VN";
        String branchId = expression.branchId() != null ? expression.branchId() : "ALL";

        LocalDate today = LocalDate.now();
        LocalDate targetPayDate = resolveTargetPayDate(today, countryCode, branchId);
        if (!today.equals(targetPayDate)) {
            log.info(
                    "BANK_SALARY_PAYROLL: hôm nay ({}) chưa phải ngày trả lương (target={}), bỏ qua",
                    today,
                    targetPayDate);
            return;
        }

        Future<Void> future = jobActionTaskExecutor.submit(() -> runJob(definition, expression, targetPayDate));
        try {
            future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            throw new IllegalStateException(
                    "BANK_SALARY_PAYROLL job action thất bại: " + definition.id() + " (" + message + ")", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "BANK_SALARY_PAYROLL job action quá thời gian chờ (" + executionTimeout + "): "
                            + definition.id(),
                    e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BANK_SALARY_PAYROLL job action bị gián đoạn: " + definition.id(), e);
        }
    }

    /**
     * Ngày 19 hàng tháng nếu là ngày làm việc, ngược lại là ngày làm việc gần nhất sau đó.
     * getNextWorkingDay(start, ...) trả về ngày làm việc đầu tiên SAU start (không tính start),
     * nên truyền vào ngày 18 để nó trả về đúng ngày 19 khi 19 là ngày làm việc, hoặc ngày làm
     * việc kế tiếp nếu 19 rơi vào cuối tuần/lễ.
     */
    private LocalDate resolveTargetPayDate(LocalDate today, String countryCode, String branchId) {
        LocalDate the19th = YearMonth.from(today).atDay(Integer.parseInt(PAY_DAY_OF_MONTH_START));
        return holidayQueryUseCase.getNextWorkingDay(the19th.minusDays(1), countryCode, branchId);
    }

    private Void runJob(JobDefinition definition, PayrollExpression expression, LocalDate targetPayDate)
            throws Exception {
        String csvFilePath = expression.csvDirectory() + "/FPT_PAYROLL_" + targetPayDate + ".csv";

        JobParameters jobParameters = new JobParametersBuilder()
                .addString("companyCode", expression.companyCode())
                .addString("targetPayDate", targetPayDate.toString())
                .addString("csvFilePath", csvFilePath)
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobOperator.start(fptPayrollJob, jobParameters);
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException("BANK_SALARY_PAYROLL batch job kết thúc với trạng thái "
                    + execution.getStatus() + ": " + definition.id());
        }
        log.info(
                "BANK_SALARY_PAYROLL job {} hoàn tất cho kỳ lương {}, exitStatus={}",
                definition.id(),
                targetPayDate,
                execution.getExitStatus());
        return null;
    }

    record PayrollExpression(String companyCode, String csvDirectory, String countryCode, String branchId) {}
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `mvn test -Dtest=PayrollJobActionTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Full compile + format + commit**

Run: `mvn compile` then `mvn spotless:apply`

```bash
git add src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobAction.java \
  src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobActionTest.java
git commit -m "feat: add PayrollJobAction (BANK_SALARY_PAYROLL entry point)

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 7: Configuration

**Files:**
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: properties `app.batch.payroll.chunk-size`, `app.batch.payroll.skip-limit`, `app.batch.payroll.execution-timeout` — consumed by `PayrollBatchConfig`/`PayrollJobAction` (Tasks 5–6, already reference these keys with inline defaults, so this step is not strictly required for the app to boot, but makes the sample's tuning knobs discoverable/consistent with `app.batch.export`/`app.batch.eod`).

- [ ] **Step 1: Add the `payroll` block**

In `application.yml`, the existing `batch:` section reads:

```yaml
  batch:
    export:
      # Số bản ghi commit mỗi chunk khi export users -> user_exports.
      chunk-size: 1000
      # Timeout riêng cho batch export (lớn hơn nhiều so với job HTTP thông thường vì xử lý hàng loạt bản ghi).
      execution-timeout: 30m
    eod:
      # Timeout cho sample job BANKING_EOD (job nhỏ nên timeout ngắn hơn export).
      execution-timeout: 5m
```

Add a `payroll:` sibling after `eod:` (same indentation level):

```yaml
    payroll:
      # Chunk size cho sample BANK_SALARY_PAYROLL (giải ngân lương hàng loạt ~30.000 nhân viên).
      chunk-size: 500
      # Số record tối đa được phép skip (lỗi validate) trước khi cả step báo lỗi.
      skip-limit: 1000
      # Timeout dài hơn EOD vì xử lý khối lượng tương đương export (~30k dòng) qua 2 tasklet + 1 chunk step.
      execution-timeout: 15m
```

- [ ] **Step 2: Verify the app context still loads**

Run: `mvn test -Dtest=PayrollJobActionTest,QuartzTriggerFactoryTest`
Expected: PASS (YAML syntax is valid — a malformed YAML fails every Spring test).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat: add app.batch.payroll configuration block

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 8: CSV generator script + a real 30,000-row sample file

**Files:**
- Create: `scripts/generate-fpt-payroll-csv.py`
- Modify: `.gitignore`

**Interfaces:**
- Produces: a CSV file with header `employeeId,accountNumber,fullName,salaryAmount`, matching exactly what `PayrollBatchConfig.csvEmployeeReader` (Task 5) and `holdFundsTasklet`'s plain-text parsing (Task 5) expect.
- Consumes: nothing (standalone Python 3 stdlib script).

- [ ] **Step 1: Write the generator script**

Create `scripts/generate-fpt-payroll-csv.py`:

```python
#!/usr/bin/env python3
"""Sinh file CSV lương giả lập cho sample BANK_SALARY_PAYROLL (xem
docs/bank-salary-sample/bank-salary-sample.md và
docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md).

Usage:
    python3 scripts/generate-fpt-payroll-csv.py \
        --count 30000 \
        --out docs/bank-salary-sample/sample-data/FPT_PAYROLL_2026-09-21.csv \
        --invalid-rate 0.01
"""
import argparse
import csv
import os
import random


def main():
    parser = argparse.ArgumentParser(description="Sinh CSV lương nhân viên FPT Software")
    parser.add_argument("--count", type=int, default=30000, help="Số nhân viên (mặc định 30000)")
    parser.add_argument("--out", required=True, help="Đường dẫn file CSV đầu ra")
    parser.add_argument(
        "--invalid-rate",
        type=float,
        default=0.01,
        help="Tỉ lệ dòng cố tình lỗi để test skip (mặc định 0.01 = 1%%)",
    )
    parser.add_argument("--seed", type=int, default=42, help="Seed để sinh dữ liệu lặp lại được")
    args = parser.parse_args()

    random.seed(args.seed)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)

    invalid_count = 0
    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["employeeId", "accountNumber", "fullName", "salaryAmount"])

        for i in range(1, args.count + 1):
            employee_id = f"FPT{i:06d}"
            full_name = f"Nhan Vien {i:06d}"
            salary = round(random.uniform(8_000_000, 60_000_000))
            account_number = f"{9_000_000_000_000 + i:013d}"

            if random.random() < args.invalid_rate:
                invalid_count += 1
                if random.random() < 0.5:
                    # Số tài khoản sai định dạng (không phải toàn số) - PayrollValidationProcessor sẽ skip.
                    account_number = f"BAD-{i}"
                else:
                    # Lương không hợp lệ - PayrollValidationProcessor sẽ skip.
                    salary = 0

            writer.writerow([employee_id, account_number, full_name, salary])

    print(f"Đã sinh {args.count} dòng ({invalid_count} dòng cố tình lỗi) vào {args.out}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Ignore generated sample data**

In `.gitignore`, add a new line:

```
docs/bank-salary-sample/sample-data/
```

- [ ] **Step 3: Run it once to produce a real 30,000-row file**

Determine `<target-pay-date>` first: run

```bash
python3 -c "
from datetime import date
d = date.today()
print(f'{d.year}-{d.month:02d}-19')
"
```

then check that date against the sample holidays seeded in `V9__create_holidays.sql` (Tết 2026-02-14/16, Quốc khánh 2026-08-31/09-01/09-02) and weekends; if the 19th of the current month lands on a weekend or one of those holidays, use the next working day instead (e.g. September 2026: the 19th is a Saturday, so the working sample file is dated `2026-09-21`). Then run:

```bash
mkdir -p docs/bank-salary-sample/sample-data
python3 scripts/generate-fpt-payroll-csv.py \
  --count 30000 \
  --out docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv
wc -l docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv
head -5 docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv
```

Expected: `wc -l` reports 30,001 lines (header + 30,000 rows); `head` shows well-formed CSV rows.

- [ ] **Step 4: Commit the script (not the generated data)**

```bash
git add scripts/generate-fpt-payroll-csv.py .gitignore
git commit -m "feat: add payroll CSV sample-data generator script

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```

---

## Task 9: Documentation and final full-suite verification

**Files:**
- Create: `docs/bank-salary-sample/running-the-sample.md`

**Interfaces:**
- Consumes: everything from Tasks 1–8 (this is the end-to-end usage doc + final verification pass).

- [ ] **Step 1: Write the runbook**

Create `docs/bank-salary-sample/running-the-sample.md`:

````markdown
# Chạy thử sample BANK_SALARY_PAYROLL

Xem thiết kế đầy đủ tại `docs/superpowers/specs/2026-08-24-bank-salary-payroll-design.md` và
mô tả nghiệp vụ gốc tại `bank-salary-sample.md` trong cùng thư mục này.

## 1. Sinh file CSV lương mẫu (30.000 nhân viên)

`PayrollJobAction` chỉ thực sự chạy vào đúng "target pay date" của tháng: ngày 19 nếu là ngày
làm việc, hoặc ngày làm việc gần nhất sau đó nếu 19 rơi vào cuối tuần/ngày lễ (`bankHolidays`).
File CSV phải được đặt tên khớp đúng ngày đó:

```bash
mkdir -p docs/bank-salary-sample/sample-data
python3 scripts/generate-fpt-payroll-csv.py \
  --count 30000 \
  --out docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv
```

Ví dụ tháng 9/2026: ngày 19 rơi vào Thứ Bảy → kỳ lương thực tế là Thứ Hai 21/09/2026 → file
phải tên `FPT_PAYROLL_2026-09-21.csv`.

## 2. Tạo JobDefinition

```bash
curl -X POST http://localhost:8080/system-report-job/api/job-definitions \
  -H "Content-Type: application/json" \
  -d '{
    "jobType": "BANK_SALARY_PAYROLL",
    "expression": "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"docs/bank-salary-sample/sample-data\",\"countryCode\":\"VN\",\"branchId\":\"ALL\"}",
    "description": "Chuyển lương hàng loạt FPT Software"
  }'
```

Lấy `data.id` trong response JSON, dùng làm `jobDefinitionId` ở bước sau.

## 3. Tạo Task (Cron hàng ngày, gắn calendar bankHolidays)

```bash
curl -X POST http://localhost:8080/system-report-job/api/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "fpt-payroll-monthly",
    "group": "payroll",
    "jobDefinitionId": "<id-từ-bước-2>",
    "triggerType": "CRON",
    "cronExpression": "0 0 8 * * ?",
    "calendarName": "bankHolidays",
    "timezoneId": "Asia/Ho_Chi_Minh",
    "description": "Chạy 08:00 hàng ngày; chỉ thực sự giải ngân đúng ngày 19 (hoặc ngày làm việc kế tiếp)"
  }'
```

`calendarName: "bankHolidays"` khiến Quartz hoàn toàn không fire trigger vào cuối tuần/ngày lễ
(xem `HolidayCalendarLoader` + `QuartzTriggerFactory`). `PayrollJobAction` tự kiểm tra thêm điều
kiện "hôm nay có đúng target pay date không" trên mỗi lần fire còn lại.

## 4. Kích hoạt + chạy thử ngay

```bash
curl -X POST http://localhost:8080/system-report-job/api/tasks/start/<task-id>
curl -X POST http://localhost:8080/system-report-job/api/tasks/trigger-now/<task-id>
```

`trigger-now` vẫn đi qua toàn bộ logic của `PayrollJobAction`, bao gồm cả việc kiểm tra "hôm
nay có phải target pay date không" — nếu không phải, job chỉ log rồi bỏ qua và
`payroll_disbursement` sẽ không có dữ liệu mới. Muốn test end-to-end thật, chạy đúng vào ngày
mục tiêu hoặc tạm sửa ngày hệ thống.

## 5. Theo dõi kết quả

```sql
SELECT * FROM payroll_batch_run ORDER BY started_at DESC;
SELECT status, COUNT(*) FROM payroll_disbursement WHERE batch_run_id = <id> GROUP BY status;
SELECT balance FROM fpt_company_account WHERE company_code = 'FPT_SOFTWARE';
SELECT balance FROM gl_suspense_account WHERE account_code = 'PAYROLL_SUSPENSE_GL';
```

Log ứng dụng in các dòng `[BANK_SALARY_PAYROLL] holdFundsStep - ...`,
`[BANK_SALARY_PAYROLL] disburseStep - bỏ qua nhân viên ... ` (1 dòng / record bị skip), và
`[BANK_SALARY_PAYROLL] notifyStep - Kỳ lương ...` (thông báo giả lập, tổng kết cuối job).
`gl_suspense_account.balance` phải quay về 0 (hoặc rất gần 0, chỉ còn phần chênh lệch của các
record bị skip không được giải ngân) sau khi job chạy xong.
````

- [ ] **Step 2: Full-suite verification**

Run: `mvn spotless:check`
Expected: BUILD SUCCESS (no formatting violations).

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass (Docker must be running for the Testcontainers-backed persistence/scheduler/e2e tests).

- [ ] **Step 3: Commit**

```bash
git add docs/bank-salary-sample/running-the-sample.md
git commit -m "docs: add BANK_SALARY_PAYROLL sample runbook

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01B8HBweFtrzgSKtoAYRhWGR"
```
