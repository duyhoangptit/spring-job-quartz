# system-report-job Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `system-report-job`, a standalone Spring Boot 4.1 / Java 21 service (Clean Architecture) that replaces the legacy Quartz-based job scheduler `system-report-job` in the `vn.tiger:microservice-java` monorepo, as a new sibling service inside the `core-banking-10000tps` repo.

**Architecture:** Clean Architecture — `domain` (framework-free models/exceptions) → `usecase` (ports in/out + services) → `infrastructure` (Quartz, JPA/Flyway, REST, job-action strategies). A single generic Quartz `Job` (`ScheduledJobExecutor`) dispatches to pluggable `JobAction` strategies (HTTP-call or in-process) resolved by `JobDefinition.jobType`, replacing the old reflection-based `className`/`packageName` job loading.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA + PostgreSQL, Flyway, Quartz (JDBC JobStore), Lombok, cron-utils, springdoc-openapi, JUnit 5, Mockito, Testcontainers, Awaitility.

**Spec:** `docs/superpowers/specs/2026-08-09-system-report-job-v2-design.md`

## Global Constraints

- Project root: `/Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/` (new directory in the existing `core-banking-10000tps` git repo — do NOT `git init` here, it's already a repo).
- groupId `com.corebanking`, artifactId `system-report-job`, base package `com.corebanking.systemreportjob`.
- Java 21, Spring Boot parent `4.1.0`. Standalone `pom.xml` — no parent module, no dependency on the old monorepo or its `common-cores` library.
- Domain layer (`domain/`) must never import Spring, JPA, or Quartz types.
- Usecase layer (`usecase/`) depends only on `domain` and its own port interfaces — never on Spring/JPA/Quartz concrete types.
- No dynamic class loading / reflection to decide "what a job runs" — dispatch is via the `JobAction` registry keyed by `JobDefinition.jobType`.
- Every task that touches DB schema goes through Flyway (`src/main/resources/db/migration/`) — never `ddl-auto: update`.
- Soft-delete entities use `@SQLRestriction("is_deleted = false")` so deleted rows never leak into reads (fixes a real bug found in the legacy code).
- Build/test verification command for every task unless stated otherwise: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test`.
- Commit after each task from inside `/Users/tigerpro/Documents/SA/core-banking-10000tps` (the repo root), with `git add system-report-job/...`.

**Corrections found during Task 9 execution (apply everywhere below, superseding what was originally written):**
- Spring Boot 4.1 removed the legacy `@MockBean` (`org.springframework.boot.test.mock.mockito.MockBean`). Every test in this plan uses `@MockitoBean` (`org.springframework.test.context.bean.override.mockito.MockitoBean`) instead — same usage, different import/annotation name. All task text below has already been updated to `@MockitoBean`.
- `testcontainers.version` must be `1.21.4`, not `1.20.4` — 1.20.4's bundled docker-java fails to negotiate with OrbStack's Docker Engine (`MinAPIVersion 1.40`) and reports "client version 1.32 is too old". Task 9 raises this version in `pom.xml`; it was `1.20.4` from Task 1 only because this incompatibility wasn't known yet.
- Spring Boot 4.1 moved Flyway autoconfiguration out of the core autoconfigure jar into a dedicated `org.springframework.boot:spring-boot-starter-flyway` module — without it, `flyway-core`/`flyway-database-postgresql` sit on the classpath but no `Flyway` bean is ever created and migrations silently never run (no error, no log line). Task 9 adds this starter to `pom.xml`.
- Any `@SpringBootTest` dispatched before Task 17 (which is when the last usecase out-port, `JobActionExecutorPort`, gets its first real adapter) boots the **entire** `com.corebanking.systemreportjob` component-scanned context, including every `@Service` from Tasks 5-8 — and those constructors need `TaskRepositoryPort`, `JobDefinitionRepositoryPort`, `TaskExecutionHistoryRepositoryPort`, `SchedulerGatewayPort`, and `JobActionExecutorPort` satisfied. Until each port has a real adapter, any bare `@SpringBootTest` in this plan (Tasks 9, 14) must `@MockitoBean` all five out-ports so the context can start. Tasks 15 and 16 already sidestep this by `@MockitoBean`-ing `ExecuteScheduledJobUseCase` (which replaces the one bean — `JobExecutionOrchestrator` — that would otherwise need the still-missing `JobActionExecutorPort`), so they need no further change.
- **General pattern (found in Tasks 9 and 10, applies everywhere below):** Spring Boot 4.1 split the old monolithic `spring-boot-test-autoconfigure` jar into many per-feature modules, each with its own dedicated starter and a *new package* for the annotation. `spring-boot-starter-test` alone no longer carries `@DataJpaTest`, `@WebMvcTest`, `@AutoConfigureMockMvc`, etc. This plan's task text has already been corrected everywhere it appears:
  - `@DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` (needs `spring-boot-starter-data-jpa-test`, added in Task 10 — Tasks 11-12 reuse it).
  - `@AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase` (same starter as above).
  - `@WebMvcTest` / `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure.{WebMvcTest,AutoConfigureMockMvc}` (needs `spring-boot-starter-webmvc-test`, added in Task 21 — Tasks 22, 23, 25 reuse it).
  - If a later task hits the same "class/package doesn't exist" error for some *other* `@Data*Test`-style annotation not listed here, this is why — look for a same-named `spring-boot-starter-<feature>-test` module in the local `.m2` repository before assuming the brief is wrong in some other way.

---

## Phase 0 — Project scaffold

### Task 1: Maven project scaffold & boot skeleton

**Files:**
- Create: `system-report-job/pom.xml`
- Create: `system-report-job/.gitignore`
- Create: `system-report-job/CLAUDE.md`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/JobApplication.java`
- Create: `system-report-job/src/main/resources/application.yml`

**Interfaces:**
- Produces: `JobApplication` (Spring Boot entrypoint, no other task depends on its internals — later tasks add config/beans that get auto-scanned under `com.corebanking.systemreportjob`).

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.corebanking</groupId>
    <artifactId>system-report-job</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>system-report-job</name>
    <description>Dynamic Quartz job/trigger management service (Clean Architecture, Spring Boot 4.1)</description>

    <properties>
        <java.version>21</java.version>
        <lombok.version>1.18.34</lombok.version>
        <spotless.version>2.43.0</spotless.version>
        <cron-utils.version>9.2.1</cron-utils.version>
        <testcontainers.version>1.20.4</testcontainers.version>
        <awaitility.version>4.2.2</awaitility.version>
        <springdoc.version>2.7.0</springdoc.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-quartz</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.cronutils</groupId>
            <artifactId>cron-utils</artifactId>
            <version>${cron-utils.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <version>${awaitility.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
            <plugin>
                <groupId>com.diffplug.spotless</groupId>
                <artifactId>spotless-maven-plugin</artifactId>
                <version>${spotless.version}</version>
                <configuration>
                    <java>
                        <removeUnusedImports/>
                        <toggleOffOn/>
                        <trimTrailingWhitespace/>
                        <endWithNewline/>
                        <indent>
                            <tabs>true</tabs>
                            <spacesPerTab>4</spacesPerTab>
                        </indent>
                        <palantirJavaFormat/>
                        <importOrder>
                            <order>java,jakarta,org,com,com.diffplug,</order>
                        </importOrder>
                    </java>
                </configuration>
                <executions>
                    <execution>
                        <phase>compile</phase>
                        <goals>
                            <goal>apply</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create `.gitignore`**

```
target/
*.class
.idea/
*.iml
.DS_Store
```

- [ ] **Step 3: Create `CLAUDE.md`**

```markdown
# system-report-job

Dynamic Quartz job/trigger management service. Clean Architecture: `domain` → `usecase` → `infrastructure`.
See `docs/superpowers/specs/2026-08-09-system-report-job-v2-design.md` (repo root) for the full design,
and `docs/superpowers/plans/2026-08-10-system-report-job-implementation.md` for the implementation plan.

- Base package: `com.corebanking.systemreportjob`
- Java 21, Spring Boot 4.1.0
- `domain/` must never import Spring/JPA/Quartz types.
- Schema changes go through Flyway (`src/main/resources/db/migration/`) — never `ddl-auto`.
```

- [ ] **Step 4: Create `JobApplication.java`**

```java
package com.corebanking.systemreportjob;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JobApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobApplication.class, args);
    }
}
```

- [ ] **Step 5: Create a minimal `application.yml`** (full datasource/quartz/flyway config added in Task 24 — this stub only sets the app name so `mvn compile` has a valid resource dir)

```yaml
spring:
  application:
    name: system-report-job
```

- [ ] **Step 6: Verify the project compiles**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml compile`
Expected: `BUILD SUCCESS` (no output on `-q` success), exit code 0.

- [ ] **Step 7: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "chore(system-report-job): scaffold standalone Spring Boot 4.1 project"
```

---

## Phase 1 — Domain layer

### Task 2: Domain model records

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/TriggerDefinition.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/TriggerState.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/JobDefinition.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/ScheduledTask.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/TaskExecutionRecord.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/TaskDetail.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/model/PageResult.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/domain/model/ScheduledTaskTest.java`

**Interfaces:**
- Produces: all 7 types above — consumed by every later task (usecase ports, services, persistence adapters, controllers). Signatures are final as written here; do not rename fields later.

- [ ] **Step 1: Create `TriggerDefinition.java`**

```java
package com.corebanking.systemreportjob.domain.model;

import java.time.LocalTime;

public sealed interface TriggerDefinition {
    record Cron(String cronExpression) implements TriggerDefinition {}

    record Simple(int intervalInSeconds, int repeatCount) implements TriggerDefinition {}

    record CalendarInterval(int intervalInDays) implements TriggerDefinition {}

    record DailyTimeInterval(LocalTime startingDailyAt, LocalTime endingDailyAt, int intervalInMinutes)
            implements TriggerDefinition {}
}
```

- [ ] **Step 2: Create `TriggerState.java`**

```java
package com.corebanking.systemreportjob.domain.model;

public enum TriggerState {
    NONE,
    NORMAL,
    PAUSED,
    COMPLETE,
    ERROR,
    BLOCKED
}
```

- [ ] **Step 3: Create `JobDefinition.java`**

```java
package com.corebanking.systemreportjob.domain.model;

import java.util.UUID;

public record JobDefinition(UUID id, String jobType, String expression, String description) {
    public JobDefinition {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType không được rỗng");
        }
    }
}
```

- [ ] **Step 4: Write the failing test for `ScheduledTask`**

```java
package com.corebanking.systemreportjob.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScheduledTaskTest {

    @Test
    void constructsWithValidFields() {
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                "daily-report",
                "reports",
                jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"),
                "Asia/Ho_Chi_Minh",
                5,
                "Daily report task");

        assertThat(task.name()).isEqualTo("daily-report");
        assertThat(task.jobDefinitionId()).isEqualTo(jobDefinitionId);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new ScheduledTask(
                        UUID.randomUUID(),
                        "  ",
                        "reports",
                        UUID.randomUUID(),
                        new TriggerDefinition.Simple(60, 0),
                        "UTC",
                        1,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tên task");
    }

    @Test
    void rejectsNullJobDefinitionId() {
        assertThatThrownBy(() -> new ScheduledTask(
                        UUID.randomUUID(),
                        "daily-report",
                        "reports",
                        null,
                        new TriggerDefinition.Simple(60, 0),
                        "UTC",
                        1,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JobDefinition");
    }
}
```

- [ ] **Step 5: Run the test to verify it fails (types don't exist yet)**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=ScheduledTaskTest`
Expected: compile error — `ScheduledTask` does not exist.

- [ ] **Step 6: Create `ScheduledTask.java`**

```java
package com.corebanking.systemreportjob.domain.model;

import java.util.UUID;

public record ScheduledTask(
        UUID id,
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
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
}
```

- [ ] **Step 7: Create the remaining records**

```java
// TaskExecutionRecord.java
package com.corebanking.systemreportjob.domain.model;

import java.time.Instant;
import java.util.UUID;

public record TaskExecutionRecord(UUID id, UUID taskId, String taskName, Instant startTime, Instant endTime, String exceptionMessage) {}
```

```java
// TaskDetail.java
package com.corebanking.systemreportjob.domain.model;

public record TaskDetail(ScheduledTask task, JobDefinition jobDefinition, TriggerState state) {}
```

```java
// PageResult.java
package com.corebanking.systemreportjob.domain.model;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=ScheduledTaskTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 9: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add domain model records"
```

### Task 3: Domain exceptions & error codes

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/exception/ErrorCode.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/exception/BusinessException.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/exception/TaskNotFoundException.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/domain/exception/JobDefinitionNotFoundException.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/domain/exception/BusinessExceptionTest.java`

**Interfaces:**
- Consumes: nothing (pure domain).
- Produces: `ErrorCode` enum (`TASK_NOT_FOUND`, `JOB_DEFINITION_NOT_FOUND`, `CRON_INVALID`, `VALIDATION_ERROR`, each with `getMessageKey()`), `BusinessException(ErrorCode, Object... messageArgs)` with `getErrorCode()`/`getMessageArgs()`, and the two typed subclasses. `infrastructure/common/GlobalExceptionHandler` (Task 20) and every usecase service (Tasks 5-8) depend on these exact names.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void carriesErrorCodeAndArgs() {
        UUID id = UUID.randomUUID();
        TaskNotFoundException ex = new TaskNotFoundException(id);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_NOT_FOUND);
        assertThat(ex.getMessageArgs()).containsExactly(id);
    }

    @Test
    void jobDefinitionNotFoundCarriesCode() {
        UUID id = UUID.randomUUID();
        JobDefinitionNotFoundException ex = new JobDefinitionNotFoundException(id);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.JOB_DEFINITION_NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=BusinessExceptionTest`
Expected: compile error — types don't exist.

- [ ] **Step 3: Create `ErrorCode.java`**

```java
package com.corebanking.systemreportjob.domain.exception;

public enum ErrorCode {
    TASK_NOT_FOUND("task.not_found"),
    JOB_DEFINITION_NOT_FOUND("job_definition.not_found"),
    CRON_INVALID("cron.invalid"),
    VALIDATION_ERROR("validation.error");

    private final String messageKey;

    ErrorCode(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
```

- [ ] **Step 4: Create `BusinessException.java`**

```java
package com.corebanking.systemreportjob.domain.exception;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object[] messageArgs;

    public BusinessException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.messageArgs = messageArgs;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object[] getMessageArgs() {
        return messageArgs;
    }
}
```

- [ ] **Step 5: Create the two typed subclasses**

```java
package com.corebanking.systemreportjob.domain.exception;

import java.util.UUID;

public class TaskNotFoundException extends BusinessException {
    public TaskNotFoundException(UUID taskId) {
        super(ErrorCode.TASK_NOT_FOUND, taskId);
    }
}
```

```java
package com.corebanking.systemreportjob.domain.exception;

import java.util.UUID;

public class JobDefinitionNotFoundException extends BusinessException {
    public JobDefinitionNotFoundException(UUID jobDefinitionId) {
        super(ErrorCode.JOB_DEFINITION_NOT_FOUND, jobDefinitionId);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=BusinessExceptionTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add domain exceptions and error codes"
```

---

## Phase 2 — Usecase ports

### Task 4: Usecase ports (in/out) and command types

Pure interfaces/records — no independent business logic to unit-test, so verification is a compile check. Every later usecase/infrastructure task depends on the exact names below; do not rename fields afterwards.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/TaskManagementUseCase.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/JobDefinitionUseCase.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/TaskHistoryQueryUseCase.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/ExecuteScheduledJobUseCase.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/CreateTaskCommand.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/CreateJobDefinitionCommand.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/in/UpdateJobDefinitionCommand.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/out/TaskRepositoryPort.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/out/JobDefinitionRepositoryPort.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/out/TaskExecutionHistoryRepositoryPort.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/out/SchedulerGatewayPort.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/ports/out/JobActionExecutorPort.java`

**Interfaces:**
- Consumes: `ScheduledTask`, `JobDefinition`, `TriggerDefinition`, `TaskExecutionRecord`, `TaskDetail`, `PageResult<T>`, `TriggerState` (Task 2).
- Produces: all port interfaces + command records below — consumed by Tasks 5-8 (services implement the `in` ports and depend on the `out` ports) and Tasks 10-19 (infrastructure adapters implement the `out` ports).

- [ ] **Step 1: Create the command records**

```java
// CreateTaskCommand.java
package com.corebanking.systemreportjob.usecase.ports.in;

import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import java.util.UUID;

public record CreateTaskCommand(
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String timezoneId,
        Integer priority,
        String description) {}
```

```java
// CreateJobDefinitionCommand.java
package com.corebanking.systemreportjob.usecase.ports.in;

public record CreateJobDefinitionCommand(String jobType, String expression, String description) {}
```

```java
// UpdateJobDefinitionCommand.java
package com.corebanking.systemreportjob.usecase.ports.in;

public record UpdateJobDefinitionCommand(String jobType, String expression, String description) {}
```

- [ ] **Step 2: Create the in-ports**

```java
// TaskManagementUseCase.java
package com.corebanking.systemreportjob.usecase.ports.in;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TaskDetail;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

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
```

```java
// JobDefinitionUseCase.java
package com.corebanking.systemreportjob.usecase.ports.in;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import java.util.UUID;

public interface JobDefinitionUseCase {
    JobDefinition create(CreateJobDefinitionCommand command);

    JobDefinition update(UUID id, UpdateJobDefinitionCommand command);

    void delete(UUID id);
}
```

```java
// TaskHistoryQueryUseCase.java
package com.corebanking.systemreportjob.usecase.ports.in;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import org.springframework.data.domain.Pageable;

public interface TaskHistoryQueryUseCase {
    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}
```

```java
// ExecuteScheduledJobUseCase.java
package com.corebanking.systemreportjob.usecase.ports.in;

import java.util.UUID;

public interface ExecuteScheduledJobUseCase {
    void execute(UUID taskId);
}
```

- [ ] **Step 3: Create the out-ports**

```java
// TaskRepositoryPort.java
package com.corebanking.systemreportjob.usecase.ports.out;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

public interface TaskRepositoryPort {
    ScheduledTask save(ScheduledTask task);

    Optional<ScheduledTask> findById(UUID id);

    List<ScheduledTask> findAll();

    PageResult<ScheduledTask> search(String keyword, Pageable pageable);

    void delete(UUID id);
}
```

```java
// JobDefinitionRepositoryPort.java
package com.corebanking.systemreportjob.usecase.ports.out;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import java.util.Optional;
import java.util.UUID;

public interface JobDefinitionRepositoryPort {
    JobDefinition save(JobDefinition definition);

    Optional<JobDefinition> findById(UUID id);

    void delete(UUID id);
}
```

```java
// TaskExecutionHistoryRepositoryPort.java
package com.corebanking.systemreportjob.usecase.ports.out;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import org.springframework.data.domain.Pageable;

public interface TaskExecutionHistoryRepositoryPort {
    TaskExecutionRecord save(TaskExecutionRecord record);

    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}
```

```java
// SchedulerGatewayPort.java
package com.corebanking.systemreportjob.usecase.ports.out;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerState;
import java.util.UUID;

public interface SchedulerGatewayPort {
    void scheduleTask(ScheduledTask task);

    void unscheduleTask(UUID taskId);

    void pauseTask(UUID taskId);

    void resumeTask(UUID taskId);

    TriggerState getTriggerState(UUID taskId);
}
```

```java
// JobActionExecutorPort.java
package com.corebanking.systemreportjob.usecase.ports.out;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

public interface JobActionExecutorPort {
    void execute(JobDefinition definition);
}
```

- [ ] **Step 4: Verify the project compiles**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add usecase ports (in/out) and command types"
```

---

## Phase 3 — Usecase services

### Task 5: `TaskOrchestrator` (implements `TaskManagementUseCase`)

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/service/TaskOrchestrator.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/usecase/service/TaskOrchestratorTest.java`

**Interfaces:**
- Consumes: `TaskRepositoryPort`, `JobDefinitionRepositoryPort`, `SchedulerGatewayPort` (Task 4, mocked in test), `TaskNotFoundException`, `JobDefinitionNotFoundException` (Task 3).
- Produces: `TaskOrchestrator(TaskRepositoryPort, JobDefinitionRepositoryPort, SchedulerGatewayPort)` implementing all 8 `TaskManagementUseCase` methods — the `web/controller/TaskController` (Task 21) is injected with this via its `TaskManagementUseCase` interface type.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.*;
import com.corebanking.systemreportjob.usecase.ports.in.CreateTaskCommand;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.SchedulerGatewayPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class TaskOrchestratorTest {

    private TaskRepositoryPort taskRepositoryPort;
    private JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private SchedulerGatewayPort schedulerGatewayPort;
    private TaskOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        taskRepositoryPort = mock(TaskRepositoryPort.class);
        jobDefinitionRepositoryPort = mock(JobDefinitionRepositoryPort.class);
        schedulerGatewayPort = mock(SchedulerGatewayPort.class);
        orchestrator = new TaskOrchestrator(taskRepositoryPort, jobDefinitionRepositoryPort, schedulerGatewayPort);
    }

    private JobDefinition sampleJobDefinition(UUID id) {
        return new JobDefinition(id, "ECHO", "{}", "sample");
    }

    private ScheduledTask sampleTask(UUID id, UUID jobDefinitionId) {
        return new ScheduledTask(
                id, "daily-report", "reports", jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"), "UTC", 5, null);
    }

    @Test
    void createSavesTaskWhenJobDefinitionExists() {
        UUID jobDefinitionId = UUID.randomUUID();
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId))
                .thenReturn(Optional.of(sampleJobDefinition(jobDefinitionId)));
        when(taskRepositoryPort.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTaskCommand command = new CreateTaskCommand(
                "daily-report", "reports", jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"), "UTC", 5, null);

        ScheduledTask result = orchestrator.create(command);

        assertThat(result.name()).isEqualTo("daily-report");
        verify(taskRepositoryPort).save(any(ScheduledTask.class));
    }

    @Test
    void createThrowsWhenJobDefinitionMissing() {
        UUID jobDefinitionId = UUID.randomUUID();
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.empty());

        CreateTaskCommand command = new CreateTaskCommand(
                "daily-report", "reports", jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"), "UTC", 5, null);

        assertThatThrownBy(() -> orchestrator.create(command)).isInstanceOf(JobDefinitionNotFoundException.class);
        verifyNoInteractions(taskRepositoryPort);
    }

    @Test
    void startSchedulesTaskViaGateway() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask task = sampleTask(taskId, UUID.randomUUID());
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));

        orchestrator.start(taskId);

        verify(schedulerGatewayPort).scheduleTask(task);
    }

    @Test
    void startThrowsWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.start(taskId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(schedulerGatewayPort);
    }

    @Test
    void pauseDelegatesToGatewayAfterExistenceCheck() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(sampleTask(taskId, UUID.randomUUID())));

        orchestrator.pause(taskId);

        verify(schedulerGatewayPort).pauseTask(taskId);
    }

    @Test
    void resumeDelegatesToGatewayAfterExistenceCheck() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(sampleTask(taskId, UUID.randomUUID())));

        orchestrator.resume(taskId);

        verify(schedulerGatewayPort).resumeTask(taskId);
    }

    @Test
    void deleteUnschedulesThenRemovesFromRepository() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(sampleTask(taskId, UUID.randomUUID())));

        orchestrator.delete(taskId);

        var inOrder = inOrder(schedulerGatewayPort, taskRepositoryPort);
        inOrder.verify(schedulerGatewayPort).unscheduleTask(taskId);
        inOrder.verify(taskRepositoryPort).delete(taskId);
    }

    @Test
    void startAllSchedulesEveryTask() {
        ScheduledTask t1 = sampleTask(UUID.randomUUID(), UUID.randomUUID());
        ScheduledTask t2 = sampleTask(UUID.randomUUID(), UUID.randomUUID());
        when(taskRepositoryPort.findAll()).thenReturn(List.of(t1, t2));

        orchestrator.startAll();

        verify(schedulerGatewayPort).scheduleTask(t1);
        verify(schedulerGatewayPort).scheduleTask(t2);
    }

    @Test
    void searchDelegatesToRepositoryPort() {
        PageResult<ScheduledTask> expected = new PageResult<>(List.of(), 0, 20, 0, 0);
        when(taskRepositoryPort.search("report", PageRequest.of(0, 20))).thenReturn(expected);

        PageResult<ScheduledTask> result = orchestrator.search("report", PageRequest.of(0, 20));

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getDetailCombinesTaskJobDefinitionAndTriggerState() {
        UUID taskId = UUID.randomUUID();
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = sampleTask(taskId, jobDefinitionId);
        JobDefinition definition = sampleJobDefinition(jobDefinitionId);
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.of(definition));
        when(schedulerGatewayPort.getTriggerState(taskId)).thenReturn(TriggerState.NORMAL);

        TaskDetail detail = orchestrator.getDetail(taskId);

        assertThat(detail.task()).isEqualTo(task);
        assertThat(detail.jobDefinition()).isEqualTo(definition);
        assertThat(detail.state()).isEqualTo(TriggerState.NORMAL);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskOrchestratorTest`
Expected: compile error — `TaskOrchestrator` does not exist.

- [ ] **Step 3: Implement `TaskOrchestrator.java`**

```java
package com.corebanking.systemreportjob.usecase.service;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TaskDetail;
import com.corebanking.systemreportjob.usecase.ports.in.CreateTaskCommand;
import com.corebanking.systemreportjob.usecase.ports.in.TaskManagementUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.SchedulerGatewayPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class TaskOrchestrator implements TaskManagementUseCase {

    private final TaskRepositoryPort taskRepositoryPort;
    private final JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private final SchedulerGatewayPort schedulerGatewayPort;

    public TaskOrchestrator(
            TaskRepositoryPort taskRepositoryPort,
            JobDefinitionRepositoryPort jobDefinitionRepositoryPort,
            SchedulerGatewayPort schedulerGatewayPort) {
        this.taskRepositoryPort = taskRepositoryPort;
        this.jobDefinitionRepositoryPort = jobDefinitionRepositoryPort;
        this.schedulerGatewayPort = schedulerGatewayPort;
    }

    @Override
    public ScheduledTask create(CreateTaskCommand command) {
        jobDefinitionRepositoryPort
                .findById(command.jobDefinitionId())
                .orElseThrow(() -> new JobDefinitionNotFoundException(command.jobDefinitionId()));

        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                command.name(),
                command.group(),
                command.jobDefinitionId(),
                command.trigger(),
                command.timezoneId(),
                command.priority(),
                command.description());
        return taskRepositoryPort.save(task);
    }

    @Override
    public void start(UUID taskId) {
        schedulerGatewayPort.scheduleTask(requireTask(taskId));
    }

    @Override
    public void pause(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.pauseTask(taskId);
    }

    @Override
    public void resume(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.resumeTask(taskId);
    }

    @Override
    public void delete(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.unscheduleTask(taskId);
        taskRepositoryPort.delete(taskId);
    }

    @Override
    public void startAll() {
        taskRepositoryPort.findAll().forEach(schedulerGatewayPort::scheduleTask);
    }

    @Override
    public PageResult<ScheduledTask> search(String keyword, Pageable pageable) {
        return taskRepositoryPort.search(keyword, pageable);
    }

    @Override
    public TaskDetail getDetail(UUID taskId) {
        ScheduledTask task = requireTask(taskId);
        JobDefinition definition = jobDefinitionRepositoryPort
                .findById(task.jobDefinitionId())
                .orElseThrow(() -> new JobDefinitionNotFoundException(task.jobDefinitionId()));
        return new TaskDetail(task, definition, schedulerGatewayPort.getTriggerState(taskId));
    }

    private ScheduledTask requireTask(UUID taskId) {
        return taskRepositoryPort.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskOrchestratorTest`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): implement TaskOrchestrator usecase service"
```

### Task 6: `JobDefinitionService` (implements `JobDefinitionUseCase`)

This is the class that fixes the legacy bug where `PUT /api/task-config/{id}` accidentally called `delete` instead of updating.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/service/JobDefinitionService.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/usecase/service/JobDefinitionServiceTest.java`

**Interfaces:**
- Consumes: `JobDefinitionRepositoryPort` (Task 4, mocked), `JobDefinitionNotFoundException` (Task 3).
- Produces: `JobDefinitionService(JobDefinitionRepositoryPort)` implementing `create`/`update`/`delete` — consumed by `web/controller/JobDefinitionController` (Task 22).

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.in.UpdateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobDefinitionServiceTest {

    private JobDefinitionRepositoryPort repositoryPort;
    private JobDefinitionService service;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(JobDefinitionRepositoryPort.class);
        service = new JobDefinitionService(repositoryPort);
    }

    @Test
    void createSavesNewJobDefinition() {
        when(repositoryPort.save(any(JobDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        JobDefinition result = service.create(new CreateJobDefinitionCommand("HTTP_CALL", "{}", "desc"));

        assertThat(result.jobType()).isEqualTo("HTTP_CALL");
        verify(repositoryPort).save(any(JobDefinition.class));
    }

    @Test
    void updateActuallyUpdatesTheRecord() {
        UUID id = UUID.randomUUID();
        JobDefinition existing = new JobDefinition(id, "HTTP_CALL", "{}", "old");
        when(repositoryPort.findById(id)).thenReturn(Optional.of(existing));
        when(repositoryPort.save(any(JobDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        JobDefinition result = service.update(id, new UpdateJobDefinitionCommand("ECHO", "{\"msg\":\"hi\"}", "new"));

        assertThat(result.jobType()).isEqualTo("ECHO");
        assertThat(result.description()).isEqualTo("new");
        verify(repositoryPort, never()).delete(any());
    }

    @Test
    void updateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repositoryPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new UpdateJobDefinitionCommand("ECHO", "{}", null)))
                .isInstanceOf(JobDefinitionNotFoundException.class);
    }

    @Test
    void deleteDelegatesToRepositoryPort() {
        UUID id = UUID.randomUUID();

        service.delete(id);

        verify(repositoryPort).delete(id);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobDefinitionServiceTest`
Expected: compile error — `JobDefinitionService` does not exist.

- [ ] **Step 3: Implement `JobDefinitionService.java`**

```java
package com.corebanking.systemreportjob.usecase.service;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.in.JobDefinitionUseCase;
import com.corebanking.systemreportjob.usecase.ports.in.UpdateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobDefinitionService implements JobDefinitionUseCase {

    private final JobDefinitionRepositoryPort repositoryPort;

    public JobDefinitionService(JobDefinitionRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public JobDefinition create(CreateJobDefinitionCommand command) {
        JobDefinition definition =
                new JobDefinition(UUID.randomUUID(), command.jobType(), command.expression(), command.description());
        return repositoryPort.save(definition);
    }

    @Override
    public JobDefinition update(UUID id, UpdateJobDefinitionCommand command) {
        repositoryPort.findById(id).orElseThrow(() -> new JobDefinitionNotFoundException(id));
        JobDefinition updated = new JobDefinition(id, command.jobType(), command.expression(), command.description());
        return repositoryPort.save(updated);
    }

    @Override
    public void delete(UUID id) {
        repositoryPort.delete(id);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobDefinitionServiceTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): implement JobDefinitionService, fixes legacy update-vs-delete bug"
```

### Task 7: `TaskHistoryQueryService` (implements `TaskHistoryQueryUseCase`)

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/service/TaskHistoryQueryService.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/usecase/service/TaskHistoryQueryServiceTest.java`

**Interfaces:**
- Consumes: `TaskExecutionHistoryRepositoryPort` (Task 4, mocked).
- Produces: `TaskHistoryQueryService(TaskExecutionHistoryRepositoryPort)` implementing `search` — consumed by `web/controller/TaskHistoryController` (Task 23).

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class TaskHistoryQueryServiceTest {

    @Test
    void searchDelegatesToRepositoryPort() {
        TaskExecutionHistoryRepositoryPort repositoryPort = mock(TaskExecutionHistoryRepositoryPort.class);
        PageResult<TaskExecutionRecord> expected = new PageResult<>(List.of(), 0, 20, 0, 0);
        when(repositoryPort.search("daily-report", PageRequest.of(0, 20))).thenReturn(expected);
        TaskHistoryQueryService service = new TaskHistoryQueryService(repositoryPort);

        PageResult<TaskExecutionRecord> result = service.search("daily-report", PageRequest.of(0, 20));

        assertThat(result).isSameAs(expected);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskHistoryQueryServiceTest`
Expected: compile error — `TaskHistoryQueryService` does not exist.

- [ ] **Step 3: Implement `TaskHistoryQueryService.java`**

```java
package com.corebanking.systemreportjob.usecase.service;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.usecase.ports.in.TaskHistoryQueryUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class TaskHistoryQueryService implements TaskHistoryQueryUseCase {

    private final TaskExecutionHistoryRepositoryPort repositoryPort;

    public TaskHistoryQueryService(TaskExecutionHistoryRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable) {
        return repositoryPort.search(taskName, pageable);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskHistoryQueryServiceTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): implement TaskHistoryQueryService"
```

### Task 8: `JobExecutionOrchestrator` (implements `ExecuteScheduledJobUseCase`)

The single entry point that `infrastructure/scheduler/ScheduledJobExecutor` (Task 15) calls when Quartz fires a trigger.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/usecase/service/JobExecutionOrchestrator.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/usecase/service/JobExecutionOrchestratorTest.java`

**Interfaces:**
- Consumes: `TaskRepositoryPort`, `JobDefinitionRepositoryPort`, `JobActionExecutorPort` (Task 4, mocked), `TaskNotFoundException`, `JobDefinitionNotFoundException` (Task 3).
- Produces: `JobExecutionOrchestrator(TaskRepositoryPort, JobDefinitionRepositoryPort, JobActionExecutorPort)` implementing `execute(UUID taskId)` — consumed by `infrastructure/scheduler/ScheduledJobExecutor` (Task 15).

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobExecutionOrchestratorTest {

    private TaskRepositoryPort taskRepositoryPort;
    private JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private JobActionExecutorPort jobActionExecutorPort;
    private JobExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        taskRepositoryPort = mock(TaskRepositoryPort.class);
        jobDefinitionRepositoryPort = mock(JobDefinitionRepositoryPort.class);
        jobActionExecutorPort = mock(JobActionExecutorPort.class);
        orchestrator =
                new JobExecutionOrchestrator(taskRepositoryPort, jobDefinitionRepositoryPort, jobActionExecutorPort);
    }

    @Test
    void dispatchesToJobActionExecutorForResolvedJobDefinition() {
        UUID taskId = UUID.randomUUID();
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                taskId, "daily-report", "reports", jobDefinitionId,
                new TriggerDefinition.Simple(60, 0), "UTC", 1, null);
        JobDefinition definition = new JobDefinition(jobDefinitionId, "ECHO", "{}", null);
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.of(definition));

        orchestrator.execute(taskId);

        verify(jobActionExecutorPort).execute(definition);
    }

    @Test
    void throwsTaskNotFoundWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.execute(taskId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(jobActionExecutorPort);
    }

    @Test
    void throwsJobDefinitionNotFoundWhenDefinitionMissing() {
        UUID taskId = UUID.randomUUID();
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                taskId, "daily-report", "reports", jobDefinitionId,
                new TriggerDefinition.Simple(60, 0), "UTC", 1, null);
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.execute(taskId)).isInstanceOf(JobDefinitionNotFoundException.class);
        verifyNoInteractions(jobActionExecutorPort);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobExecutionOrchestratorTest`
Expected: compile error — `JobExecutionOrchestrator` does not exist.

- [ ] **Step 3: Implement `JobExecutionOrchestrator.java`**

```java
package com.corebanking.systemreportjob.usecase.service;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobExecutionOrchestrator implements ExecuteScheduledJobUseCase {

    private final TaskRepositoryPort taskRepositoryPort;
    private final JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private final JobActionExecutorPort jobActionExecutorPort;

    public JobExecutionOrchestrator(
            TaskRepositoryPort taskRepositoryPort,
            JobDefinitionRepositoryPort jobDefinitionRepositoryPort,
            JobActionExecutorPort jobActionExecutorPort) {
        this.taskRepositoryPort = taskRepositoryPort;
        this.jobDefinitionRepositoryPort = jobDefinitionRepositoryPort;
        this.jobActionExecutorPort = jobActionExecutorPort;
    }

    @Override
    public void execute(UUID taskId) {
        ScheduledTask task = taskRepositoryPort.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        JobDefinition definition = jobDefinitionRepositoryPort
                .findById(task.jobDefinitionId())
                .orElseThrow(() -> new JobDefinitionNotFoundException(task.jobDefinitionId()));
        jobActionExecutorPort.execute(definition);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobExecutionOrchestratorTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): implement JobExecutionOrchestrator"
```

---

## Phase 4 — Infrastructure: persistence

### Task 9: Flyway migrations & `BaseEntity`

**Files:**
- Create: `system-report-job/src/main/resources/db/migration/V1__create_quartz_tables.sql`
- Create: `system-report-job/src/main/resources/db/migration/V2__create_job_definitions.sql`
- Create: `system-report-job/src/main/resources/db/migration/V3__create_tasks.sql`
- Create: `system-report-job/src/main/resources/db/migration/V4__create_task_execution_history.sql`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/entity/BaseEntity.java`
- Create: `system-report-job/src/test/resources/application-test.yml`
- Modify: `system-report-job/pom.xml` (dependency fixes — see Step 7)
- Modify: `system-report-job/src/main/resources/application.yml` (Step 10)
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/persistence/FlywayMigrationTest.java`

**Interfaces:**
- Produces: 4 migrated tables (`QRTZ_*`, `job_definitions`, `tasks`, `task_execution_history`) and `BaseEntity` (`id: UUID`, `createdAt/updatedAt: Instant`, `isDeleted: boolean`) — every JPA entity in Tasks 10-12 extends `BaseEntity`. `src/test/resources/application-test.yml` is the shared Testcontainers-driven datasource config every persistence/E2E test in this plan uses (`@ActiveProfiles("test")` + `@Testcontainers`). The `pom.xml` fixes (Step 7) apply to the whole project — no later task needs to touch `testcontainers.version` or the Flyway starter again.

- [ ] **Step 1: Create `V1__create_quartz_tables.sql`** (standard Quartz JDBC JobStore schema, `QRTZ_` prefix — matches `application.yml`'s `tablePrefix` set in Task 24; diff against the exact Quartz version's official `tables_postgres.sql` before running in production)

```sql
CREATE TABLE QRTZ_JOB_DETAILS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    JOB_NAME VARCHAR(200) NOT NULL,
    JOB_GROUP VARCHAR(200) NOT NULL,
    DESCRIPTION VARCHAR(250) NULL,
    JOB_CLASS_NAME VARCHAR(250) NOT NULL,
    IS_DURABLE BOOL NOT NULL,
    IS_NONCONCURRENT BOOL NOT NULL,
    IS_UPDATE_DATA BOOL NOT NULL,
    REQUESTS_RECOVERY BOOL NOT NULL,
    JOB_DATA BYTEA NULL,
    PRIMARY KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE QRTZ_TRIGGERS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    JOB_NAME VARCHAR(200) NOT NULL,
    JOB_GROUP VARCHAR(200) NOT NULL,
    DESCRIPTION VARCHAR(250) NULL,
    NEXT_FIRE_TIME BIGINT NULL,
    PREV_FIRE_TIME BIGINT NULL,
    PRIORITY INTEGER NULL,
    TRIGGER_STATE VARCHAR(16) NOT NULL,
    TRIGGER_TYPE VARCHAR(8) NOT NULL,
    START_TIME BIGINT NOT NULL,
    END_TIME BIGINT NULL,
    CALENDAR_NAME VARCHAR(200) NULL,
    MISFIRE_INSTR SMALLINT NULL,
    JOB_DATA BYTEA NULL,
    PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME, JOB_NAME, JOB_GROUP) REFERENCES QRTZ_JOB_DETAILS (SCHED_NAME, JOB_NAME, JOB_GROUP)
);

CREATE TABLE QRTZ_SIMPLE_TRIGGERS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    REPEAT_COUNT BIGINT NOT NULL,
    REPEAT_INTERVAL BIGINT NOT NULL,
    TIMES_TRIGGERED BIGINT NOT NULL,
    PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP) REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_CRON_TRIGGERS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    CRON_EXPRESSION VARCHAR(120) NOT NULL,
    TIME_ZONE_ID VARCHAR(80),
    PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP) REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_SIMPROP_TRIGGERS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    STR_PROP_1 VARCHAR(512) NULL,
    STR_PROP_2 VARCHAR(512) NULL,
    STR_PROP_3 VARCHAR(512) NULL,
    INT_PROP_1 INT NULL,
    INT_PROP_2 INT NULL,
    LONG_PROP_1 BIGINT NULL,
    LONG_PROP_2 BIGINT NULL,
    DEC_PROP_1 NUMERIC(13, 4) NULL,
    DEC_PROP_2 NUMERIC(13, 4) NULL,
    BOOL_PROP_1 BOOL NULL,
    BOOL_PROP_2 BOOL NULL,
    PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP) REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_BLOB_TRIGGERS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    BLOB_DATA BYTEA NULL,
    PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),
    FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP) REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_CALENDARS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    CALENDAR_NAME VARCHAR(200) NOT NULL,
    CALENDAR BYTEA NOT NULL,
    PRIMARY KEY (SCHED_NAME, CALENDAR_NAME)
);

CREATE TABLE QRTZ_PAUSED_TRIGGER_GRPS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    PRIMARY KEY (SCHED_NAME, TRIGGER_GROUP)
);

CREATE TABLE QRTZ_FIRED_TRIGGERS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    ENTRY_ID VARCHAR(95) NOT NULL,
    TRIGGER_NAME VARCHAR(200) NOT NULL,
    TRIGGER_GROUP VARCHAR(200) NOT NULL,
    INSTANCE_NAME VARCHAR(200) NOT NULL,
    FIRED_TIME BIGINT NOT NULL,
    SCHED_TIME BIGINT NOT NULL,
    PRIORITY INTEGER NOT NULL,
    STATE VARCHAR(16) NOT NULL,
    JOB_NAME VARCHAR(200) NULL,
    JOB_GROUP VARCHAR(200) NULL,
    IS_NONCONCURRENT BOOL NULL,
    REQUESTS_RECOVERY BOOL NULL,
    PRIMARY KEY (SCHED_NAME, ENTRY_ID)
);

