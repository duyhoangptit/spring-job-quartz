# Bank Salary Payroll Sample — Design

Date: 2026-08-24
Status: Approved for implementation

## 1. Purpose

Add a runnable sample under this Clean-Architecture Quartz/Spring Batch service that
demonstrates a realistic bulk bank-payroll disbursement flow, per
`docs/bank-salary-sample/bank-salary-sample.md`:

- Company **FPT Software** sends a CSV of ~30,000 employees to be paid on the 19th of
  the month (or the next working day, if the 19th falls on a weekend/holiday).
- Step 1: verify + hold funds from the company's account into an internal GL suspense
  account before disbursing anything.
- Step 2: chunk-oriented disbursement to all employees, with per-record skip on invalid
  data (job keeps processing the rest — it does not fail wholesale).
- Step 3: a notification step (simulated via logging) summarizing the run.

This reuses the existing `bankHolidays` Quartz calendar (`HolidayCalendarLoader`) and the
existing `HolidayQueryUseCase.getNextWorkingDay(...)` domain logic — no new holiday
calculation code.

## 2. Non-goals

- No real money movement / no integration with any external banking rail — everything is
  simulated against local Postgres tables.
- No new `employees` master table — the CSV *is* the employee list for a given run (per
  the source design doc).
- No dynamic per-month trigger rescheduling — the Quartz trigger fires daily and the
  `JobAction` decides whether today is the target pay date (see §4).
- No seeded `Task`/`JobDefinition` migration — creating the Task is a documented manual
  API step (§7), by the user's explicit choice.

## 3. Package layout

```
infrastructure/jobactions/batch/payroll/
├── PayrollJobAction.java        # JobAction, jobType = BANK_SALARY_PAYROLL
├── PayrollBatchConfig.java      # Job + 3 Steps (hold funds / disburse / notify)
├── PayrollCsvRecord.java        # raw CSV row DTO (reader target)
├── PayrollDisbursementRecord.java # processed record (writer target)
└── PayrollValidationException.java # thrown by processor on invalid record -> triggers skip
```

`infrastructure/jobactions/batch/banksalaryfxt/` (existing draft: `BatchReaderConfig`,
`PayrollQuartzJob`, `PayrollScheduler`) is deleted. It violates the architecture rule that
`ScheduledJobExecutor` is the only `org.quartz.Job` in the system (`PayrollQuartzJob`
extends `QuartzJobBean` directly) and never actually attaches to Quartz/`bankHolidays`
(`PayrollScheduler` uses Spring `@Scheduled`). It's replaced by the `JobAction` +
`*BatchConfig` pattern already used by `BankingEodJobAction`/`BankingEodBatchConfig` and
`SpringBatchJobAction`/`UserExportBatchConfig`.

## 4. Scheduling

- The `Task`/`JobDefinition` for this sample uses a **daily** `Cron` trigger (e.g.
  `0 0 8 * * ?`) with Quartz calendar name `bankHolidays` attached, so Quartz itself never
  fires the trigger on a weekend or bank holiday (see §7 for how the Task is created).
- `PayrollJobAction.execute(JobDefinition)` decides whether *today* is the actual pay date:

  ```java
  LocalDate today = LocalDate.now();
  LocalDate the19th = LocalDate.of(today.getYear(), today.getMonth(), 19);
  LocalDate targetPayDate = holidayQueryUseCase.getNextWorkingDay(
          the19th.minusDays(1), countryCode, branchId);
  if (!today.equals(targetPayDate)) {
      log.info("BANK_SALARY_PAYROLL: hôm nay ({}) chưa phải ngày trả lương (target={}), bỏ qua", today, targetPayDate);
      return;
  }
  ```

  `getNextWorkingDay(start, ...)` returns the first working day *strictly after* `start`;
  passing `the19th.minusDays(1)` (the 18th) makes it return the 19th itself when the 19th
  is a working day, or the next working day after it otherwise — one call, no re-derivation
  of holiday logic.
