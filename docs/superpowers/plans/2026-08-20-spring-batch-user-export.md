# Spring Batch user export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `users` source table (mock, seedable to 1M rows), a `user_exports` sink table, and a real
Spring Batch chunk-oriented `JobAction` (`EXPORT_USERS`) — a working, non-toy example of Spring Batch
inside the existing Quartz-driven `JobAction` registry.

**Architecture:** `SpringBatchJobAction` is one more `JobAction` bean, exactly like `HttpCallJobAction` —
`domain/`, `usecase/`, `ScheduledJobExecutor`, `JobActionRegistry`, `QuartzJobListener` are **not**
modified. A Spring Batch `Job`/`Step` (`UserExportBatchConfig`) reads `users` page-by-page and writes to
`user_exports`; job/step execution state persists in Flyway-managed `BATCH_*` tables via a JDBC-backed
`JobRepository` we wire ourselves (Boot's default is in-memory — see Global Constraints).

**Tech Stack:** Spring Boot 4.1.0, **Spring Batch 6.0.4** (verified against the actual jars in the local
Maven cache — its API differs from the "classic" 4.x/5.x shape most docs/examples show), Postgres 16 +
Flyway, Testcontainers, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-20-spring-batch-user-export-design.md`

## Global Constraints

These are load-bearing, version-specific facts verified by extracting `spring-batch-core-6.0.4`,
`spring-batch-infrastructure-6.0.4`, `spring-boot-batch-4.1.0`, and `spring-boot-jpa-4.1.0` from the
local Maven cache and reading their real sources — **do not substitute pre-6.0 Spring Batch knowledge**,
package names and idioms below are not interchangeable with older Spring Batch.

- Package moves vs. classic Spring Batch: `Job` → `org.springframework.batch.core.job.Job`, `Step` →
  `org.springframework.batch.core.step.Step`, `JobExecution` →
  `org.springframework.batch.core.job.JobExecution`, `JobParameters`/`JobParametersBuilder` →
  `org.springframework.batch.core.job.parameters.*`, `JobBuilder` →
  `org.springframework.batch.core.job.builder.JobBuilder`, `StepBuilder` →
  `org.springframework.batch.core.step.builder.StepBuilder`. `BatchStatus`/`ExitStatus` stay at
  `org.springframework.batch.core.*`.
- `ItemReader`/`ItemWriter`/`ItemProcessor` and all JDBC item support (`JdbcPagingItemReaderBuilder`,
  `JdbcBatchItemWriterBuilder`, `ItemPreparedStatementSetter`, `Order`) live under
  **`org.springframework.batch.infrastructure.item.*`** (was `org.springframework.batch.item.*`).
- `JobLauncher` is `@Deprecated(since="6.0", forRemoval=true)`. Use **`JobOperator`**
  (`org.springframework.batch.core.launch.JobOperator`, extends `JobLauncher`) and its non-deprecated
  `start(Job, JobParameters)` — never `JobLauncher.run(...)`.
- Dependency: `org.springframework.boot:spring-boot-starter-batch` (no explicit version — managed by the
  `spring-boot-starter-parent:4.1.0` BOM, which pins `spring-batch.version=6.0.4`). Test dependency:
  `org.springframework.batch:spring-batch-test` (test scope, same BOM management).
- `spring.batch.job.enabled=false` still works in `application.yml` (disables
  `JobLauncherApplicationRunner` auto-run on startup — confirmed in
  `org.springframework.boot.batch.autoconfigure.BatchJobLauncherAutoConfiguration` source). **There is
  no `spring.batch.jdbc.initialize-schema` property in this version** — don't add it, it does nothing.
- **Trap — must fix, not optional:** Boot's own batch autoconfiguration
  (`org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration` →
  `SpringBootBatchDefaultConfiguration extends DefaultBatchConfiguration`) wires an **in-memory
  `ResourcelessJobRepository`** by default (confirmed in `DefaultBatchConfiguration` source — its
  `jobRepository()` bean literally returns `new ResourcelessJobRepository()`). This silently ignores our
  `BATCH_*` Postgres tables. Fix: provide an app `@Configuration` extending
  `org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration` (Task 4) — an
  **empty subclass is sufficient**; Boot's in-memory config backs off automatically
  (`@ConditionalOnMissingBean(value = DefaultBatchConfiguration.class, ...)` on Boot's own class).
  `JdbcDefaultBatchConfiguration` looks up beans literally named `"dataSource"` and
  `"transactionManager"` — both already exist (Boot's autoconfigured Hikari `DataSource`; the JPA
  `JpaTransactionManager` registered as `transactionManager` by
  `org.springframework.boot.jpa.autoconfigure.JpaBaseConfiguration`, confirmed by reading its source) —
  no further wiring needed.
- Records (`UserRecord`, `UserExportRecord`) don't have JavaBean `getX()` accessors, so
  `.beanRowMapper()` (reader) and `.beanMapped()` (writer) — both built on `BeanPropertyRowMapper` /
  `BeanPropertySqlParameterSource` — **will not work**. Use an explicit `.rowMapper(...)` lambda and an
  explicit `.itemPreparedStatementSetter(...)` lambda instead (both shown verbatim in Task 4).
- Test utility: `org.springframework.batch.test.JobOperatorTestUtils(JobOperator, JobRepository)` +
  `.setJob(Job)` + `.startJob(JobParameters)` — the modern replacement for the legacy
  `JobLauncherTestUtils` it extends.
- Postgres folds unquoted identifiers to lowercase — `V7`'s `CREATE TABLE BATCH_JOB_INSTANCE (...)`
  (copied verbatim from Spring Batch) is queried in tests as `"batch_job_instance"`, same as the
  existing `V1` Quartz tables and their `"qrtz_job_details"` assertion in `FlywayMigrationTest`.
- Follow existing conventions exactly: `id UUID PRIMARY KEY` set in Java (never a DB default — see
  `BaseEntity`), Testcontainers + `@DynamicPropertySource` pattern (see `FlywayMigrationTest`,
  `TaskExecutionHistoryRepositoryAdapterTest`), `JobAction`'s virtual-thread + timeout wrapper (see
  `HttpCallJobAction`), Vietnamese exception messages matching the existing style.

---

## Task 1: Add Spring Batch dependencies

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing (new dependency).
- Produces: `spring-boot-starter-batch` and `spring-batch-test` on the classpath for every later task.

- [ ] **Step 1: Add the main dependency**

In `pom.xml`, inside `<dependencies>`, add next to `spring-boot-starter-quartz`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch</artifactId>
</dependency>
```

- [ ] **Step 2: Add the test dependency**

In the same `<dependencies>` block, add next to `spring-boot-starter-test`:

```xml
<dependency>
    <groupId>org.springframework.batch</groupId>
    <artifactId>spring-batch-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Verify resolution and compile**

Run: `mvn -q dependency:tree | grep -i batch`
Expected: lines for `spring-boot-starter-batch`, `spring-boot-batch`, `spring-batch-core:6.0.4`,
`spring-batch-infrastructure:6.0.4`, `spring-batch-test:6.0.4`.

Run: `mvn compile`
Expected: `BUILD SUCCESS` (no code changed yet, this just confirms no dependency conflicts).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build(system-report-job): add Spring Batch dependencies"
```

---

## Task 2: `users` / `user_exports` / Spring Batch schema migrations

**Files:**
- Create: `src/main/resources/db/migration/V5__create_users_table.sql`
- Create: `src/main/resources/db/migration/V6__create_user_exports_table.sql`
- Create: `src/main/resources/db/migration/V7__create_spring_batch_tables.sql`
- Modify: `src/test/java/com/corebanking/systemreportjob/infrastructure/persistence/FlywayMigrationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: tables `users`, `user_exports`, `batch_job_instance`, `batch_job_execution`,
  `batch_job_execution_params`, `batch_job_execution_context`, `batch_step_execution`,
  `batch_step_execution_context` — consumed by Task 4 (`UserExportBatchConfig`, `BatchConfig`) and Task 6
  (e2e test).

- [ ] **Step 1: Extend `FlywayMigrationTest` with the new table assertions (write the failing test first)**

In `FlywayMigrationTest.migratesAllExpectedTables()`, add after the existing `qrtz_job_details`
assertion:

```java
            assertThat(tableExists(metaData, "users")).isTrue();
            assertThat(tableExists(metaData, "user_exports")).isTrue();
            assertThat(tableExists(metaData, "batch_job_instance")).isTrue();
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=FlywayMigrationTest`
Expected: FAIL — `users` does not exist.

- [ ] **Step 3: Create `V5__create_users_table.sql`**

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

- [ ] **Step 4: Create `V6__create_user_exports_table.sql`**

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

- [ ] **Step 5: Create `V7__create_spring_batch_tables.sql`**

Copy this **verbatim** — it is the exact content of `org/springframework/batch/core/schema-postgresql.sql`
inside `spring-batch-core-6.0.4.jar` (extracted and diffed against 5.2.2 in this session — note the last
sequence is `BATCH_JOB_INSTANCE_SEQ`, not the `BATCH_JOB_SEQ` name used pre-6.0):

```sql
CREATE TABLE BATCH_JOB_INSTANCE (
	JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_NAME VARCHAR(100) NOT NULL,
	JOB_KEY VARCHAR(32) NOT NULL,
	constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE BATCH_JOB_EXECUTION (
	JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT,
	JOB_INSTANCE_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
	references BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
	JOB_EXECUTION_ID BIGINT NOT NULL,
	PARAMETER_NAME VARCHAR(100) NOT NULL,
	PARAMETER_TYPE VARCHAR(100) NOT NULL,
	PARAMETER_VALUE VARCHAR(2500),
	IDENTIFYING CHAR(1) NOT NULL,
	constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION (
	STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
	VERSION BIGINT NOT NULL,
	STEP_NAME VARCHAR(100) NOT NULL,
	JOB_EXECUTION_ID BIGINT NOT NULL,
	CREATE_TIME TIMESTAMP NOT NULL,
	START_TIME TIMESTAMP DEFAULT NULL,
	END_TIME TIMESTAMP DEFAULT NULL,
	STATUS VARCHAR(10),
	COMMIT_COUNT BIGINT,
	READ_COUNT BIGINT,
	FILTER_COUNT BIGINT,
	WRITE_COUNT BIGINT,
	READ_SKIP_COUNT BIGINT,
	WRITE_SKIP_COUNT BIGINT,
	PROCESS_SKIP_COUNT BIGINT,
	ROLLBACK_COUNT BIGINT,
	EXIT_CODE VARCHAR(2500),
	EXIT_MESSAGE VARCHAR(2500),
	LAST_UPDATED TIMESTAMP,
	constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
	STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
	references BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
	JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
	SHORT_CONTEXT VARCHAR(2500) NOT NULL,
	SERIALIZED_CONTEXT TEXT,
	constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
	references BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=FlywayMigrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V5__create_users_table.sql \
        src/main/resources/db/migration/V6__create_user_exports_table.sql \
        src/main/resources/db/migration/V7__create_spring_batch_tables.sql \
        src/test/java/com/corebanking/systemreportjob/infrastructure/persistence/FlywayMigrationTest.java
git commit -m "feat(system-report-job): add users, user_exports, and Spring Batch schema migrations"
```

---

## Task 3: 1M-row mock data seed script (not a Flyway migration)

**Files:**
- Create: `src/main/resources/db/seed/seed_users_1m.sql`
- Modify: `README.md`

**Interfaces:**
- Consumes: `users` table (Task 2).
- Produces: nothing consumed by later tasks — this is a standalone, manually-run script, deliberately
  excluded from the Flyway migration chain (see Global Constraints / spec §3: every Testcontainers test
  class in this repo replays the full migration chain on a fresh container).

- [ ] **Step 1: Create the seed script**

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

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

- [ ] **Step 2: Document it in README.md**

Add a new section after `## Testing`:

```markdown
## Sample data: 1M mock users + Spring Batch export job

Seed the `users` table with 1,000,000 mock rows (local dev DB only — **not** run by tests or on
startup):

\`\`\`bash
psql "postgresql://tigerpro:secret@localhost:5432/db_system_report_job" \
  -f src/main/resources/db/seed/seed_users_1m.sql
\`\`\`

Create and start a job that exports `users` into `user_exports` in chunks of 1000 via Spring Batch:

\`\`\`bash
curl -X POST localhost:8080/system-report-job/api/job-definitions \
  -H 'Content-Type: application/json' \
  -d '{"jobType":"EXPORT_USERS","expression":"{}"}'
# -> note the returned "id" as JOB_DEFINITION_ID

curl -X POST localhost:8080/system-report-job/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"name":"export-users-hourly","group":"reports","jobDefinitionId":"JOB_DEFINITION_ID",
       "triggerType":"CRON","cronExpression":"0 0 * * * ?"}'
# -> note the returned "id" as TASK_ID

curl -X POST localhost:8080/system-report-job/api/tasks/start/TASK_ID
\`\`\`

Watch progress: `SELECT COUNT(*) FROM user_exports;` grows in chunks of 1000 as the job runs.
```

- [ ] **Step 3: Verify manually**

Run: `docker run -d --name system-report-job-db -e POSTGRES_DB=db_system_report_job -e POSTGRES_PASSWORD=root -p 5432:5432 postgres:16-alpine`
(or reuse an already-running local instance), run `mvn spring-boot:run` once so Flyway creates the
schema, stop it, then run the `psql -f ...` command from Step 2.

Run: `psql "postgresql://tigerpro:secret@localhost:5432/db_system_report_job" -c "SELECT COUNT(*) FROM users;"`
Expected: `1000000`.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/seed/seed_users_1m.sql README.md
git commit -m "feat(system-report-job): add 1M-row users mock data seed script"
```

---

## Task 4: Batch infrastructure — `BatchConfig` + `UserExportBatchConfig` (reader/processor/writer/job)

**Files:**
- Create: `src/main/java/com/corebanking/systemreportjob/infrastructure/config/BatchConfig.java`
- Create: `src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserRecord.java`
- Create: `src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportRecord.java`
- Create: `src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportBatchConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportBatchConfigIT.java`

**Interfaces:**
- Consumes: `users`/`user_exports`/`BATCH_*` tables (Task 2); `DataSource` bean named `"dataSource"` and
  `PlatformTransactionManager` bean named `"transactionManager"` (both already autoconfigured by Boot).
- Produces: `Job exportUsersJob` bean, `record UserRecord(UUID id, String username, String email, String
  fullName, String phoneNumber, String address, String gender, LocalDate dob, String description, String
  status)`, `record UserExportRecord(UUID id, UUID userId, String username, String email, String
  fullName, String phoneNumber, String address, String gender, LocalDate dob, String description, String
  status, Instant exportedAt)` — all consumed by Task 5 (`SpringBatchJobAction`).

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportBatchConfigIT.java`:

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UserExportBatchConfigIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    Job exportUsersJob;

    private void seedUsers(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, username, email, full_name, status) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    "it-user-" + i,
                    "it-user-" + i + "@example.com",
                    "IT User " + i,
                    "ACTIVE");
        }
    }

    @Test
    void exportsAllUsersIntoUserExportsTable() throws Exception {
        seedUsers(20);
        JobOperatorTestUtils testUtils = new JobOperatorTestUtils(jobOperator, jobRepository);
        testUtils.setJob(exportUsersJob);

        JobExecution execution = testUtils.startJob(new JobParametersBuilder()
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer exportedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_exports", Integer.class);
        assertThat(exportedCount).isEqualTo(20);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn test -Dtest=UserExportBatchConfigIT`
Expected: FAIL — Spring context fails to start, `NoSuchBeanDefinitionException` for `Job exportUsersJob`
(or similar — the bean doesn't exist yet).

- [ ] **Step 3: Create `BatchConfig`**

```java
package com.corebanking.systemreportjob.infrastructure.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatchConfig extends JdbcDefaultBatchConfiguration {}
```

- [ ] **Step 4: Create the `UserRecord` and `UserExportRecord` records**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import java.time.LocalDate;
import java.util.UUID;

public record UserRecord(
        UUID id,
        String username,
        String email,
        String fullName,
        String phoneNumber,
        String address,
        String gender,
        LocalDate dob,
        String description,
        String status) {}
```

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record UserExportRecord(
        UUID id,
        UUID userId,
        String username,
        String email,
        String fullName,
        String phoneNumber,
        String address,
        String gender,
        LocalDate dob,
        String description,
        String status,
        Instant exportedAt) {}
```

- [ ] **Step 5: Create `UserExportBatchConfig`**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class UserExportBatchConfig {

    @Bean
    public ItemReader<UserRecord> userItemReader(DataSource dataSource) throws Exception {
        return new JdbcPagingItemReaderBuilder<UserRecord>()
                .name("userItemReader")
                .dataSource(dataSource)
                .selectClause(
                        "id, username, email, full_name, phone_number, address, gender, dob, description, status")
                .fromClause("users")
                .sortKeys(Map.of("id", Order.ASCENDING))
                .pageSize(1000)
                .rowMapper((rs, rowNum) -> new UserRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("phone_number"),
                        rs.getString("address"),
                        rs.getString("gender"),
                        rs.getObject("dob", java.time.LocalDate.class),
                        rs.getString("description"),
                        rs.getString("status")))
                .build();
    }

    @Bean
    public ItemProcessor<UserRecord, UserExportRecord> userExportProcessor() {
        return user -> new UserExportRecord(
                UUID.randomUUID(),
                user.id(),
                user.username(),
                user.email(),
                user.fullName(),
                user.phoneNumber(),
                user.address(),
                user.gender(),
                user.dob(),
                user.description(),
                user.status(),
                Instant.now());
    }

    @Bean
    public ItemWriter<UserExportRecord> userExportWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<UserExportRecord>()
                .dataSource(dataSource)
                .sql("INSERT INTO user_exports "
                        + "(id, user_id, username, email, full_name, phone_number, address, gender, dob, description, status, exported_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setObject(1, item.id());
                    ps.setObject(2, item.userId());
                    ps.setString(3, item.username());
                    ps.setString(4, item.email());
                    ps.setString(5, item.fullName());
                    ps.setString(6, item.phoneNumber());
                    ps.setString(7, item.address());
                    ps.setString(8, item.gender());
                    ps.setObject(9, item.dob());
                    ps.setString(10, item.description());
                    ps.setString(11, item.status());
                    ps.setObject(12, Timestamp.from(item.exportedAt()));
                })
                .build();
    }

    @Bean
    public Step exportUsersStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<UserRecord> userItemReader,
            ItemProcessor<UserRecord, UserExportRecord> userExportProcessor,
            ItemWriter<UserExportRecord> userExportWriter,
            @Value("${app.batch.export.chunk-size:1000}") int chunkSize) {
        return new StepBuilder("exportUsersStep", jobRepository)
                .<UserRecord, UserExportRecord>chunk(chunkSize, transactionManager)
                .reader(userItemReader)
                .processor(userExportProcessor)
                .writer(userExportWriter)
                .build();
    }

    @Bean
    public Job exportUsersJob(JobRepository jobRepository, Step exportUsersStep) {
        return new JobBuilder("exportUsersJob", jobRepository).start(exportUsersStep).build();
    }
}
```

- [ ] **Step 6: Wire `application.yml`**

Add to the `spring:` block (sibling of `quartz:`):

```yaml
  batch:
    job:
      enabled: false
```

Add to the `app:` block (sibling of `http-client:`/`job-action:`):

```yaml
  batch:
    export:
      # Số bản ghi commit mỗi chunk khi export users -> user_exports.
      chunk-size: 1000
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn test -Dtest=UserExportBatchConfigIT`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/corebanking/systemreportjob/infrastructure/config/BatchConfig.java \
        src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserRecord.java \
        src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportRecord.java \
        src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportBatchConfig.java \
        src/main/resources/application.yml \
        src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/UserExportBatchConfigIT.java
git commit -m "feat(system-report-job): add JDBC-backed Spring Batch users export job"
```

---

## Task 5: `SpringBatchJobAction`

**Files:**
- Create: `src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/SpringBatchJobAction.java`
- Test: `src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/SpringBatchJobActionTest.java`

**Interfaces:**
- Consumes: `JobAction` interface (`boolean matches(String)`, `void execute(JobDefinition)` — unchanged,
  see `infrastructure/jobactions/JobAction.java`); `Job exportUsersJob` bean (Task 4); existing
  `jobActionTaskExecutor` bean (`VirtualThreadConfig`, unchanged); existing
  `app.job-action.execution-timeout` config key (unchanged, already used by `HttpCallJobAction`).
- Produces: a `@Component` `JobAction` bean auto-discovered by `JobActionRegistry` — no registry code
  changes.

- [ ] **Step 1: Write the failing unit tests**

Create `src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/SpringBatchJobActionTest.java`:

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

class SpringBatchJobActionTest {

    private final Job exportUsersJob = mock(Job.class);

    private SpringBatchJobAction newAction(JobOperator jobOperator, AsyncTaskExecutor executor) {
        return new SpringBatchJobAction(jobOperator, exportUsersJob, executor, Duration.ofSeconds(30));
    }

    @Test
    void matchesOnlyExportUsersJobType() {
        SpringBatchJobAction action = newAction(
                mock(JobOperator.class), new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));

        assertThat(action.matches("EXPORT_USERS")).isTrue();
        assertThat(action.matches("HTTP_CALL")).isFalse();
    }

    @Test
    void completesSuccessfullyWhenBatchJobCompletes() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution completed = mock(JobExecution.class);
        when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(completed.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobOperator.start(eq(exportUsersJob), any(JobParameters.class))).thenReturn(completed);
        SpringBatchJobAction action = newAction(
                jobOperator, new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "EXPORT_USERS", "{}", null);

        action.execute(definition);

        verify(jobOperator).start(eq(exportUsersJob), any(JobParameters.class));
    }

    @Test
    void throwsWhenBatchJobDoesNotComplete() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution failed = mock(JobExecution.class);
        when(failed.getStatus()).thenReturn(BatchStatus.FAILED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(failed);
        SpringBatchJobAction action = newAction(
                jobOperator, new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "EXPORT_USERS", "{}", null);

        assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void failsFastInsteadOfBlockingForeverWhenActionExceedsTimeout() {
        AsyncTaskExecutor hangingExecutor = new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                // không chạy gì cả
            }

            @Override
            public Future<?> submit(Runnable task) {
                return new CompletableFuture<>();
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return new CompletableFuture<>();
            }
        };
        SpringBatchJobAction action =
                new SpringBatchJobAction(mock(JobOperator.class), exportUsersJob, hangingExecutor, Duration.ofMillis(100));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "EXPORT_USERS", "{}", null);

        long start = System.nanoTime();
        assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quá thời gian chờ");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn test -Dtest=SpringBatchJobActionTest`
Expected: FAIL — compile error, `SpringBatchJobAction` doesn't exist yet.

- [ ] **Step 3: Create `SpringBatchJobAction`**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import java.time.Duration;
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

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.infrastructure.jobactions.JobAction;

@Component
public class SpringBatchJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(SpringBatchJobAction.class);

    private final JobOperator jobOperator;
    private final Job exportUsersJob;
    private final AsyncTaskExecutor jobActionTaskExecutor;
    private final Duration executionTimeout;

    public SpringBatchJobAction(
            JobOperator jobOperator,
            Job exportUsersJob,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
            @Value("${app.job-action.execution-timeout:30s}") Duration executionTimeout) {
        this.jobOperator = jobOperator;
        this.exportUsersJob = exportUsersJob;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean matches(String jobType) {
        return "EXPORT_USERS".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        Future<Void> future = jobActionTaskExecutor.submit(() -> runJob(definition));
        try {
            future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("EXPORT_USERS job action thất bại: " + definition.id(), e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "EXPORT_USERS job action quá thời gian chờ (" + executionTimeout + "): " + definition.id(), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EXPORT_USERS job action bị gián đoạn: " + definition.id(), e);
        }
    }

    private Void runJob(JobDefinition definition) throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("jobDefinitionId", definition.id().toString())
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobOperator.start(exportUsersJob, jobParameters);
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    "EXPORT_USERS batch job kết thúc với trạng thái " + execution.getStatus() + ": " + definition.id());
        }
        log.info("EXPORT_USERS job {} hoàn tất, exitStatus={}", definition.id(), execution.getExitStatus());
        return null;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn test -Dtest=SpringBatchJobActionTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/SpringBatchJobAction.java \
        src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/batch/SpringBatchJobActionTest.java
git commit -m "feat(system-report-job): wire SpringBatchJobAction into the EXPORT_USERS job type"
```

---

## Task 6: End-to-end wiring test

**Files:**
- Create: `src/test/java/com/corebanking/systemreportjob/e2e/EndToEndUserExportTest.java`

**Interfaces:**
- Consumes: `POST /api/job-definitions`, `POST /api/tasks`, `POST /api/tasks/start/{id}` (existing
  controllers, unchanged — same request/response shapes as `EndToEndTaskExecutionTest`); `users` /
  `user_exports` tables (Task 2); `SpringBatchJobAction` (Task 5, picked up automatically by
  `JobActionRegistry` — no test wiring needed).
- Produces: nothing (terminal verification task).

- [ ] **Step 1: Write the test**

```java
package com.corebanking.systemreportjob.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class EndToEndUserExportTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.quartz.job-store-type", () -> "jdbc");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private void seedUsers(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, username, email, full_name, status) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    "e2e-user-" + i,
                    "e2e-user-" + i + "@example.com",
                    "E2E User " + i,
                    "ACTIVE");
        }
    }

    @Test
    void exportsUsersEndToEndWhenTaskFires() throws Exception {
        seedUsers(5);

        String jobDefinitionBody = mockMvc.perform(post("/api/job-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"EXPORT_USERS\",\"expression\":\"{}\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobDefinitionId =
                objectMapper.readTree(jobDefinitionBody).path("data").path("id").asText();

        String taskBody = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
								{"name":"e2e-export-users","group":"e2e","jobDefinitionId":"%s",
								"triggerType":"SIMPLE","intervalInSeconds":1,"repeatCount":0}
								"""
                                        .formatted(jobDefinitionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(taskBody).path("data").path("id").asText();

        mockMvc.perform(post("/api/tasks/start/{id}", taskId)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer exportedCount =
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_exports", Integer.class);
            assertThat(exportedCount).isEqualTo(5);
        });
    }
}
```

- [ ] **Step 2: Run to verify it fails first if run before Tasks 1-5, or passes if run after**

Run: `mvn test -Dtest=EndToEndUserExportTest`
Expected (after Tasks 1-5 are done): PASS.

- [ ] **Step 3: Run the full suite**

Run: `mvn spotless:apply && mvn test`
Expected: `BUILD SUCCESS`, all tests green (Docker must be running).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/corebanking/systemreportjob/e2e/EndToEndUserExportTest.java
git commit -m "test(system-report-job): add end-to-end coverage for the EXPORT_USERS job"
```

---

## Self-Review Notes

- **Spec coverage:** §2 (data model) → Task 2. §3 (mock seed) → Task 3. §4 (batch job design) → Tasks 4-5.
  §5 (wiring) → Task 3 README + Task 6 e2e test. §6 (pom/yml) → Tasks 1, 4. §7 (testing strategy) → Tasks
  2, 4, 5, 6. §8 (out of scope) respected — no domain/port/controller added for `users`.
- **Placeholder scan:** none — every step has real, complete code; the seed script and README have exact
  commands, not descriptions.
- **Type consistency:** `UserRecord`/`UserExportRecord` field names and order match between Task 4's
  reader/processor/writer and are unchanged in Task 5/6 (only consumed as opaque `Job exportUsersJob`
  there). `SpringBatchJobAction` constructor signature (`JobOperator, Job, AsyncTaskExecutor, Duration`)
  matches exactly between Task 5's implementation and its test.