CREATE TABLE QRTZ_SCHEDULER_STATE (
    SCHED_NAME VARCHAR(120) NOT NULL,
    INSTANCE_NAME VARCHAR(200) NOT NULL,
    LAST_CHECKIN_TIME BIGINT NOT NULL,
    CHECKIN_INTERVAL BIGINT NOT NULL,
    PRIMARY KEY (SCHED_NAME, INSTANCE_NAME)
);

CREATE TABLE QRTZ_LOCKS (
    SCHED_NAME VARCHAR(120) NOT NULL,
    LOCK_NAME VARCHAR(40) NOT NULL,
    PRIMARY KEY (SCHED_NAME, LOCK_NAME)
);

CREATE INDEX IDX_QRTZ_J_REQ_RECOVERY ON QRTZ_JOB_DETAILS (SCHED_NAME, REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_J_GRP ON QRTZ_JOB_DETAILS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_J ON QRTZ_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_JG ON QRTZ_TRIGGERS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_T_C ON QRTZ_TRIGGERS (SCHED_NAME, CALENDAR_NAME);
CREATE INDEX IDX_QRTZ_T_G ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_T_STATE ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_STATE ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_N_G_STATE ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NEXT_FIRE_TIME ON QRTZ_TRIGGERS (SCHED_NAME, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_MISFIRE ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_T_NFT_ST_MISFIRE_GRP ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_GROUP, TRIGGER_STATE);
CREATE INDEX IDX_QRTZ_FT_TRIG_INST_NAME ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME);
CREATE INDEX IDX_QRTZ_FT_INST_JOB_REQ_RCVRY ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME, REQUESTS_RECOVERY);
CREATE INDEX IDX_QRTZ_FT_J_G ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_JG ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_GROUP);
CREATE INDEX IDX_QRTZ_FT_T_G ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP);
CREATE INDEX IDX_QRTZ_FT_TG ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_GROUP);
```

- [ ] **Step 2: Create `V2__create_job_definitions.sql`**

```sql
CREATE TABLE job_definitions (
    id UUID PRIMARY KEY,
    job_type VARCHAR(100) NOT NULL,
    expression TEXT,
    description VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE
);
```

- [ ] **Step 3: Create `V3__create_tasks.sql`** (`task_group` instead of the reserved word `group` to avoid quoting)

```sql
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    task_group VARCHAR(100) NOT NULL,
    job_definition_id UUID NOT NULL REFERENCES job_definitions (id),
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
```

- [ ] **Step 4: Create `V4__create_task_execution_history.sql`** (`task_name` denormalized so history search never needs a join)

```sql
CREATE TABLE task_execution_history (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES tasks (id),
    task_name VARCHAR(255) NOT NULL,
    start_time TIMESTAMP WITH TIME ZONE,
    end_time TIMESTAMP WITH TIME ZONE,
    exception_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_execution_history_task_name ON task_execution_history (task_name);
```

- [ ] **Step 5: Create `BaseEntity.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    private UUID id;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
```

- [ ] **Step 6: Create the shared test datasource config `src/test/resources/application-test.yml`** (Testcontainers fills the actual JDBC URL via `@DynamicPropertySource` in each test — this file only carries the non-DB defaults every persistence/E2E test shares)

```yaml
spring:
  application:
    name: system-report-job-test
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  quartz:
    job-store-type: memory
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 7: Fix two dependency gaps in `pom.xml` found while building this task** — (a) bump `testcontainers.version` from `1.20.4` to `1.21.4`: 1.20.4's bundled docker-java cannot negotiate with OrbStack's Docker Engine (`MinAPIVersion 1.40`) and every Testcontainers-backed test in this plan fails at container startup with "client version 1.32 is too old"; (b) add `spring-boot-starter-flyway` — Spring Boot 4.1 moved Flyway autoconfiguration into this dedicated starter, so `flyway-core`/`flyway-database-postgresql` alone sit unused on the classpath: no `Flyway` bean is created, no error is logged, and migrations silently never run.

In `system-report-job/pom.xml`:

```xml
<testcontainers.version>1.21.4</testcontainers.version>
```

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
```

- [ ] **Step 8: Write the failing migration test** — `@MockitoBean` the 5 usecase out-ports so the full `@SpringBootTest` context can boot even though no adapter for them exists until Tasks 10-12/15/17 (see Global Constraints)

```java
package com.corebanking.systemreportjob.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.SchedulerGatewayPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // No adapter implements these yet (Tasks 10-12/15/17) — mocked so the full context can boot.
    @MockitoBean
    TaskRepositoryPort taskRepositoryPort;

    @MockitoBean
    JobDefinitionRepositoryPort jobDefinitionRepositoryPort;

    @MockitoBean
    TaskExecutionHistoryRepositoryPort taskExecutionHistoryRepositoryPort;

    @MockitoBean
    SchedulerGatewayPort schedulerGatewayPort;

    @MockitoBean
    JobActionExecutorPort jobActionExecutorPort;

    @Autowired
    DataSource dataSource;

    @Test
    void migratesAllExpectedTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(tableExists(metaData, "tasks")).isTrue();
            assertThat(tableExists(metaData, "job_definitions")).isTrue();
            assertThat(tableExists(metaData, "task_execution_history")).isTrue();
            assertThat(tableExists(metaData, "qrtz_job_details")).isTrue();
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws Exception {
        try (ResultSet rs = metaData.getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }
}
```

- [ ] **Step 9: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=FlywayMigrationTest`
Expected: fails to start Spring context — no `application.yml` datasource/flyway wiring yet (that lands fully in Task 24, but Testcontainers' `@DynamicPropertySource` supplies the connection details here already, so the failure at this point is Flyway not finding the migration files' expected default location, or `spring.datasource.driver-class-name` missing). Confirm the failure message mentions Flyway or datasource, not an assertion failure — that confirms the test harness itself is correctly wired to what step 10-11 build.

- [ ] **Step 10: Add the minimum root `application.yml` datasource+flyway block needed for this test** (full production block, including Quartz clustering and actuator restrictions, is completed in Task 24 — this step only unblocks Flyway so persistence tests can run from here on)

Edit `system-report-job/src/main/resources/application.yml`, replacing its content with:

```yaml
spring:
  application:
    name: system-report-job
  datasource:
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
```

- [ ] **Step 11: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=FlywayMigrationTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0` (Testcontainers pulls `postgres:16-alpine` on first run — allow extra time). Startup logs should show `o.f.core.internal.command.DbMigrate` applying versions 1-4.

- [ ] **Step 12: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add Flyway migrations (Quartz + domain tables) and BaseEntity

Also bumps testcontainers.version to 1.21.4 (OrbStack Docker API compat) and
adds spring-boot-starter-flyway (Spring Boot 4.1 split Flyway autoconfig into
its own starter — without it flyway-core sits unused and migrations never run)."
```

### Task 10: `JobDefinition` persistence (entity + repository + adapter)

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/entity/JobDefinitionEntity.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/repository/JobDefinitionJpaRepository.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/adapter/JobDefinitionRepositoryAdapter.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/persistence/adapter/JobDefinitionRepositoryAdapterTest.java`

**Interfaces:**
- Consumes: `JobDefinition` (Task 2), `JobDefinitionRepositoryPort` (Task 4), `BaseEntity` + migration `job_definitions` (Task 9).
- Produces: `JobDefinitionRepositoryAdapter implements JobDefinitionRepositoryPort` — Spring auto-wires it wherever `JobDefinitionRepositoryPort` is injected (Tasks 6, 8 services already written against the port).

- [ ] **Step 1: Create `JobDefinitionEntity.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "job_definitions")
@SQLDelete(sql = "UPDATE job_definitions SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class JobDefinitionEntity extends BaseEntity {
    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "expression")
    private String expression;

    @Column(name = "description")
    private String description;
}
```

- [ ] **Step 2: Create `JobDefinitionJpaRepository.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.repository;