- `countryCode`/`branchId` come from `JobDefinition.expression()` (default `"VN"`/`"ALL"`).
- This runs once at each qualifying Quartz fire per day; a real system would also want
  idempotency (don't double-run if fired twice for the same target date) — out of scope for
  the sample, but `payroll_batch_run` has a unique constraint on
  `(company_code, target_pay_date)` so a duplicate run fails fast in Step 1 instead of
  double-crediting the GL account.

## 5. Spring Batch job (`PayrollBatchConfig`)

Linear job, 3 steps — `fptPayrollJob = holdFundsStep -> disburseStep -> notifyStep`.

**Step 1 — `holdFundsStep` (tasklet)**
- Streams the CSV once (plain `BufferedReader`, not a Spring Batch reader — this step only
  needs the total) to compute `totalAmount` and `employeeCount`.
- Reads `fpt_company_account.balance` for the company; if `balance < totalAmount`, throws
  `IllegalStateException` (job fails here, nothing is disbursed, no funds move).
- Otherwise, in one JDBC transaction: debit `fpt_company_account`, credit
  `gl_suspense_account`, insert `payroll_batch_run` (`status = HOLD_SUCCESS`,
  `total_employees`, `total_amount`). Its generated id becomes the batch run's execution
  context key (`batchRunId`) for later steps.

**Step 2 — `disburseStep` (chunk, size from `app.batch.payroll.chunk-size`, default 500)**
- Reader: `FlatFileItemReader<PayrollCsvRecord>` over the same CSV (`employeeId,
  accountNumber, fullName, salaryAmount`), header line skipped — same shape as the existing
  `BatchReaderConfig` draft.
- Processor: validates `accountNumber` (regex, e.g. `^\d{10,14}$`) and `salaryAmount > 0`;
  throws `PayrollValidationException` on failure. Maps valid rows to
  `PayrollDisbursementRecord`.
- Writer: `JdbcBatchItemWriterBuilder` inserts into `payroll_disbursement`
  (`status = SUCCESS`) and debits `gl_suspense_account` by the chunk's total.
- Fault tolerance: `.faultTolerant().skip(PayrollValidationException.class)
  .skipLimit(app.batch.payroll.skip-limit)`, with a `SkipListener` that inserts the failed
  row into `payroll_disbursement` (`status = SKIPPED`, `error_reason` = exception message)
  so every one of the 30,000 input rows ends up with exactly one outcome row — nothing is
  silently dropped. A bad record in the middle of a chunk causes only that item to be
  skipped (Spring Batch retries the chunk item-by-item once a skippable exception occurs);
  the surrounding 29,000+ good records still commit normally, matching the source design
  doc's "1 employee fails, the other 29,500 are unaffected" requirement.

**Step 3 — `notifyStep` (tasklet)**
- `SELECT status, COUNT(*), COALESCE(SUM(amount),0) FROM payroll_disbursement WHERE
  batch_run_id = ? GROUP BY status`.
- Logs a summary line (simulated notification), e.g.:
  `log.info("[BANK_SALARY_PAYROLL] Kỳ lương {}: 29700/30000 thành công ({} VND), 300 bị skip", ...)`
  and `log.warn(...)` per skipped record already logged by the `SkipListener` in Step 2 (no
  duplicate logging here — this step only logs the aggregate).
- Updates `payroll_batch_run.status = COMPLETED`.

## 6. Database schema (`V11__create_payroll_tables.sql` — `V10` is the §7 `tasks.calendar_name` migration)

```sql
CREATE TABLE fpt_company_account (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(30) NOT NULL UNIQUE,
    account_number VARCHAR(20) NOT NULL,
    balance NUMERIC(18, 2) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE gl_suspense_account (
    id BIGSERIAL PRIMARY KEY,
    account_code VARCHAR(30) NOT NULL UNIQUE,  -- e.g. 'PAYROLL_SUSPENSE_GL'
    balance NUMERIC(18, 2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payroll_batch_run (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(30) NOT NULL,
    target_pay_date DATE NOT NULL,
    total_employees INT NOT NULL,
    total_amount NUMERIC(18, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- HOLD_SUCCESS, COMPLETED, FAILED
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT unique_payroll_run_per_period UNIQUE (company_code, target_pay_date)
);

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

INSERT INTO fpt_company_account (company_code, account_number, balance)
VALUES ('FPT_SOFTWARE', '9999000111222', 500000000000.00); -- 500 tỷ VND, đủ cho ~30k nhân viên
```

Seed balance sized so a `mvn spring-boot:run` + generated CSV can actually complete Step 1
without manual DB setup.

## 7. Attaching the `bankHolidays` Quartz calendar to a Task (shared scheduling-engine change)

Checked against current code: `QuartzTriggerFactory.build()` never calls Quartz's
`TriggerBuilder.modifiedByCalendar(name)` — nothing in this system today can actually
attach a registered Quartz `Calendar` (like `bankHolidays`) to a `Task`'s trigger. Reusing
`bankHolidays` for real (not just re-deriving the same holiday data via SQL) requires a
small, generic addition to the shared Task model — independent of trigger type, the same
way `timezoneId`/`priority` already sit next to `trigger` on `ScheduledTask`:

- `ScheduledTask` gains a new field `calendarName` (nullable `String`), inserted after
  `trigger`: `(id, name, group, jobDefinitionId, trigger, calendarName, timezoneId,
  priority, description)`.
- `CreateTaskCommand` and `CreateTaskRequest` gain the same nullable `calendarName` field.
- `tasks` table gains a nullable `calendar_name VARCHAR(100)` column (new migration).
- `TaskEntity` gains `calendarName`; `TaskRepositoryAdapter.toEntity`/`toDomain` map it.
- `QuartzTriggerFactory.build()` calls `builder.modifiedByCalendar(task.calendarName())`
  when non-null, before `.withSchedule(...)` — works for any of the four trigger kinds,
  not just `Cron`.
- `TaskResponse`/`TaskDetailResponse` are unchanged (they don't expose trigger/schedule
  fields today, confirmed by reading both files — no follow-on change needed there).

This is a small (~7 files), backward-compatible, additive change: existing tasks keep
`calendar_name = NULL` and behave exactly as before.

## 8. Creating the Task (documented, not seeded)

Per the user's choice, no Flyway seed for `JobDefinition`/`Task`. Add
`docs/bank-salary-sample/running-the-sample.md` showing the two POST calls against the
existing `JobDefinitionUseCase`/`TaskManagementUseCase` REST endpoints:

1. Create a `JobDefinition` with `jobType = "BANK_SALARY_PAYROLL"` and `expression =
   {"companyCode":"FPT_SOFTWARE","csvDirectory":"<path>","countryCode":"VN","branchId":"ALL"}`.
2. Create a `Task` referencing that `JobDefinition` with a `Cron` `TriggerDefinition`
   (`0 0 8 * * ?`) and `calendarName = "bankHolidays"` (§7) — Quartz then never fires this
   trigger on a weekend or bank holiday.

## 9. CSV generation (30,000 employees)

- `scripts/generate-fpt-payroll-csv.py` (Python 3 stdlib only — `csv`, `random`, `argparse`;
  no new project dependency). Args: `--count` (default 30000), `--out`, `--invalid-rate`
  (default 0.01).
- Columns: `employeeId,accountNumber,fullName,salaryAmount` (matches
  `BatchReaderConfig`'s existing `.names(...)`, kept as-is).
- ~1% of rows are intentionally invalid (malformed `accountNumber` or `salaryAmount <= 0`)
  to exercise the Step 2 skip path end-to-end.
- The script is committed; its output (a multi-MB generated CSV) is not — add the output
  directory to `.gitignore`. Default output directory:
  `docs/bank-salary-sample/sample-data/` (overridable via `--out`); the same path (or
  wherever the user points `csvDirectory` in the `JobDefinition`) is what `PayrollJobAction`
  reads from at runtime, as `FPT_PAYROLL_<targetPayDate>.csv`.
- As part of implementation, the script is run once to produce a real 30,000-row file for
  local testing.

## 10. Configuration (`application.yml`)

```yaml
app:
  batch:
    payroll:
      chunk-size: 500
      skip-limit: 1000
      execution-timeout: 15m
```

`PayrollJobAction` follows the existing `BankingEodJobAction`/`SpringBatchJobAction` shape:
runs the batch job on `jobActionTaskExecutor`, bounded by `execution-timeout`, via
`JobOperator`.

## 11. Testing

- Unit test for the pay-date resolution logic in `PayrollJobAction` (19th on a working day,
  19th on a weekend, 19th on a holiday with a multi-day bridge, mocking
  `HolidayQueryUseCase` and `JobOperator`) — plain Mockito, no Spring context, matching
  `SpringBatchJobActionTest`'s existing shape exactly (that test mocks `JobOperator`/`Job`
  and never boots Spring Batch or touches a real DB).
- Unit test for the Step 2 validation branch (`PayrollValidationProcessor`): valid row maps
  through, malformed account number and non-positive amount each throw
  `PayrollValidationException` — plain Mockito/JUnit, no Spring context needed since the
  processor has no framework dependency beyond the interface.
- No Testcontainers-backed test for `PayrollBatchConfig`'s tasklet SQL/chunk wiring itself:
  `BankingEodBatchConfig` (the existing sample this mirrors) has zero test coverage at that
  level today — only its `JobAction` wrapper is tested, with `Job`/`JobOperator` mocked out.
  This sample follows the same precedent for consistency; the two unit tests above are what
  cover its actual decision logic (pay-date resolution, record validation).

## 12. Open items deferred out of this sample's scope

- Idempotent re-run / retry of a partially-completed batch run.
- Real notification integration (email/SMS/webhook) — logging only, as requested.
- Multi-company support beyond FPT_SOFTWARE (the schema allows it via `company_code`, but
  no second company is seeded or tested).