import com.corebanking.systemreportjob.infrastructure.persistence.entity.JobDefinitionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDefinitionJpaRepository extends JpaRepository<JobDefinitionEntity, UUID> {}
```

- [ ] **Step 3: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(JobDefinitionRepositoryAdapter.class)
class JobDefinitionRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JobDefinitionRepositoryAdapter adapter;

    @Test
    void savesAndReloadsAJobDefinition() {
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "HTTP_CALL", "{\"url\":\"http://x\"}", "desc");

        JobDefinition saved = adapter.save(definition);

        assertThat(adapter.findById(saved.id())).contains(saved);
    }

    @Test
    void deletedJobDefinitionIsNoLongerFound() {
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "ECHO", "{}", null);
        JobDefinition saved = adapter.save(definition);

        adapter.delete(saved.id());

        assertThat(adapter.findById(saved.id())).isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobDefinitionRepositoryAdapterTest`
Expected: compile error — `JobDefinitionRepositoryAdapter` does not exist.

- [ ] **Step 5: Implement `JobDefinitionRepositoryAdapter.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.adapter;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.infrastructure.persistence.entity.JobDefinitionEntity;
import com.corebanking.systemreportjob.infrastructure.persistence.repository.JobDefinitionJpaRepository;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JobDefinitionRepositoryAdapter implements JobDefinitionRepositoryPort {

    private final JobDefinitionJpaRepository jpaRepository;

    public JobDefinitionRepositoryAdapter(JobDefinitionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public JobDefinition save(JobDefinition definition) {
        JobDefinitionEntity entity = toEntity(definition);
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<JobDefinition> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    private JobDefinitionEntity toEntity(JobDefinition definition) {
        JobDefinitionEntity entity = new JobDefinitionEntity();
        entity.setId(definition.id());
        entity.setJobType(definition.jobType());
        entity.setExpression(definition.expression());
        entity.setDescription(definition.description());
        return entity;
    }

    private JobDefinition toDomain(JobDefinitionEntity entity) {
        return new JobDefinition(entity.getId(), entity.getJobType(), entity.getExpression(), entity.getDescription());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobDefinitionRepositoryAdapterTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add JobDefinition JPA persistence adapter"
```

### Task 11: `Task` persistence (entity + repository + adapter)

Depends on `JobDefinitionEntity` (Task 10) for the `job_definition_id` FK column, and maps the `TriggerDefinition` sealed interface to/from flat columns via `switch` pattern matching.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/entity/TaskEntity.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/repository/TaskJpaRepository.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/adapter/TaskRepositoryAdapter.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/persistence/adapter/TaskRepositoryAdapterTest.java`

**Interfaces:**
- Consumes: `ScheduledTask`, `TriggerDefinition`, `PageResult<T>` (Task 2), `TaskRepositoryPort` (Task 4), `BaseEntity` + migration `tasks` (Task 9).
- Produces: `TaskRepositoryAdapter implements TaskRepositoryPort` — consumed wherever `TaskRepositoryPort` is injected (Tasks 5, 8).

- [ ] **Step 1: Create `TaskEntity.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tasks")
@SQLDelete(sql = "UPDATE tasks SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class TaskEntity extends BaseEntity {
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "task_group", nullable = false)
    private String taskGroup;

    @Column(name = "job_definition_id", nullable = false)
    private UUID jobDefinitionId;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "cron_expression")
    private String cronExpression;

    @Column(name = "interval_in_seconds")
    private Integer intervalInSeconds;

    @Column(name = "repeat_count")
    private Integer repeatCount;

    @Column(name = "interval_in_days")
    private Integer intervalInDays;

    @Column(name = "interval_in_minutes")
    private Integer intervalInMinutes;

    @Column(name = "starting_daily_at")
    private LocalTime startingDailyAt;

    @Column(name = "ending_daily_at")
    private LocalTime endingDailyAt;

    @Column(name = "timezone_id")
    private String timezoneId;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "description")
    private String description;
}
```

- [ ] **Step 2: Create `TaskJpaRepository.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.repository;

import com.corebanking.systemreportjob.infrastructure.persistence.entity.TaskEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskJpaRepository extends JpaRepository<TaskEntity, UUID> {
    Page<TaskEntity> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
```

- [ ] **Step 3: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(TaskRepositoryAdapter.class)
class TaskRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TaskRepositoryAdapter adapter;

    private ScheduledTask sample(TriggerDefinition trigger) {
        return new ScheduledTask(
                UUID.randomUUID(), "daily-report", "reports", UUID.randomUUID(), trigger, "UTC", 5, "desc");
    }

    @Test
    void roundTripsACronTriggerTask() {
        ScheduledTask saved = adapter.save(sample(new TriggerDefinition.Cron("0 0 1 * * ?")));

        assertThat(adapter.findById(saved.id())).contains(saved);
    }

    @Test
    void roundTripsADailyTimeIntervalTriggerTask() {
        var trigger = new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);

        ScheduledTask saved = adapter.save(sample(trigger));

        assertThat(adapter.findById(saved.id())).contains(saved);
    }

    @Test
    void searchFiltersByNameAndPaginates() {
        adapter.save(sample(new TriggerDefinition.Simple(60, 0)));

        PageResult<ScheduledTask> result = adapter.search("daily", PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void deletedTaskIsNoLongerFound() {
        ScheduledTask saved = adapter.save(sample(new TriggerDefinition.Simple(60, 0)));

        adapter.delete(saved.id());

        assertThat(adapter.findById(saved.id())).isEmpty();
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskRepositoryAdapterTest`
Expected: compile error — `TaskRepositoryAdapter` does not exist.

- [ ] **Step 5: Implement `TaskRepositoryAdapter.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.adapter;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.persistence.entity.TaskEntity;
import com.corebanking.systemreportjob.infrastructure.persistence.repository.TaskJpaRepository;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class TaskRepositoryAdapter implements TaskRepositoryPort {

    private final TaskJpaRepository jpaRepository;

    public TaskRepositoryAdapter(TaskJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public ScheduledTask save(ScheduledTask task) {
        return toDomain(jpaRepository.save(toEntity(task)));
    }

    @Override
    public Optional<ScheduledTask> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<ScheduledTask> findAll() {
        return jpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public PageResult<ScheduledTask> search(String keyword, Pageable pageable) {
        Page<TaskEntity> page = (keyword == null || keyword.isBlank())
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findByNameContainingIgnoreCase(keyword, pageable);
        return new PageResult<>(
                page.getContent().stream().map(this::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @Override
    public void delete(UUID id) {
        jpaRepository.deleteById(id);
    }

    private TaskEntity toEntity(ScheduledTask task) {
        TaskEntity entity = new TaskEntity();
        entity.setId(task.id());
        entity.setName(task.name());
        entity.setTaskGroup(task.group());
        entity.setJobDefinitionId(task.jobDefinitionId());
        entity.setTimezoneId(task.timezoneId());
        entity.setPriority(task.priority());
        entity.setDescription(task.description());
        switch (task.trigger()) {
            case TriggerDefinition.Cron c -> {
                entity.setTriggerType("CRON");
                entity.setCronExpression(c.cronExpression());
            }
            case TriggerDefinition.Simple s -> {
                entity.setTriggerType("SIMPLE");
                entity.setIntervalInSeconds(s.intervalInSeconds());
                entity.setRepeatCount(s.repeatCount());
            }
            case TriggerDefinition.CalendarInterval ci -> {
                entity.setTriggerType("CALENDAR_INTERVAL");
                entity.setIntervalInDays(ci.intervalInDays());
            }
            case TriggerDefinition.DailyTimeInterval d -> {
                entity.setTriggerType("DAILY_TIME_INTERVAL");
                entity.setStartingDailyAt(d.startingDailyAt());
                entity.setEndingDailyAt(d.endingDailyAt());
                entity.setIntervalInMinutes(d.intervalInMinutes());
            }
        }
        return entity;
    }

    private ScheduledTask toDomain(TaskEntity entity) {
        TriggerDefinition trigger =
                switch (entity.getTriggerType()) {
                    case "CRON" -> new TriggerDefinition.Cron(entity.getCronExpression());
                    case "SIMPLE" -> new TriggerDefinition.Simple(entity.getIntervalInSeconds(), entity.getRepeatCount());
                    case "CALENDAR_INTERVAL" -> new TriggerDefinition.CalendarInterval(entity.getIntervalInDays());
                    case "DAILY_TIME_INTERVAL" -> new TriggerDefinition.DailyTimeInterval(
                            entity.getStartingDailyAt(), entity.getEndingDailyAt(), entity.getIntervalInMinutes());
                    default -> throw new IllegalStateException("Unknown trigger_type: " + entity.getTriggerType());
                };
        return new ScheduledTask(
                entity.getId(),
                entity.getName(),
                entity.getTaskGroup(),
                entity.getJobDefinitionId(),
                trigger,
                entity.getTimezoneId(),
                entity.getPriority(),
                entity.getDescription());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskRepositoryAdapterTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add Task JPA persistence adapter with TriggerDefinition mapping"
```

### Task 12: `TaskExecutionHistory` persistence (entity + repository + adapter)

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/entity/TaskExecutionHistoryEntity.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/repository/TaskExecutionHistoryJpaRepository.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/persistence/adapter/TaskExecutionHistoryRepositoryAdapter.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/persistence/adapter/TaskExecutionHistoryRepositoryAdapterTest.java`

**Interfaces:**
- Consumes: `TaskExecutionRecord`, `PageResult<T>` (Task 2), `TaskExecutionHistoryRepositoryPort` (Task 4), `BaseEntity` + migration `task_execution_history` (Task 9).
- Produces: `TaskExecutionHistoryRepositoryAdapter implements TaskExecutionHistoryRepositoryPort` — consumed by Task 7 (`TaskHistoryQueryService`) and Task 16 (`QuartzJobListener`).

This entity has no soft-delete (history rows are never deleted), so no `@SQLDelete`/`@SQLRestriction` and its own `id`/`createdAt` handling instead of `BaseEntity` (which carries `isDeleted`/`updatedAt` that don't apply here).

- [ ] **Step 1: Create `TaskExecutionHistoryEntity.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_execution_history")
public class TaskExecutionHistoryEntity {
    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Column(name = "start_time")
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "exception_message")
    private String exceptionMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
```

- [ ] **Step 2: Create `TaskExecutionHistoryJpaRepository.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.repository;

import com.corebanking.systemreportjob.infrastructure.persistence.entity.TaskExecutionHistoryEntity;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskExecutionHistoryJpaRepository extends JpaRepository<TaskExecutionHistoryEntity, UUID> {
    Page<TaskExecutionHistoryEntity> findByTaskNameContainingIgnoreCase(String taskName, Pageable pageable);
}
```

- [ ] **Step 3: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(TaskExecutionHistoryRepositoryAdapter.class)
class TaskExecutionHistoryRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TaskExecutionHistoryRepositoryAdapter adapter;

    @Test
    void savesAndSearchesByTaskName() {
        TaskExecutionRecord record = new TaskExecutionRecord(
                UUID.randomUUID(), UUID.randomUUID(), "daily-report", Instant.now(), Instant.now(), null);

        adapter.save(record);
        PageResult<TaskExecutionRecord> result = adapter.search("daily", PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).taskName()).isEqualTo("daily-report");
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskExecutionHistoryRepositoryAdapterTest`
Expected: compile error — `TaskExecutionHistoryRepositoryAdapter` does not exist.

- [ ] **Step 5: Implement `TaskExecutionHistoryRepositoryAdapter.java`**

```java
package com.corebanking.systemreportjob.infrastructure.persistence.adapter;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.infrastructure.persistence.entity.TaskExecutionHistoryEntity;
import com.corebanking.systemreportjob.infrastructure.persistence.repository.TaskExecutionHistoryJpaRepository;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
public class TaskExecutionHistoryRepositoryAdapter implements TaskExecutionHistoryRepositoryPort {

    private final TaskExecutionHistoryJpaRepository jpaRepository;

    public TaskExecutionHistoryRepositoryAdapter(TaskExecutionHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public TaskExecutionRecord save(TaskExecutionRecord record) {
        TaskExecutionHistoryEntity entity = new TaskExecutionHistoryEntity();
        entity.setId(record.id() != null ? record.id() : UUID.randomUUID());
        entity.setTaskId(record.taskId());
        entity.setTaskName(record.taskName());
        entity.setStartTime(record.startTime());
        entity.setEndTime(record.endTime());
        entity.setExceptionMessage(record.exceptionMessage());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable) {
        Page<TaskExecutionHistoryEntity> page = (taskName == null || taskName.isBlank())
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findByTaskNameContainingIgnoreCase(taskName, pageable);
        return new PageResult<>(
                page.getContent().stream().map(this::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    private TaskExecutionRecord toDomain(TaskExecutionHistoryEntity entity) {
        return new TaskExecutionRecord(
                entity.getId(),
                entity.getTaskId(),
                entity.getTaskName(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getExceptionMessage());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskExecutionHistoryRepositoryAdapterTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add TaskExecutionHistory JPA persistence adapter"
```

---

## Phase 5 — Infrastructure: scheduler

### Task 13: `QuartzIdentifiers` + `QuartzTriggerFactory`

All scheduled jobs share one fixed Quartz job group (`"system-report-job"`); uniqueness comes from `taskId` (a UUID) as the job/trigger name, not from `task.group()` — `task.group()` stays a business-level tag on `ScheduledTask` used only for search, never for Quartz identity. This keeps `SchedulerGatewayPort` methods working with just a `UUID taskId` (as already defined in Task 4) — no version of this file must reintroduce a `group` parameter into these identifiers.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/scheduler/QuartzIdentifiers.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/scheduler/QuartzTriggerFactory.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/scheduler/QuartzTriggerFactoryTest.java`

**Interfaces:**
- Consumes: `ScheduledTask`, `TriggerDefinition` (Task 2).
- Produces: `QuartzIdentifiers.jobKey(UUID taskId): JobKey` / `.triggerKey(UUID taskId): TriggerKey` (used by Task 15's `QuartzSchedulerGatewayAdapter`), `QuartzTriggerFactory.build(ScheduledTask): org.quartz.Trigger` (used by Task 15).

- [ ] **Step 1: Create `QuartzIdentifiers.java`**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler;

import java.util.UUID;
import org.quartz.JobKey;
import org.quartz.TriggerKey;

public final class QuartzIdentifiers {
    public static final String JOB_GROUP = "system-report-job";

    private QuartzIdentifiers() {}

    public static JobKey jobKey(UUID taskId) {
        return JobKey.jobKey(taskId.toString(), JOB_GROUP);
    }

    public static TriggerKey triggerKey(UUID taskId) {
        return TriggerKey.triggerKey(taskId + "-trigger", JOB_GROUP);
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronTrigger;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;

class QuartzTriggerFactoryTest {

    private final QuartzTriggerFactory factory = new QuartzTriggerFactory();

    private ScheduledTask taskWith(TriggerDefinition trigger) {
        return new ScheduledTask(UUID.randomUUID(), "t", "g", UUID.randomUUID(), trigger, "UTC", 5, null);
    }

    @Test
    void buildsCronTrigger() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.Cron("0 0 1 * * ?")));

        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getCronExpression()).isEqualTo("0 0 1 * * ?");
    }

    @Test
    void buildsSimpleTrigger() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.Simple(60, 3)));

        assertThat(trigger).isInstanceOf(SimpleTrigger.class);
        SimpleTrigger simple = (SimpleTrigger) trigger;
        assertThat(simple.getRepeatInterval()).isEqualTo(60_000L);
        assertThat(simple.getRepeatCount()).isEqualTo(3);
    }

    @Test
    void buildsCalendarIntervalTrigger() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.CalendarInterval(2)));

        assertThat(trigger).isInstanceOf(CalendarIntervalTrigger.class);
        assertThat(((CalendarIntervalTrigger) trigger).getRepeatInterval()).isEqualTo(2);
    }

    @Test
    void buildsDailyTimeIntervalTrigger() {
        Trigger trigger = factory.build(
                taskWith(new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0), 15)));

        assertThat(trigger).isInstanceOf(DailyTimeIntervalTrigger.class);
        assertThat(((DailyTimeIntervalTrigger) trigger).getRepeatInterval()).isEqualTo(15);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzTriggerFactoryTest`
Expected: compile error — `QuartzTriggerFactory` does not exist.

- [ ] **Step 4: Implement `QuartzTriggerFactory.java`**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import java.util.Date;
import java.util.TimeZone;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CronScheduleBuilder;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TimeOfDay;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Component;

@Component
public class QuartzTriggerFactory {

    public Trigger build(ScheduledTask task) {
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(QuartzIdentifiers.triggerKey(task.id()))
                .forJob(QuartzIdentifiers.jobKey(task.id()))
                .startAt(new Date());
        if (task.priority() != null) {
            builder = builder.withPriority(task.priority());
        }

        return switch (task.trigger()) {
            case TriggerDefinition.Cron c -> builder.withSchedule(CronScheduleBuilder.cronSchedule(c.cronExpression())
                            .inTimeZone(TimeZone.getTimeZone(task.timezoneId()))
                            .withMisfireHandlingInstructionFireAndProceed())
                    .build();
            case TriggerDefinition.Simple s -> builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInSeconds(s.intervalInSeconds())
                            .withRepeatCount(s.repeatCount()))
                    .build();
            case TriggerDefinition.CalendarInterval ci -> builder.withSchedule(
                            CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                                    .withIntervalInDays(ci.intervalInDays()))
                    .build();
            case TriggerDefinition.DailyTimeInterval d -> builder.withSchedule(
                            DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                                    .startingDailyAt(new TimeOfDay(
                                            d.startingDailyAt().getHour(), d.startingDailyAt().getMinute()))
                                    .endingDailyAt(new TimeOfDay(
                                            d.endingDailyAt().getHour(), d.endingDailyAt().getMinute()))
                                    .withIntervalInMinutes(d.intervalInMinutes()))
                    .build();
        };
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzTriggerFactoryTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add QuartzTriggerFactory (TriggerDefinition -> org.quartz.Trigger)"
```

### Task 14: `QuartzClusterConfig`

Registers the Spring-aware job factory (so `ScheduledJobExecutor`, Task 15, gets `@Autowired` fields populated by Quartz-instantiated `Job` objects) and wires whatever `JobListener`/`TriggerListener` beans exist in the context — written as `List<JobListener>`/`List<TriggerListener>` so it compiles now and auto-picks up Task 16's listener beans later with no further changes needed here.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/config/QuartzClusterConfig.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/config/QuartzClusterConfigTest.java`

**Interfaces:**
- Produces: `QuartzClusterConfig.AutowiringSpringBeanJobFactory` bean, and a `SchedulerFactoryBeanCustomizer` bean that Spring Boot's Quartz autoconfiguration applies to the auto-configured `Scheduler`/`SchedulerFactoryBean` — no other task references this class directly, but every test in this plan that boots a full Spring context relies on the `Scheduler` bean it configures.

- [ ] **Step 1: Write the failing test** — `@MockitoBean` the 5 usecase out-ports for the same reason as Task 9's `FlywayMigrationTest` (see Global Constraints): no adapter implements them yet, and this test boots the full context.

```java
package com.corebanking.systemreportjob.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.SchedulerGatewayPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class QuartzClusterConfigTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    TaskRepositoryPort taskRepositoryPort;

    @MockitoBean
    JobDefinitionRepositoryPort jobDefinitionRepositoryPort;

    @MockitoBean
    TaskExecutionHistoryRepositoryPort taskExecutionHistoryRepositoryPort;

    @MockitoBean
    SchedulerGatewayPort schedulerGatewayPort;

    @MockitoBean
    JobActionExecutorPort jobActionExecutorPort;

    @Autowired
    Scheduler scheduler;

    @Autowired
    QuartzClusterConfig.AutowiringSpringBeanJobFactory jobFactory;

    @Test
    void schedulerBeanIsConfiguredAndStarted() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(jobFactory).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzClusterConfigTest`
Expected: compile error — `QuartzClusterConfig` does not exist.

- [ ] **Step 3: Implement `QuartzClusterConfig.java`**

```java
package com.corebanking.systemreportjob.infrastructure.config;

import java.util.List;
import org.quartz.JobListener;
import org.quartz.TriggerListener;
import org.quartz.spi.TriggerFiredBundle;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

@Configuration
public class QuartzClusterConfig {

    public static class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory
            implements ApplicationContextAware {
        private transient AutowireCapableBeanFactory beanFactory;

        @Override
        public void setApplicationContext(ApplicationContext context) {
            this.beanFactory = context.getAutowireCapableBeanFactory();
        }

        @Override
        protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
            Object job = super.createJobInstance(bundle);
            beanFactory.autowireBean(job);
            return job;
        }
    }

    @Bean
    public AutowiringSpringBeanJobFactory springBeanJobFactory() {
        return new AutowiringSpringBeanJobFactory();
    }

    @Bean
    public SchedulerFactoryBeanCustomizer schedulerFactoryBeanCustomizer(
            AutowiringSpringBeanJobFactory jobFactory, List<JobListener> jobListeners, List<TriggerListener> triggerListeners) {
        return factory -> {
            factory.setJobFactory(jobFactory);
            factory.setOverwriteExistingJobs(true);
            factory.setWaitForJobsToCompleteOnShutdown(true);
            factory.setGlobalJobListeners(jobListeners.toArray(new JobListener[0]));
            factory.setGlobalTriggerListeners(triggerListeners.toArray(new TriggerListener[0]));
        };
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzClusterConfigTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add QuartzClusterConfig (Spring-aware job factory + listener wiring)"
```

### Task 15: `ScheduledJobExecutor` + `QuartzSchedulerGatewayAdapter`

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/scheduler/ScheduledJobExecutor.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/scheduler/QuartzSchedulerGatewayAdapter.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/scheduler/QuartzSchedulerGatewayAdapterTest.java`

**Interfaces:**
- Consumes: `ExecuteScheduledJobUseCase` (Task 4/8), `SchedulerGatewayPort` (Task 4), `ScheduledTask`/`TriggerState` (Task 2), `QuartzIdentifiers`/`QuartzTriggerFactory` (Task 13).
- Produces: `ScheduledJobExecutor` (the only `org.quartz.Job` implementation in the codebase — Quartz instantiates it by class + `AutowiringSpringBeanJobFactory` from Task 14 populates its `executeScheduledJobUseCase` field), `QuartzSchedulerGatewayAdapter implements SchedulerGatewayPort`. The `JobDataMap` keys `"taskId"` and `"taskName"` set here are read by `ScheduledJobExecutor` and by `QuartzJobListener` (Task 16) — do not rename them independently in either file.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.domain.model.TriggerState;
import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class QuartzSchedulerGatewayAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    QuartzSchedulerGatewayAdapter adapter;

    @MockitoBean
    ExecuteScheduledJobUseCase executeScheduledJobUseCase;

    private ScheduledTask sample() {
        return new ScheduledTask(
                UUID.randomUUID(), "fast-task", "test", UUID.randomUUID(),
                new TriggerDefinition.Simple(1, 0), "UTC", 1, null);
    }

    @Test
    void scheduledTaskFiresAndInvokesUseCase() {
        ScheduledTask task = sample();

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(executeScheduledJobUseCase).execute(task.id()));
    }

    @Test
    void pauseThenResumeChangesTriggerState() {
        ScheduledTask task = sample();
        adapter.scheduleTask(task);

        adapter.pauseTask(task.id());
        await().atMost(Duration.ofSeconds(5)).until(() -> adapter.getTriggerState(task.id()) == TriggerState.PAUSED);

        adapter.resumeTask(task.id());
        await().atMost(Duration.ofSeconds(5))
                .until(() -> adapter.getTriggerState(task.id()) != TriggerState.PAUSED);
    }

    @Test
    void unscheduleRemovesTheJob() {
        ScheduledTask task = sample();
        adapter.scheduleTask(task);

        adapter.unscheduleTask(task.id());

        assertThat(adapter.getTriggerState(task.id())).isEqualTo(TriggerState.NONE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzSchedulerGatewayAdapterTest`
Expected: compile error — neither class exists.

- [ ] **Step 3: Implement `ScheduledJobExecutor.java`**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler;

import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import java.util.UUID;
import lombok.Setter;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

public class ScheduledJobExecutor extends QuartzJobBean {

    @Setter(onMethod_ = @Autowired)
    private ExecuteScheduledJobUseCase executeScheduledJobUseCase;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        UUID taskId = UUID.fromString(context.getMergedJobDataMap().getString("taskId"));
        executeScheduledJobUseCase.execute(taskId);
    }
}
```

- [ ] **Step 4: Implement `QuartzSchedulerGatewayAdapter.java`**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerState;
import com.corebanking.systemreportjob.usecase.ports.out.SchedulerGatewayPort;
import java.util.Set;
import java.util.UUID;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.stereotype.Component;

@Component
public class QuartzSchedulerGatewayAdapter implements SchedulerGatewayPort {

    private final Scheduler scheduler;
    private final QuartzTriggerFactory triggerFactory;

    public QuartzSchedulerGatewayAdapter(Scheduler scheduler, QuartzTriggerFactory triggerFactory) {
        this.scheduler = scheduler;
        this.triggerFactory = triggerFactory;
    }

    @Override
    public void scheduleTask(ScheduledTask task) {
        JobDetail jobDetail = JobBuilder.newJob(ScheduledJobExecutor.class)
                .withIdentity(QuartzIdentifiers.jobKey(task.id()))
                .usingJobData("taskId", task.id().toString())
                .usingJobData("taskName", task.name())
                .storeDurably()
                .build();
        Trigger trigger = triggerFactory.build(task);
        try {
            scheduler.scheduleJob(jobDetail, Set.of(trigger), true);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể lên lịch task " + task.id(), e);
        }
    }

    @Override
    public void unscheduleTask(UUID taskId) {
        try {
            scheduler.deleteJob(QuartzIdentifiers.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể huỷ lịch task " + taskId, e);
        }
    }

    @Override
    public void pauseTask(UUID taskId) {
        try {
            scheduler.pauseJob(QuartzIdentifiers.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể tạm dừng task " + taskId, e);
        }
    }

    @Override
    public void resumeTask(UUID taskId) {
        try {
            scheduler.resumeJob(QuartzIdentifiers.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể tiếp tục task " + taskId, e);
        }
    }

    @Override
    public TriggerState getTriggerState(UUID taskId) {
        try {
            JobKey jobKey = QuartzIdentifiers.jobKey(taskId);
            if (!scheduler.checkExists(jobKey)) {
                return TriggerState.NONE;
            }
            for (Trigger trigger : scheduler.getTriggersOfJob(jobKey)) {
                return mapState(scheduler.getTriggerState(trigger.getKey()));
            }
            return TriggerState.NONE;
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể lấy trạng thái task " + taskId, e);
        }
    }

    private TriggerState mapState(Trigger.TriggerState state) {
        return switch (state) {
            case NORMAL -> TriggerState.NORMAL;
            case PAUSED -> TriggerState.PAUSED;
            case COMPLETE -> TriggerState.COMPLETE;
            case ERROR -> TriggerState.ERROR;
            case BLOCKED -> TriggerState.BLOCKED;
            case NONE -> TriggerState.NONE;
        };
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzSchedulerGatewayAdapterTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add ScheduledJobExecutor and QuartzSchedulerGatewayAdapter"
```

### Task 16: `QuartzJobListener` + `QuartzJobTriggerListener`

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/scheduler/listeners/QuartzJobListener.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/scheduler/listeners/QuartzJobTriggerListener.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/scheduler/listeners/QuartzJobListenerTest.java`

**Interfaces:**
- Consumes: `TaskExecutionHistoryRepositoryPort` (Task 4/12), `TaskExecutionRecord` (Task 2), the `"taskId"`/`"taskName"` `JobDataMap` keys set by Task 15.
- Produces: two `@Component` beans that `QuartzClusterConfig` (Task 14) auto-collects via its `List<JobListener>`/`List<TriggerListener>` parameters — no other task calls these directly.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.scheduler.QuartzSchedulerGatewayAdapter;
import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class QuartzJobListenerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    QuartzSchedulerGatewayAdapter adapter;

    @Autowired
    TaskExecutionHistoryRepositoryPort historyRepositoryPort;

    @MockitoBean
    ExecuteScheduledJobUseCase executeScheduledJobUseCase;

    private ScheduledTask sample(String name) {
        return new ScheduledTask(
                UUID.randomUUID(), name, "test", UUID.randomUUID(),
                new TriggerDefinition.Simple(1, 0), "UTC", 1, null);
    }

    @Test
    void recordsSuccessfulExecution() {
        ScheduledTask task = sample("listener-success");
        doNothing().when(executeScheduledJobUseCase).execute(task.id());

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            PageResult<TaskExecutionRecord> result =
                    historyRepositoryPort.search(task.name(), PageRequest.of(0, 10));
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).exceptionMessage()).isNull();
            assertThat(result.content().get(0).startTime()).isNotNull();
            assertThat(result.content().get(0).endTime()).isNotNull();
        });
    }

    @Test
    void recordsFailedExecutionWithExceptionMessage() {
        ScheduledTask task = sample("listener-failure");
        doThrow(new RuntimeException("boom")).when(executeScheduledJobUseCase).execute(task.id());

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            PageResult<TaskExecutionRecord> result =
                    historyRepositoryPort.search(task.name(), PageRequest.of(0, 10));
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).exceptionMessage()).contains("boom");
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzJobListenerTest`
Expected: fails — no history row gets recorded yet (listener doesn't exist), so `result.content()` is empty and the `await()` times out.

- [ ] **Step 3: Implement `QuartzJobListener.java`**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler.listeners;

import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import java.time.Instant;
import java.util.UUID;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.springframework.stereotype.Component;

@Component
public class QuartzJobListener implements JobListener {

    private static final String START_TIME_KEY = "startTime";

    private final TaskExecutionHistoryRepositoryPort historyRepositoryPort;

    public QuartzJobListener(TaskExecutionHistoryRepositoryPort historyRepositoryPort) {
        this.historyRepositoryPort = historyRepositoryPort;
    }

    @Override
    public String getName() {
        return "systemReportJobListener";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        context.put(START_TIME_KEY, Instant.now());
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // no history to record — the job never actually ran
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        Instant startTime = (Instant) context.get(START_TIME_KEY);
        UUID taskId = UUID.fromString(context.getMergedJobDataMap().getString("taskId"));
        String taskName = context.getMergedJobDataMap().getString("taskName");

        historyRepositoryPort.save(new TaskExecutionRecord(
                UUID.randomUUID(),
                taskId,
                taskName,
                startTime,
                Instant.now(),
                jobException == null ? null : jobException.getMessage()));
    }
}
```

- [ ] **Step 4: Implement `QuartzJobTriggerListener.java`**

```java
package com.corebanking.systemreportjob.infrastructure.scheduler.listeners;

import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.TriggerListener;
import org.springframework.stereotype.Component;

@Component
public class QuartzJobTriggerListener implements TriggerListener {

    @Override
    public String getName() {
        return "systemReportJobTriggerListener";
    }

    @Override
    public void triggerFired(Trigger trigger, JobExecutionContext context) {}

    @Override
    public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
        return false;
    }

    @Override
    public void triggerMisfired(Trigger trigger) {}

    @Override
    public void triggerComplete(
            Trigger trigger, JobExecutionContext context, Trigger.CompletedExecutionInstruction instruction) {}
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=QuartzJobListenerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add QuartzJobListener/QuartzJobTriggerListener, wires execution history recording"
```

---

## Phase 6 — Infrastructure: job actions

### Task 17: `JobAction` + `JobActionRegistry`

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/JobAction.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/JobActionRegistry.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/JobActionRegistryTest.java`

**Interfaces:**
- Consumes: `JobDefinition` (Task 2), `JobActionExecutorPort` (Task 4), `BusinessException`/`ErrorCode` (Task 3).
- Produces: `JobAction` interface (`matches(String jobType): boolean`, `execute(JobDefinition): void`) — every job-action bean (Tasks 18, 19) implements it; `JobActionRegistry implements JobActionExecutorPort` — Spring auto-collects all `JobAction` beans.

- [ ] **Step 1: Create `JobAction.java`**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

public interface JobAction {
    boolean matches(String jobType);

    void execute(JobDefinition definition);
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.corebanking.systemreportjob.domain.exception.BusinessException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JobActionRegistryTest {

    @Test
    void dispatchesToMatchingAction() {
        AtomicReference<JobDefinition> executed = new AtomicReference<>();
        JobAction echoAction = new JobAction() {
            @Override
            public boolean matches(String jobType) {
                return "ECHO".equals(jobType);
            }

            @Override
            public void execute(JobDefinition definition) {
                executed.set(definition);
            }
        };
        JobActionRegistry registry = new JobActionRegistry(List.of(echoAction));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "ECHO", "{}", null);

        registry.execute(definition);

        assertThat(executed.get()).isEqualTo(definition);
    }

    @Test
    void throwsWhenNoActionMatches() {
        JobActionRegistry registry = new JobActionRegistry(List.of());
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "UNKNOWN", "{}", null);

        assertThatThrownBy(() -> registry.execute(definition)).isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobActionRegistryTest`
Expected: compile error — `JobActionRegistry` does not exist.

- [ ] **Step 4: Implement `JobActionRegistry.java`**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions;

import com.corebanking.systemreportjob.domain.exception.BusinessException;
import com.corebanking.systemreportjob.domain.exception.ErrorCode;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JobActionRegistry implements JobActionExecutorPort {

    private final List<JobAction> actions;

    public JobActionRegistry(List<JobAction> actions) {
        this.actions = actions;
    }

    @Override
    public void execute(JobDefinition definition) {
        actions.stream()
                .filter(action -> action.matches(definition.jobType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, definition.jobType()))
                .execute(definition);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobActionRegistryTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add JobAction strategy interface and JobActionRegistry"
```

### Task 18: `HttpCallJobAction` + `VirtualThreadConfig`

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/config/VirtualThreadConfig.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/HttpCallJobAction.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/HttpCallJobActionTest.java`

**Interfaces:**
- Consumes: `JobAction` (Task 17), `JobDefinition` (Task 2).
- Produces: `jobActionTaskExecutor` bean (`AsyncTaskExecutor`, virtual-thread-backed), `HttpCallJobAction` (`jobType = "HTTP_CALL"`, `expression` is a JSON object `{"url": "...", "method": "...", "headers": {...}}`) — replaces the legacy `HttpCallFactory`/`HttpCallService`/`SystemCallService`/`UserCallService` chain.

- [ ] **Step 1: Create `VirtualThreadConfig.java`**

```java
package com.corebanking.systemreportjob.infrastructure.config;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

@Configuration
public class VirtualThreadConfig {

    @Bean(name = "jobActionTaskExecutor")
    public AsyncTaskExecutor jobActionTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

- [ ] **Step 2: Write the failing test** (constructs `HttpCallJobAction` directly with a `MockRestServiceServer`-bound `RestClient.Builder` — no Spring context needed)

```java
package com.corebanking.systemreportjob.infrastructure.jobactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpCallJobActionTest {

    private HttpCallJobAction newAction(RestClient.Builder builder) {
        return new HttpCallJobAction(
                builder, new ObjectMapper(), new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
    }

    @Test
    void callsConfiguredHttpEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://example.test/ping"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        JobDefinition definition = new JobDefinition(
                UUID.randomUUID(), "HTTP_CALL", "{\"url\":\"http://example.test/ping\",\"method\":\"POST\"}", null);

        newAction(builder).execute(definition);

        server.verify();
    }

    @Test
    void matchesOnlyHttpCallJobType() {
        HttpCallJobAction action = newAction(RestClient.builder());

        assertThat(action.matches("HTTP_CALL")).isTrue();
        assertThat(action.matches("ECHO")).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=HttpCallJobActionTest`
Expected: compile error — `HttpCallJobAction` does not exist.

- [ ] **Step 4: Implement `HttpCallJobAction.java`** (the blocking HTTP call runs on a virtual thread submitted to `jobActionTaskExecutor`; `execute()` still blocks the calling Quartz worker thread waiting on the result, so exceptions propagate normally into `JobActionRegistry` → `JobExecutionOrchestrator` → `ScheduledJobExecutor` → Quartz's listener chain, same failure path as every other job type)

```java
package com.corebanking.systemreportjob.infrastructure.jobactions;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HttpCallJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(HttpCallJobAction.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor jobActionTaskExecutor;

    public HttpCallJobAction(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
    }

    @Override
    public boolean matches(String jobType) {
        return "HTTP_CALL".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        try {
            jobActionTaskExecutor.submit(() -> callHttp(definition)).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("HTTP_CALL job action thất bại: " + definition.id(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP_CALL job action bị gián đoạn: " + definition.id(), e);
        }
    }

    private Void callHttp(JobDefinition definition) throws Exception {
        HttpCallExpression expression = objectMapper.readValue(definition.expression(), HttpCallExpression.class);
        HttpHeaders headers = new HttpHeaders();
        if (expression.headers() != null) {
            expression.headers().forEach(headers::set);
        }
        String result = restClient
                .method(HttpMethod.valueOf(expression.method()))
                .uri(expression.url())
                .headers(h -> h.addAll(headers))
                .retrieve()
                .body(String.class);
        log.info("HTTP_CALL job {} result: {}", definition.id(), result);
        return null;
    }

    record HttpCallExpression(String url, String method, Map<String, String> headers) {}
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=HttpCallJobActionTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add HttpCallJobAction on virtual threads, replaces legacy HttpCallFactory chain"
```

### Task 19: `EchoInProcessJobAction` (sample in-process job)

Demonstrates the "job runs logic in-process instead of calling HTTP" capability that was explicitly requested during design (spec section 1) — and is what Task 25's end-to-end test schedules.

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/jobactions/sample/EchoInProcessJobAction.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/jobactions/sample/EchoInProcessJobActionTest.java`

**Interfaces:**
- Consumes: `JobAction` (Task 17), `JobDefinition` (Task 2).
- Produces: `EchoInProcessJobAction` (`jobType = "ECHO"`) — consumed by Task 25's end-to-end test via `JobActionRegistry` auto-discovery.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EchoInProcessJobActionTest {

    private final EchoInProcessJobAction action = new EchoInProcessJobAction();

    @Test
    void matchesOnlyEchoJobType() {
        assertThat(action.matches("ECHO")).isTrue();
        assertThat(action.matches("HTTP_CALL")).isFalse();
    }

    @Test
    void executeDoesNotThrow() {
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "ECHO", "{\"msg\":\"hi\"}", null);

        assertThatCode(() -> action.execute(definition)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=EchoInProcessJobActionTest`
Expected: compile error — `EchoInProcessJobAction` does not exist.

- [ ] **Step 3: Implement `EchoInProcessJobAction.java`**

```java
package com.corebanking.systemreportjob.infrastructure.jobactions.sample;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.infrastructure.jobactions.JobAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EchoInProcessJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(EchoInProcessJobAction.class);

    @Override
    public boolean matches(String jobType) {
        return "ECHO".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        log.info("[ECHO] JobDefinition {} expression={}", definition.id(), definition.expression());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=EchoInProcessJobActionTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add EchoInProcessJobAction sample in-process job"
```

---

## Phase 7 — Infrastructure: web

### Task 20: Common web infra — `ApiResponse`, `PageResponse`, `GlobalExceptionHandler`, `@ValidCron`, i18n messages

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/common/ApiResponse.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/common/PageResponse.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/common/GlobalExceptionHandler.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/common/ValidCron.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/common/CronExpressionValidator.java`
- Create: `system-report-job/src/main/resources/messages.properties`
- Create: `system-report-job/src/main/resources/messages_en.properties`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `BusinessException`/`ErrorCode` (Task 3), `PageResult<T>` (Task 2).
- Produces: `ApiResponse<T>` (`.ok(data)`, `.ok()`, `.error(status, message)`), `PageResponse<T>` (`.from(PageResult<S>, Function<S,T>)`), `GlobalExceptionHandler`, `@ValidCron` — every controller in Tasks 21-23 wraps its responses in `ApiResponse`/`PageResponse` and relies on `GlobalExceptionHandler` for error mapping; `CreateTaskRequest` (Task 21) uses `@ValidCron`.

- [ ] **Step 1: Create `ApiResponse.java`**

```java
package com.corebanking.systemreportjob.infrastructure.common;

public record ApiResponse<T>(boolean success, int status, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, 200, null, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, 200, null, null);
    }

    public static <T> ApiResponse<T> error(int status, String message) {
        return new ApiResponse<>(false, status, message, null);
    }
}
```

- [ ] **Step 2: Create `PageResponse.java`**

```java
package com.corebanking.systemreportjob.infrastructure.common;

import com.corebanking.systemreportjob.domain.model.PageResult;
import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> data, int currentPage, int pageSize, long totalElements, int totalPages) {
    public static <S, T> PageResponse<T> from(PageResult<S> pageResult, Function<S, T> mapper) {
        return new PageResponse<>(
                pageResult.content().stream().map(mapper).toList(),
                pageResult.page(),
                pageResult.size(),
                pageResult.totalElements(),
                pageResult.totalPages());
    }
}
```

- [ ] **Step 3: Create `messages.properties`** (default bundle, Vietnamese)

```properties
task.not_found=Không tìm thấy task với id {0}
job_definition.not_found=Không tìm thấy JobDefinition với id {0}
cron.invalid=Biểu thức cron không hợp lệ
validation.error=Không tìm thấy JobAction phù hợp cho jobType {0}
```

- [ ] **Step 4: Create `messages_en.properties`**

```properties
task.not_found=Task not found with id {0}
job_definition.not_found=JobDefinition not found with id {0}
cron.invalid=Invalid cron expression
validation.error=No JobAction found for jobType {0}
```

- [ ] **Step 5: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    GlobalExceptionHandlerTest() {
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messageSource);

    @Test
    void mapsTaskNotFoundTo404WithInterpolatedMessage() {
        UUID taskId = UUID.randomUUID();

        ResponseEntity<ApiResponse<Object>> response =
                handler.handleBusinessException(new TaskNotFoundException(taskId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).contains(taskId.toString());
    }

    @Test
    void mapsJobDefinitionNotFoundTo404() {
        ResponseEntity<ApiResponse<Object>> response =
                handler.handleBusinessException(new JobDefinitionNotFoundException(UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=GlobalExceptionHandlerTest`
Expected: compile error — `GlobalExceptionHandler` does not exist.

- [ ] **Step 7: Create `ValidCron.java`**

```java
package com.corebanking.systemreportjob.infrastructure.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = CronExpressionValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCron {
    String message() default "{cron.invalid}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 8: Create `CronExpressionValidator.java`**

```java
package com.corebanking.systemreportjob.infrastructure.common;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CronExpressionValidator implements ConstraintValidator<ValidCron, String> {

    private final CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            parser.parse(value).validate();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

- [ ] **Step 9: Implement `GlobalExceptionHandler.java`**

```java
package com.corebanking.systemreportjob.infrastructure.common;

import com.corebanking.systemreportjob.domain.exception.BusinessException;
import com.corebanking.systemreportjob.domain.exception.ErrorCode;
import java.util.Objects;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException exception) {
        HttpStatus status = statusFor(exception.getErrorCode());
        String message = resolveMessage(exception.getErrorCode(), exception.getMessageArgs());
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = Objects.requireNonNull(exception.getFieldError()).getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case TASK_NOT_FOUND, JOB_DEFINITION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CRON_INVALID, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        };
    }

    private String resolveMessage(ErrorCode errorCode, Object[] args) {
        try {
            return messageSource.getMessage(errorCode.getMessageKey(), args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return errorCode.name();
        }
    }
}
```

- [ ] **Step 10: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=GlobalExceptionHandlerTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 11: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add common web infra (ApiResponse, PageResponse, GlobalExceptionHandler, ValidCron)"
```

### Task 21: `TaskController` + DTOs

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/request/CreateTaskRequest.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/response/TaskResponse.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/response/JobDefinitionResponse.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/response/TaskDetailResponse.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/controller/TaskController.java`
- Modify: `system-report-job/pom.xml` (`spring-boot-starter-webmvc-test` — see Step 3)
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/web/controller/TaskControllerTest.java`

**Interfaces:**
- Consumes: `TaskManagementUseCase`, `CreateTaskCommand` (Task 4), `ScheduledTask`/`TriggerDefinition`/`TaskDetail` (Task 2), `ApiResponse`/`PageResponse`/`GlobalExceptionHandler`/`@ValidCron` (Task 20).
- Produces: `JobDefinitionResponse.from(JobDefinition): JobDefinitionResponse` — reused as-is by Task 22 (do not create a second copy there). The `pom.xml` `spring-boot-starter-webmvc-test` addition applies to the whole project — Tasks 22, 23, 25 need no further `pom.xml` change for `@WebMvcTest`/`@AutoConfigureMockMvc`.

- [ ] **Step 1: Create `CreateTaskRequest.java`**

```java
package com.corebanking.systemreportjob.infrastructure.web.dto.request;

import com.corebanking.systemreportjob.infrastructure.common.ValidCron;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.UUID;

public record CreateTaskRequest(
        @NotBlank String name,
        @NotBlank String group,
        @NotNull UUID jobDefinitionId,
        @NotBlank String triggerType,
        @ValidCron String cronExpression,
        Integer intervalInSeconds,
        Integer repeatCount,
        Integer intervalInDays,
        Integer intervalInMinutes,
        LocalTime startingDailyAt,
        LocalTime endingDailyAt,
        String timezoneId,
        Integer priority,
        String description) {}
```

- [ ] **Step 2: Create the response DTOs**

```java
// TaskResponse.java
package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import java.util.UUID;

public record TaskResponse(UUID id, String name, String group, UUID jobDefinitionId, String description) {
    public static TaskResponse from(ScheduledTask task) {
        return new TaskResponse(task.id(), task.name(), task.group(), task.jobDefinitionId(), task.description());
    }
}
```

```java
// JobDefinitionResponse.java
package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import java.util.UUID;

public record JobDefinitionResponse(UUID id, String jobType, String expression, String description) {
    public static JobDefinitionResponse from(JobDefinition definition) {
        return new JobDefinitionResponse(
                definition.id(), definition.jobType(), definition.expression(), definition.description());
    }
}
```

```java
// TaskDetailResponse.java
package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import com.corebanking.systemreportjob.domain.model.TaskDetail;

public record TaskDetailResponse(TaskResponse task, JobDefinitionResponse jobDefinition, String triggerState) {
    public static TaskDetailResponse from(TaskDetail detail) {
        return new TaskDetailResponse(
                TaskResponse.from(detail.task()), JobDefinitionResponse.from(detail.jobDefinition()), detail.state()
                        .name());
    }
}
```

- [ ] **Step 3: Add the `@WebMvcTest` test-slice dependency to `pom.xml`, then write the failing test** — Spring Boot 4.1 moved `@WebMvcTest`/`@AutoConfigureMockMvc` out of `spring-boot-starter-test` into a dedicated `spring-boot-starter-webmvc-test` module (the same pattern Task 9 hit for Flyway and Task 10 hit for `@DataJpaTest` — see Global Constraints). This is the first task in the plan using `@WebMvcTest`, so add the dependency now; Tasks 22, 23, and 25 reuse it with no further `pom.xml` change.

In `system-report-job/pom.xml`, add (test scope):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

```java
package com.corebanking.systemreportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.common.GlobalExceptionHandler;
import com.corebanking.systemreportjob.usecase.ports.in.TaskManagementUseCase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskController.class)
@Import(GlobalExceptionHandler.class)
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskManagementUseCase taskManagementUseCase;

    @Test
    void createReturnsCreatedTask() throws Exception {
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(), "daily-report", "reports", jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"), "UTC", 5, null);
        when(taskManagementUseCase.create(any())).thenReturn(task);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"daily-report","group":"reports","jobDefinitionId":"%s",
                                 "triggerType":"CRON","cronExpression":"0 0 1 * * ?"}
                                """
                                        .formatted(jobDefinitionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("daily-report"));
    }

    @Test
    void startReturnsOk() throws Exception {
        mockMvc.perform(post("/api/tasks/start/{id}", UUID.randomUUID())).andExpect(status().isOk());
    }

    @Test
    void detailReturns404WhenTaskMissing() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(taskManagementUseCase.getDetail(taskId)).thenThrow(new TaskNotFoundException(taskId));

        mockMvc.perform(get("/api/tasks/{id}", taskId)).andExpect(status().isNotFound());
    }

    @Test
    void pauseReturnsOk() throws Exception {
        mockMvc.perform(put("/api/tasks/pause/{id}", UUID.randomUUID())).andExpect(status().isOk());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskControllerTest`
Expected: compile error — `TaskController` does not exist.

- [ ] **Step 5: Implement `TaskController.java`**

```java
package com.corebanking.systemreportjob.infrastructure.web.controller;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.common.ApiResponse;
import com.corebanking.systemreportjob.infrastructure.common.PageResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.request.CreateTaskRequest;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.TaskDetailResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.TaskResponse;
import com.corebanking.systemreportjob.usecase.ports.in.CreateTaskCommand;
import com.corebanking.systemreportjob.usecase.ports.in.TaskManagementUseCase;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
@Tag(name = "Task", description = "Task management")
@SecurityRequirement(name = "bearerAuth")
public class TaskController {

    private final TaskManagementUseCase taskManagementUseCase;

    public TaskController(TaskManagementUseCase taskManagementUseCase) {
        this.taskManagementUseCase = taskManagementUseCase;
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<TaskResponse>> search(
            @RequestParam(required = false) String keyword, Pageable pageable) {
        PageResult<ScheduledTask> result = taskManagementUseCase.search(keyword, pageable);
        return ApiResponse.ok(PageResponse.from(result, TaskResponse::from));
    }

    @PostMapping
    public ApiResponse<TaskResponse> create(@RequestBody @Valid CreateTaskRequest request) {
        ScheduledTask task = taskManagementUseCase.create(toCommand(request));
        return ApiResponse.ok(TaskResponse.from(task));
    }

    @PostMapping("/start/{id}")
    public ApiResponse<Void> start(@PathVariable UUID id) {
        taskManagementUseCase.start(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(TaskDetailResponse.from(taskManagementUseCase.getDetail(id)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        taskManagementUseCase.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/pause/{id}")
    public ApiResponse<Void> pause(@PathVariable UUID id) {
        taskManagementUseCase.pause(id);
        return ApiResponse.ok();
    }

    @PutMapping("/resume/{id}")
    public ApiResponse<Void> resume(@PathVariable UUID id) {
        taskManagementUseCase.resume(id);
        return ApiResponse.ok();
    }

    private CreateTaskCommand toCommand(CreateTaskRequest request) {
        TriggerDefinition trigger =
                switch (request.triggerType().toUpperCase()) {
                    case "CRON" -> new TriggerDefinition.Cron(request.cronExpression());
                    case "SIMPLE" -> new TriggerDefinition.Simple(request.intervalInSeconds(), request.repeatCount());
                    case "CALENDAR_INTERVAL" -> new TriggerDefinition.CalendarInterval(request.intervalInDays());
                    case "DAILY_TIME_INTERVAL" -> new TriggerDefinition.DailyTimeInterval(
                            request.startingDailyAt(), request.endingDailyAt(), request.intervalInMinutes());
                    default -> throw new IllegalArgumentException("Unknown triggerType: " + request.triggerType());
                };
        return new CreateTaskCommand(
                request.name(),
                request.group(),
                request.jobDefinitionId(),
                trigger,
                request.timezoneId(),
                request.priority(),
                request.description());
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskControllerTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add TaskController and its DTOs"
```

### Task 22: `JobDefinitionController` + DTOs

Includes a dedicated regression test for the legacy bug (`PUT` used to call `delete`).

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/request/CreateJobDefinitionRequest.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/request/UpdateJobDefinitionRequest.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/controller/JobDefinitionController.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/web/controller/JobDefinitionControllerTest.java`

**Interfaces:**
- Consumes: `JobDefinitionUseCase`, `CreateJobDefinitionCommand`, `UpdateJobDefinitionCommand` (Task 4), `JobDefinitionResponse` (Task 21), `ApiResponse`/`GlobalExceptionHandler` (Task 20).

- [ ] **Step 1: Create the request DTOs**

```java
// CreateJobDefinitionRequest.java
package com.corebanking.systemreportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateJobDefinitionRequest(@NotBlank String jobType, String expression, String description) {}
```

```java
// UpdateJobDefinitionRequest.java
package com.corebanking.systemreportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateJobDefinitionRequest(@NotBlank String jobType, String expression, String description) {}
```

- [ ] **Step 2: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.infrastructure.common.GlobalExceptionHandler;
import com.corebanking.systemreportjob.usecase.ports.in.JobDefinitionUseCase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobDefinitionController.class)
@Import(GlobalExceptionHandler.class)
class JobDefinitionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JobDefinitionUseCase jobDefinitionUseCase;

    @Test
    void putCallsUpdateNotDelete() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobDefinitionUseCase.update(eq(id), any())).thenReturn(new JobDefinition(id, "ECHO", "{}", "updated"));

        mockMvc.perform(put("/api/job-definitions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ECHO\",\"expression\":\"{}\",\"description\":\"updated\"}"))
                .andExpect(status().isOk());

        verify(jobDefinitionUseCase).update(eq(id), any());
        verify(jobDefinitionUseCase, never()).delete(any());
    }

    @Test
    void createReturnsOk() throws Exception {
        when(jobDefinitionUseCase.create(any()))
                .thenReturn(new JobDefinition(UUID.randomUUID(), "HTTP_CALL", "{}", null));

        mockMvc.perform(post("/api/job-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"HTTP_CALL\",\"expression\":\"{}\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/job-definitions/{id}", UUID.randomUUID())).andExpect(status().isOk());

        verify(jobDefinitionUseCase).delete(any());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobDefinitionControllerTest`
Expected: compile error — `JobDefinitionController` does not exist.

- [ ] **Step 4: Implement `JobDefinitionController.java`**

```java
package com.corebanking.systemreportjob.infrastructure.web.controller;

import com.corebanking.systemreportjob.infrastructure.common.ApiResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.request.CreateJobDefinitionRequest;
import com.corebanking.systemreportjob.infrastructure.web.dto.request.UpdateJobDefinitionRequest;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.JobDefinitionResponse;
import com.corebanking.systemreportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.in.JobDefinitionUseCase;
import com.corebanking.systemreportjob.usecase.ports.in.UpdateJobDefinitionCommand;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-definitions")
@Tag(name = "Job definition", description = "Job definition management")
@SecurityRequirement(name = "bearerAuth")
public class JobDefinitionController {

    private final JobDefinitionUseCase jobDefinitionUseCase;

    public JobDefinitionController(JobDefinitionUseCase jobDefinitionUseCase) {
        this.jobDefinitionUseCase = jobDefinitionUseCase;
    }

    @PostMapping
    public ApiResponse<JobDefinitionResponse> create(@RequestBody @Valid CreateJobDefinitionRequest request) {
        var definition = jobDefinitionUseCase.create(
                new CreateJobDefinitionCommand(request.jobType(), request.expression(), request.description()));
        return ApiResponse.ok(JobDefinitionResponse.from(definition));
    }

    @PutMapping("/{id}")
    public ApiResponse<JobDefinitionResponse> update(
            @PathVariable UUID id, @RequestBody @Valid UpdateJobDefinitionRequest request) {
        var definition = jobDefinitionUseCase.update(
                id, new UpdateJobDefinitionCommand(request.jobType(), request.expression(), request.description()));
        return ApiResponse.ok(JobDefinitionResponse.from(definition));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        jobDefinitionUseCase.delete(id);
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=JobDefinitionControllerTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add JobDefinitionController with regression test for legacy update-vs-delete bug"
```

### Task 23: `TaskHistoryController` + DTO

**Files:**
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/dto/response/TaskExecutionHistoryResponse.java`
- Create: `system-report-job/src/main/java/com/corebanking/systemreportjob/infrastructure/web/controller/TaskHistoryController.java`
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/infrastructure/web/controller/TaskHistoryControllerTest.java`

**Interfaces:**
- Consumes: `TaskHistoryQueryUseCase` (Task 4), `TaskExecutionRecord`/`PageResult<T>` (Task 2), `ApiResponse`/`PageResponse`/`GlobalExceptionHandler` (Task 20).

- [ ] **Step 1: Create `TaskExecutionHistoryResponse.java`**

```java
package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import java.time.Instant;
import java.util.UUID;

public record TaskExecutionHistoryResponse(
        UUID id, UUID taskId, String taskName, Instant startTime, Instant endTime, String exceptionMessage) {
    public static TaskExecutionHistoryResponse from(TaskExecutionRecord record) {
        return new TaskExecutionHistoryResponse(
                record.id(),
                record.taskId(),
                record.taskName(),
                record.startTime(),
                record.endTime(),
                record.exceptionMessage());
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.corebanking.systemreportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.infrastructure.common.GlobalExceptionHandler;
import com.corebanking.systemreportjob.usecase.ports.in.TaskHistoryQueryUseCase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskHistoryController.class)
@Import(GlobalExceptionHandler.class)
class TaskHistoryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskHistoryQueryUseCase taskHistoryQueryUseCase;

    @Test
    void searchReturnsOk() throws Exception {
        when(taskHistoryQueryUseCase.search(any(), any())).thenReturn(new PageResult<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/task-history/search")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskHistoryControllerTest`
Expected: compile error — `TaskHistoryController` does not exist.

- [ ] **Step 4: Implement `TaskHistoryController.java`**

```java
package com.corebanking.systemreportjob.infrastructure.web.controller;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.infrastructure.common.ApiResponse;
import com.corebanking.systemreportjob.infrastructure.common.PageResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.TaskExecutionHistoryResponse;
import com.corebanking.systemreportjob.usecase.ports.in.TaskHistoryQueryUseCase;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/task-history")
@Tag(name = "Task history", description = "Task execution history")
@SecurityRequirement(name = "bearerAuth")
public class TaskHistoryController {

    private final TaskHistoryQueryUseCase taskHistoryQueryUseCase;

    public TaskHistoryController(TaskHistoryQueryUseCase taskHistoryQueryUseCase) {
        this.taskHistoryQueryUseCase = taskHistoryQueryUseCase;
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<TaskExecutionHistoryResponse>> search(
            @RequestParam(required = false) String taskName, Pageable pageable) {
        PageResult<TaskExecutionRecord> result = taskHistoryQueryUseCase.search(taskName, pageable);
        return ApiResponse.ok(PageResponse.from(result, TaskExecutionHistoryResponse::from));
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=TaskHistoryControllerTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): add TaskHistoryController and its DTO"
```

---

## Phase 8 — Wiring, end-to-end verification, docs

### Task 24: Full production `application.yml`

Replaces the minimal datasource/JPA/Flyway stub from Task 9 with the complete config: Quartz JDBC JobStore + clustering flag, restricted actuator exposure, and the Flyway-vs-Quartz-autoinit fix (`initialize-schema: never` — the Quartz tables are created by `V1__create_quartz_tables.sql`, Task 9, not by the starter's own initializer).

**Files:**
- Modify: `system-report-job/src/main/resources/application.yml` (full replace)
- Create: `system-report-job/src/main/resources/application-production.yml`

**Interfaces:**
- Produces: no new Java types — only configuration properties consumed by Spring Boot's Quartz/JPA/Flyway/Actuator autoconfiguration and by `QuartzClusterConfig` (Task 14, via `spring.quartz.properties...isClustered`).

- [ ] **Step 1: Replace `application.yml` with the full config**

```yaml
server:
  port: ${SERVER_PORT:9093}
  servlet:
    context-path: /system-report-job

spring:
  application:
    name: system-report-job
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://${POSTGRESQL_HOST:localhost}:${POSTGRESQL_PORT:5432}/${POSTGRESQL_DB:db_system_report_job}
    username: ${POSTGRESQL_USER:postgres}
    password: ${POSTGRESQL_PASSWORD:root}
    hikari:
      connection-timeout: 60000
      maximum-pool-size: 5
      minimum-idle: 3
      idle-timeout: 30000
      max-lifetime: 1800000
      pool-name: SystemReportJobHikariCP
      connection-test-query: SELECT 1
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        globally_quoted_identifiers: true
  flyway:
    enabled: true
  quartz:
    job-store-type: jdbc
    jdbc:
      initialize-schema: never
    properties:
      org:
        quartz:
          scheduler:
            instanceName: SystemReportJobScheduler
            instanceId: AUTO
          jobStore:
            class: org.springframework.scheduling.quartz.LocalDataSourceJobStore
            driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
            tablePrefix: QRTZ_
            isClustered: ${QUARTZ_JOB_STORE_IS_CLUSTERED:false}
            clusterCheckinInterval: 20000
            useProperties: true

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when-authorized
```

- [ ] **Step 2: Create `application-production.yml`** — the legacy config left `isClustered` at an env-var default of `false` with no committed profile that ever turns it on, so in practice it always ran single-instance despite using the JDBC JobStore. This profile makes clustering the explicit, committed production behavior instead of an opt-in nobody opts into.

```yaml
spring:
  quartz:
    properties:
      org:
        quartz:
          jobStore:
            isClustered: true
```

- [ ] **Step 3: Run the full test suite to confirm nothing regressed**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test`
Expected: `BUILD SUCCESS`, every test from Tasks 2-23 still passes (Testcontainers-backed tests override `spring.datasource.*`/`spring.quartz.job-store-type` via `@DynamicPropertySource`/`application-test.yml`, so they're unaffected by this file).

- [ ] **Step 4: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "feat(system-report-job): finalize production application.yml (Quartz clustering, restricted actuator)"
```

### Task 25: End-to-end test — full REST → Quartz → history pipeline

Boots the entire app (real JDBC-backed Quartz, not the `memory` store used by every earlier test) against Testcontainers Postgres, and drives it purely through HTTP, proving every layer wired together correctly.

**Files:**
- Test: `system-report-job/src/test/java/com/corebanking/systemreportjob/e2e/EndToEndTaskExecutionTest.java`

**Interfaces:**
- Consumes: the full REST API (Tasks 21-23), `EchoInProcessJobAction` (Task 19, `jobType = "ECHO"`).

- [ ] **Step 1: Write the test**

```java
package com.corebanking.systemreportjob.e2e;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class EndToEndTaskExecutionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override the "test" profile's in-memory Quartz store for this one test — exercises the
        // real JDBC-backed job store (and the QRTZ_* tables from V1) against Testcontainers Postgres.
        registry.add("spring.quartz.job-store-type", () -> "jdbc");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsTaskAndRecordsExecutionHistoryEndToEnd() throws Exception {
        String jobDefinitionBody = mockMvc.perform(post("/api/job-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ECHO\",\"expression\":\"{\\\"msg\\\":\\\"e2e\\\"}\"}"))
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
                                {"name":"e2e-task","group":"e2e","jobDefinitionId":"%s",
                                 "triggerType":"SIMPLE","intervalInSeconds":1,"repeatCount":0}
                                """
                                        .formatted(jobDefinitionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(taskBody).path("data").path("id").asText();

        mockMvc.perform(post("/api/tasks/start/{id}", taskId)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mockMvc.perform(
                        get("/api/task-history/search").param("taskName", "e2e-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()", is(1))));
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test -Dtest=EndToEndTaskExecutionTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 3: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "test(system-report-job): add end-to-end REST -> Quartz -> history pipeline test"
```

### Task 26: `README.md` and final full-suite verification

**Files:**
- Create: `system-report-job/README.md`

**Interfaces:**
- Produces: nothing consumed by other tasks — this is the last task in the plan.

- [ ] **Step 1: Create `README.md`**

```markdown
# system-report-job

Dynamic Quartz job/trigger management service — Clean Architecture rewrite of the legacy
`system-report-job` module (Spring Boot 3.4.5, `vn.tiger:microservice-java` monorepo) on
**Spring Boot 4.1 / Java 21**.

## Run locally

```bash
docker run -d --name system-report-job-db -e POSTGRES_DB=db_system_report_job \
  -e POSTGRES_PASSWORD=root -p 5432:5432 postgres:16-alpine

mvn -f system-report-job/pom.xml spring-boot:run
```

Swagger UI: http://localhost:9093/system-report-job/swagger-ui.html

## Architecture

See `docs/superpowers/specs/2026-08-09-system-report-job-v2-design.md` (design spec) and
`docs/superpowers/plans/2026-08-10-system-report-job-implementation.md` (implementation plan),
both in this repo's root.

- `domain/` — framework-free models and exceptions
- `usecase/` — ports (in/out) + services
- `infrastructure/` — Quartz scheduler, JPA persistence, REST controllers, job-action strategies

## Testing

```bash
mvn -f system-report-job/pom.xml test
```

Persistence/scheduler/end-to-end tests use Testcontainers — Docker must be running locally.
```

- [ ] **Step 2: Run the complete test suite one final time**

Run: `mvn -q -f /Users/tigerpro/Documents/SA/core-banking-10000tps/system-report-job/pom.xml test`
Expected: `BUILD SUCCESS`, all tests from every task (2 through 25) pass together.

- [ ] **Step 3: Commit**

```bash
cd /Users/tigerpro/Documents/SA/core-banking-10000tps
git add system-report-job/
git commit -m "docs(system-report-job): add README"
```

---

## Post-plan follow-ups (explicitly out of scope — see spec section 11)

- Spring Security / JWT auth is not implemented; `@SecurityRequirement` annotations are documentation-only.
- No data migration from the legacy `db_system_job` database — this plan only builds the new schema.
- Quartz cluster behavior (`isClustered: true` with 2+ instances) is configured but not exercised by a multi-instance test — standard Quartz clustering is relied upon as-is.
