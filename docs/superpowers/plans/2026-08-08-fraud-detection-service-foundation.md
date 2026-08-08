# fraud-detection-service — Foundation (Phase 0+1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap the `fraud-detection-service` Maven/Spring Boot project and deliver a
working rule-version data model (entities, migration, repositories, audit trail) with a
read-only `GET /admin/fraud-rules/history` endpoint — no Drools, no file-platform
integration, no security yet (those are later plans).

**Architecture:** Package-by-feature single Maven module. `ruleversion` package holds the
`FraudRuleVersion` aggregate and its audit trail (`ruleversion.audit` sub-package). Postgres
schema is owned by Flyway migrations under `src/main/resources/db/migration`. All
integration-style tests share one `AbstractIntegrationTest` base class backed by a
Testcontainers Postgres instance so behavior is verified against a real database, not H2.

**Tech Stack:** Java 21, Spring Boot 4.1 (`spring-boot-starter-parent` 4.1.0), Maven,
PostgreSQL 16, Flyway, Spring Data JPA, Testcontainers, JUnit 5, AssertJ, Mockito.

## Global Constraints

- JDK 21, Spring Boot 4.1, Maven build — no Gradle.
- PostgreSQL only. **No MinIO, no direct file storage in this service** — file handling is
  out of scope for this plan (added in a later "file-platform integration" plan).
- Base package: `com.corebanking.frauddetection`.
- Project root for all files in this plan: `fraud-detection-service/` (relative to repo
  root `core-banking-10000tps/`).
- Docker must be running locally — Tasks 1-4 use Testcontainers (real Postgres container)
  for their tests. Tasks 5-7 use pure Mockito/`@WebMvcTest` and do **not** need Docker.
  Run `docker info` first if a Testcontainers test hangs or fails to start.
  Testcontainers version: `testcontainers-bom` 1.20.4.
  Postgres test image: `postgres:16-alpine`.
- No Lombok — write explicit constructors/getters, or Java records for immutable DTOs.
- JSON columns (`backtest_summary`, `detail`) are mapped as `String` fields with
  `@JdbcTypeCode(SqlTypes.JSON)` from `org.hibernate.annotations`/`org.hibernate.type` —
  this plan does not build typed JSON object mapping, callers pass pre-serialized JSON
  strings.
- **No authentication/authorization is implemented in this plan.** The
  `GET /admin/fraud-rules/history` endpoint is open. Role `ops-admin` enforcement is
  added in the security plan (Phase 6) — do not add auth logic here.
- Restricting `UPDATE`/`DELETE` grants on `fraud_rule_audit_log` for the application DB
  role is a deployment/infra concern (needs a non-owner app role) and is explicitly
  **out of scope** for this plan's migration — tracked as a follow-up, not implemented here.

---

### Task 1: Project bootstrap — Maven project, Spring Boot app, Testcontainers base, health check

**Files:**
- Create: `fraud-detection-service/pom.xml`
- Create: `fraud-detection-service/.gitignore`
- Create: `fraud-detection-service/src/main/resources/application.yml`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/FraudDetectionServiceApplication.java`
- Create: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/AbstractIntegrationTest.java`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/FraudDetectionServiceApplicationTests.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `com.corebanking.frauddetection.FraudDetectionServiceApplication` (Spring Boot
  entry point); `com.corebanking.frauddetection.AbstractIntegrationTest` — abstract test
  base class, annotated `@Testcontainers @SpringBootTest(webEnvironment = RANDOM_PORT)`,
  starts a shared static `PostgreSQLContainer<?> POSTGRES` and registers
  `spring.datasource.*` via `@DynamicPropertySource`. All later integration tests extend
  this class.

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.corebanking</groupId>
    <artifactId>fraud-detection-service</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>fraud-detection-service</name>
    <description>Fraud rule version management + fraud detection pipeline</description>

    <properties>
        <java.version>21</java.version>
        <testcontainers.version>1.20.4</testcontainers.version>
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
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
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

- [ ] **Step 3: Sanity-check the POM resolves**

Run: `cd fraud-detection-service && mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS` (empty `src/main/java`, nothing to compile yet, but dependency
resolution must succeed).

- [ ] **Step 4: Create the Testcontainers base test class**

```java
package com.corebanking.frauddetection;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fraud_detection_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

- [ ] **Step 5: Write the failing test**

```java
package com.corebanking.frauddetection;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class FraudDetectionServiceApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `mvn test -Dtest=FraudDetectionServiceApplicationTests`
Expected: FAIL — `IllegalStateException: Unable to find a @SpringBootConfiguration` (no
`@SpringBootApplication` class exists yet for `@SpringBootTest` to bootstrap from).

- [ ] **Step 7: Create `application.yml`**

```yaml
spring:
  application:
    name: fraud-detection-service
  datasource:
    url: jdbc:postgresql://localhost:5432/fraud_detection
    username: fraud_detection
    password: fraud_detection
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health

server:
  port: 8081
```

- [ ] **Step 8: Create the application entry point**

```java
package com.corebanking.frauddetection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FraudDetectionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionServiceApplication.class, args);
    }
}
```

- [ ] **Step 9: Run the test to verify it passes**

Run: `mvn test -Dtest=FraudDetectionServiceApplicationTests`
Expected: PASS — both `contextLoads()` and `healthEndpointReturnsUp()` green. (First run
pulls the `postgres:16-alpine` image, so it may take longer.)

- [ ] **Step 10: Commit**

```bash
git add fraud-detection-service/pom.xml fraud-detection-service/.gitignore \
        fraud-detection-service/src/main/resources/application.yml \
        fraud-detection-service/src/main/java/com/corebanking/frauddetection/FraudDetectionServiceApplication.java \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/AbstractIntegrationTest.java \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/FraudDetectionServiceApplicationTests.java
git commit -m "feat(fraud-detection-service): bootstrap Spring Boot project with Testcontainers base"
```

---

### Task 2: Flyway migration — `fraud_rule_version` + `fraud_rule_audit_log` tables

**Files:**
- Create: `fraud-detection-service/src/main/resources/db/migration/V1__init_fraud_rule_tables.sql`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/SchemaMigrationTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (Task 1).
- Produces: Postgres tables `fraud_rule_version` and `fraud_rule_audit_log`, available in
  every test/run from this point on (used by Tasks 3-4's JPA entities).

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.frauddetection.ruleversion;

import com.corebanking.frauddetection.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fraudRuleTablesExistAfterMigration() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_name IN ('fraud_rule_version', 'fraud_rule_audit_log')",
                Integer.class);

        assertThat(tableCount).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=SchemaMigrationTest`
Expected: FAIL — `tableCount` is `0` (no migration files exist yet, Flyway only created the
empty `flyway_schema_history` table).

- [ ] **Step 3: Create the migration**

```sql
-- V1__init_fraud_rule_tables.sql
CREATE TABLE fraud_rule_version (
    id                    BIGSERIAL PRIMARY KEY,
    version_no            INT NOT NULL,
    file_platform_ref_id  UUID NOT NULL,
    status                VARCHAR(20) NOT NULL,
    uploaded_by           VARCHAR(100) NOT NULL,
    uploaded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by          VARCHAR(100),
    activated_at          TIMESTAMPTZ,
    backtest_summary      JSONB,
    CONSTRAINT uq_fraud_rule_version_version_no UNIQUE (version_no)
);

-- Intended to be append-only: no UPDATE/DELETE grants for the application DB role in
-- production. That role/grant setup is an infra concern, tracked separately — NOT done
-- in this migration (see plan's Global Constraints).
CREATE TABLE fraud_rule_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    version_id   BIGINT NOT NULL REFERENCES fraud_rule_version(id),
    action       VARCHAR(30) NOT NULL,
    performed_by VARCHAR(100) NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail       JSONB
);
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=SchemaMigrationTest`
Expected: PASS — `tableCount` is `2`.

- [ ] **Step 5: Commit**

```bash
git add fraud-detection-service/src/main/resources/db/migration/V1__init_fraud_rule_tables.sql \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/SchemaMigrationTest.java
git commit -m "feat(fraud-detection-service): add Flyway migration for rule version tables"
```

---

### Task 3: `FraudRuleVersion` entity + repository

**Files:**
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersionStatus.java`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersion.java`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersionRepository.java`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersionRepositoryTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (Task 1), `fraud_rule_version` table (Task 2).
- Produces: `FraudRuleVersionStatus` enum (`DRAFT, BACKTESTED, ACTIVE, INACTIVE`);
  `FraudRuleVersion` entity with constructor
  `FraudRuleVersion(Integer versionNo, UUID filePlatformRefId, FraudRuleVersionStatus status, String uploadedBy, Instant uploadedAt)`
  and getters `getId()`, `getVersionNo()`, `getFilePlatformRefId()`, `getStatus()`,
  `getUploadedBy()`, `getUploadedAt()`, `getActivatedBy()`, `getActivatedAt()`,
  `getBacktestSummary()`, plus setters `setStatus`, `setActivatedBy`, `setActivatedAt`,
  `setBacktestSummary`; `FraudRuleVersionRepository extends JpaRepository<FraudRuleVersion, Long>`
  with `findByVersionNo(Integer): Optional<FraudRuleVersion>` and
  `findAllByOrderByVersionNoDesc(): List<FraudRuleVersion>`.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.frauddetection.ruleversion;

import com.corebanking.frauddetection.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudRuleVersionRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private FraudRuleVersionRepository repository;

    @Test
    void savesAndFindsByVersionNo() {
        FraudRuleVersion version = new FraudRuleVersion(
                1, UUID.randomUUID(), FraudRuleVersionStatus.DRAFT, "admin1", Instant.now());

        repository.saveAndFlush(version);

        Optional<FraudRuleVersion> found = repository.findByVersionNo(1);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(FraudRuleVersionStatus.DRAFT);
        assertThat(found.get().getUploadedBy()).isEqualTo("admin1");
    }

    @Test
    void rejectsDuplicateVersionNo() {
        repository.saveAndFlush(new FraudRuleVersion(
                2, UUID.randomUUID(), FraudRuleVersionStatus.DRAFT, "admin1", Instant.now()));

        FraudRuleVersion duplicate = new FraudRuleVersion(
                2, UUID.randomUUID(), FraudRuleVersionStatus.DRAFT, "admin2", Instant.now());

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=FraudRuleVersionRepositoryTest`
Expected: FAIL — compile error, `FraudRuleVersion`/`FraudRuleVersionStatus`/
`FraudRuleVersionRepository` do not exist yet.

- [ ] **Step 3: Create the status enum**

```java
package com.corebanking.frauddetection.ruleversion;

public enum FraudRuleVersionStatus {
    DRAFT,
    BACKTESTED,
    ACTIVE,
    INACTIVE
}
```

- [ ] **Step 4: Create the entity**

```java
package com.corebanking.frauddetection.ruleversion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_rule_version")
public class FraudRuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_no", nullable = false, unique = true)
    private Integer versionNo;

    @Column(name = "file_platform_ref_id", nullable = false)
    private UUID filePlatformRefId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FraudRuleVersionStatus status;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "backtest_summary", columnDefinition = "jsonb")
    private String backtestSummary;

    protected FraudRuleVersion() {
        // JPA
    }

    public FraudRuleVersion(Integer versionNo, UUID filePlatformRefId, FraudRuleVersionStatus status,
                             String uploadedBy, Instant uploadedAt) {
        this.versionNo = versionNo;
        this.filePlatformRefId = filePlatformRefId;
        this.status = status;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public UUID getFilePlatformRefId() {
        return filePlatformRefId;
    }

    public FraudRuleVersionStatus getStatus() {
        return status;
    }

    public void setStatus(FraudRuleVersionStatus status) {
        this.status = status;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(String activatedBy) {
        this.activatedBy = activatedBy;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public String getBacktestSummary() {
        return backtestSummary;
    }

    public void setBacktestSummary(String backtestSummary) {
        this.backtestSummary = backtestSummary;
    }
}
```

- [ ] **Step 5: Create the repository**

```java
package com.corebanking.frauddetection.ruleversion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FraudRuleVersionRepository extends JpaRepository<FraudRuleVersion, Long> {

    Optional<FraudRuleVersion> findByVersionNo(Integer versionNo);

    List<FraudRuleVersion> findAllByOrderByVersionNoDesc();
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=FraudRuleVersionRepositoryTest`
Expected: PASS — both tests green.

- [ ] **Step 7: Commit**

```bash
git add fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersionStatus.java \
        fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersion.java \
        fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersionRepository.java \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/FraudRuleVersionRepositoryTest.java
git commit -m "feat(fraud-detection-service): add FraudRuleVersion entity and repository"
```

---

### Task 4: `FraudRuleAuditLog` entity + repository

**Files:**
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/audit/FraudRuleAuditAction.java`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/audit/FraudRuleAuditLog.java`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/audit/FraudRuleAuditLogRepository.java`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/audit/FraudRuleAuditLogRepositoryTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest` (Task 1), `fraud_rule_audit_log` table (Task 2),
  `FraudRuleVersion`/`FraudRuleVersionRepository`/`FraudRuleVersionStatus` (Task 3 — the
  test must insert a parent `fraud_rule_version` row first because
  `fraud_rule_audit_log.version_id` has a `REFERENCES fraud_rule_version(id)` FK
  constraint).
- Produces: `FraudRuleAuditAction` enum
  (`UPLOADED, VALIDATED, BACKTESTED, ACTIVATED, ROLLED_BACK`); `FraudRuleAuditLog` entity
  with constructor
  `FraudRuleAuditLog(Long versionId, FraudRuleAuditAction action, String performedBy, Instant performedAt, String detail)`
  and getters `getId()`, `getVersionId()`, `getAction()`, `getPerformedBy()`,
  `getPerformedAt()`, `getDetail()`;
  `FraudRuleAuditLogRepository extends JpaRepository<FraudRuleAuditLog, Long>` with
  `findAllByVersionIdOrderByPerformedAtAsc(Long): List<FraudRuleAuditLog>`.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.frauddetection.ruleversion.audit;

import com.corebanking.frauddetection.AbstractIntegrationTest;
import com.corebanking.frauddetection.ruleversion.FraudRuleVersion;
import com.corebanking.frauddetection.ruleversion.FraudRuleVersionRepository;
import com.corebanking.frauddetection.ruleversion.FraudRuleVersionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRuleAuditLogRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private FraudRuleAuditLogRepository auditLogRepository;

    @Autowired
    private FraudRuleVersionRepository versionRepository;

    @Test
    void savesAndFindsByVersionIdOrderedByPerformedAt() {
        FraudRuleVersion version = versionRepository.saveAndFlush(new FraudRuleVersion(
                50, UUID.randomUUID(), FraudRuleVersionStatus.DRAFT, "admin1", Instant.now()));
        Long versionId = version.getId();

        Instant first = Instant.parse("2026-08-01T00:00:00Z");
        Instant second = Instant.parse("2026-08-02T00:00:00Z");

        auditLogRepository.saveAndFlush(new FraudRuleAuditLog(
                versionId, FraudRuleAuditAction.ACTIVATED, "admin2", second, null));
        auditLogRepository.saveAndFlush(new FraudRuleAuditLog(
                versionId, FraudRuleAuditAction.UPLOADED, "admin1", first, null));

        List<FraudRuleAuditLog> logs = auditLogRepository.findAllByVersionIdOrderByPerformedAtAsc(versionId);

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getAction()).isEqualTo(FraudRuleAuditAction.UPLOADED);
        assertThat(logs.get(1).getAction()).isEqualTo(FraudRuleAuditAction.ACTIVATED);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=FraudRuleAuditLogRepositoryTest`
Expected: FAIL — compile error, `FraudRuleAuditLog`/`FraudRuleAuditAction`/
`FraudRuleAuditLogRepository` do not exist yet.

- [ ] **Step 3: Create the action enum**

```java
package com.corebanking.frauddetection.ruleversion.audit;

public enum FraudRuleAuditAction {
    UPLOADED,
    VALIDATED,
    BACKTESTED,
    ACTIVATED,
    ROLLED_BACK
}
```

- [ ] **Step 4: Create the entity**

```java
package com.corebanking.frauddetection.ruleversion.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "fraud_rule_audit_log")
public class FraudRuleAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private FraudRuleAuditAction action;

    @Column(name = "performed_by", nullable = false, length = 100)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private String detail;

    protected FraudRuleAuditLog() {
        // JPA
    }

    public FraudRuleAuditLog(Long versionId, FraudRuleAuditAction action, String performedBy,
                              Instant performedAt, String detail) {
        this.versionId = versionId;
        this.action = action;
        this.performedBy = performedBy;
        this.performedAt = performedAt;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getVersionId() {
        return versionId;
    }

    public FraudRuleAuditAction getAction() {
        return action;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }

    public String getDetail() {
        return detail;
    }
}
```

- [ ] **Step 5: Create the repository**

```java
package com.corebanking.frauddetection.ruleversion.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudRuleAuditLogRepository extends JpaRepository<FraudRuleAuditLog, Long> {

    List<FraudRuleAuditLog> findAllByVersionIdOrderByPerformedAtAsc(Long versionId);
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn test -Dtest=FraudRuleAuditLogRepositoryTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/audit/ \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/audit/
git commit -m "feat(fraud-detection-service): add FraudRuleAuditLog entity and repository"
```

---

### Task 5: `AuditLogService`

**Files:**
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/config/ClockConfig.java`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/audit/AuditLogService.java`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/audit/AuditLogServiceTest.java`

**Interfaces:**
- Consumes: `FraudRuleAuditLogRepository`, `FraudRuleAuditLog`, `FraudRuleAuditAction`
  (Task 4).
- Produces: `Clock` bean (`ClockConfig.clock()`) usable by any later service that needs
  the current time; `AuditLogService.record(Long versionId, FraudRuleAuditAction action, String performedBy, String detail): FraudRuleAuditLog`.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.frauddetection.ruleversion.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private FraudRuleAuditLogRepository repository;

    @Test
    void recordsAuditLogWithClockTime() {
        Instant fixedInstant = Instant.parse("2026-08-08T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        AuditLogService service = new AuditLogService(repository, fixedClock);

        when(repository.save(any(FraudRuleAuditLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FraudRuleAuditLog result = service.record(
                7L, FraudRuleAuditAction.UPLOADED, "admin1", "{\"filename\":\"rules.xlsx\"}");

        ArgumentCaptor<FraudRuleAuditLog> captor = ArgumentCaptor.forClass(FraudRuleAuditLog.class);
        verify(repository).save(captor.capture());
        FraudRuleAuditLog saved = captor.getValue();

        assertThat(saved.getVersionId()).isEqualTo(7L);
        assertThat(saved.getAction()).isEqualTo(FraudRuleAuditAction.UPLOADED);
        assertThat(saved.getPerformedBy()).isEqualTo("admin1");
        assertThat(saved.getPerformedAt()).isEqualTo(fixedInstant);
        assertThat(saved.getDetail()).isEqualTo("{\"filename\":\"rules.xlsx\"}");
        assertThat(result.getPerformedAt()).isEqualTo(fixedInstant);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=AuditLogServiceTest`
Expected: FAIL — compile error, `AuditLogService` does not exist yet.

- [ ] **Step 3: Create `ClockConfig`**

```java
package com.corebanking.frauddetection.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Create `AuditLogService`**

```java
package com.corebanking.frauddetection.ruleversion.audit;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class AuditLogService {

    private final FraudRuleAuditLogRepository repository;
    private final Clock clock;

    public AuditLogService(FraudRuleAuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public FraudRuleAuditLog record(Long versionId, FraudRuleAuditAction action, String performedBy, String detail) {
        FraudRuleAuditLog log = new FraudRuleAuditLog(versionId, action, performedBy, Instant.now(clock), detail);
        return repository.save(log);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=AuditLogServiceTest`
Expected: PASS. (No Docker needed — this is a pure Mockito unit test.)

- [ ] **Step 6: Commit**

```bash
git add fraud-detection-service/src/main/java/com/corebanking/frauddetection/config/ClockConfig.java \
        fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/audit/AuditLogService.java \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/audit/AuditLogServiceTest.java
git commit -m "feat(fraud-detection-service): add AuditLogService"
```

---

### Task 6: `RuleVersionQueryService`

**Files:**
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/RuleVersionQueryService.java`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/RuleVersionQueryServiceTest.java`

**Interfaces:**
- Consumes: `FraudRuleVersionRepository`, `FraudRuleVersion`, `FraudRuleVersionStatus`
  (Task 3).
- Produces: `RuleVersionQueryService.listHistory(): List<FraudRuleVersion>`.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.frauddetection.ruleversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleVersionQueryServiceTest {

    @Mock
    private FraudRuleVersionRepository repository;

    @Test
    void listHistoryReturnsVersionsOrderedByRepository() {
        FraudRuleVersion v2 = new FraudRuleVersion(
                2, UUID.randomUUID(), FraudRuleVersionStatus.ACTIVE, "admin1", Instant.now());
        FraudRuleVersion v1 = new FraudRuleVersion(
                1, UUID.randomUUID(), FraudRuleVersionStatus.INACTIVE, "admin1", Instant.now());
        when(repository.findAllByOrderByVersionNoDesc()).thenReturn(List.of(v2, v1));

        RuleVersionQueryService service = new RuleVersionQueryService(repository);
        List<FraudRuleVersion> result = service.listHistory();

        assertThat(result).containsExactly(v2, v1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=RuleVersionQueryServiceTest`
Expected: FAIL — compile error, `RuleVersionQueryService` does not exist yet.

- [ ] **Step 3: Create `RuleVersionQueryService`**

```java
package com.corebanking.frauddetection.ruleversion;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleVersionQueryService {

    private final FraudRuleVersionRepository repository;

    public RuleVersionQueryService(FraudRuleVersionRepository repository) {
        this.repository = repository;
    }

    public List<FraudRuleVersion> listHistory() {
        return repository.findAllByOrderByVersionNoDesc();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn test -Dtest=RuleVersionQueryServiceTest`
Expected: PASS. (No Docker needed — pure Mockito unit test.)

- [ ] **Step 5: Commit**

```bash
git add fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/RuleVersionQueryService.java \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/RuleVersionQueryServiceTest.java
git commit -m "feat(fraud-detection-service): add RuleVersionQueryService"
```

---

### Task 7: `GET /admin/fraud-rules/history` endpoint

**Files:**
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/api/RuleVersionHistoryItem.java`
- Create: `fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/api/RuleVersionController.java`
- Test: `fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/api/RuleVersionControllerTest.java`

**Interfaces:**
- Consumes: `RuleVersionQueryService.listHistory()` (Task 6), `FraudRuleVersion` getters
  (Task 3).
- Produces: `GET /admin/fraud-rules/history` REST endpoint returning
  `List<RuleVersionHistoryItem>` as JSON. **Unauthenticated in this plan** — see Global
  Constraints.

- [ ] **Step 1: Write the failing test**

```java
package com.corebanking.frauddetection.ruleversion.api;

import com.corebanking.frauddetection.ruleversion.FraudRuleVersion;
import com.corebanking.frauddetection.ruleversion.FraudRuleVersionStatus;
import com.corebanking.frauddetection.ruleversion.RuleVersionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RuleVersionController.class)
class RuleVersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuleVersionQueryService queryService;

    @Test
    void historyReturnsVersionsAsJson() throws Exception {
        FraudRuleVersion version = new FraudRuleVersion(
                3, UUID.randomUUID(), FraudRuleVersionStatus.ACTIVE, "admin1",
                Instant.parse("2026-08-01T00:00:00Z"));
        when(queryService.listHistory()).thenReturn(List.of(version));

        mockMvc.perform(get("/admin/fraud-rules/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionNo").value(3))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].uploadedBy").value("admin1"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn test -Dtest=RuleVersionControllerTest`
Expected: FAIL — compile error, `RuleVersionController`/`RuleVersionHistoryItem` do not
exist yet.

- [ ] **Step 3: Create the response DTO**

```java
package com.corebanking.frauddetection.ruleversion.api;

import java.time.Instant;

public record RuleVersionHistoryItem(
        Long id,
        Integer versionNo,
        String status,
        String uploadedBy,
        Instant uploadedAt,
        String activatedBy,
        Instant activatedAt
) {
}
```

- [ ] **Step 4: Create the controller**

```java
package com.corebanking.frauddetection.ruleversion.api;

import com.corebanking.frauddetection.ruleversion.FraudRuleVersion;
import com.corebanking.frauddetection.ruleversion.RuleVersionQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/fraud-rules")
public class RuleVersionController {

    private final RuleVersionQueryService queryService;

    public RuleVersionController(RuleVersionQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/history")
    public List<RuleVersionHistoryItem> history() {
        return queryService.listHistory().stream()
                .map(this::toHistoryItem)
                .toList();
    }

    private RuleVersionHistoryItem toHistoryItem(FraudRuleVersion version) {
        return new RuleVersionHistoryItem(
                version.getId(),
                version.getVersionNo(),
                version.getStatus().name(),
                version.getUploadedBy(),
                version.getUploadedAt(),
                version.getActivatedBy(),
                version.getActivatedAt());
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn test -Dtest=RuleVersionControllerTest`
Expected: PASS. (No Docker needed — `@WebMvcTest` only loads the web layer.)

- [ ] **Step 6: Run the full test suite**

Run: `mvn test`
Expected: PASS — all tests from Tasks 1-7 green (requires Docker running for the
Testcontainers-backed tests).

- [ ] **Step 7: Commit**

```bash
git add fraud-detection-service/src/main/java/com/corebanking/frauddetection/ruleversion/api/ \
        fraud-detection-service/src/test/java/com/corebanking/frauddetection/ruleversion/api/
git commit -m "feat(fraud-detection-service): add GET /admin/fraud-rules/history endpoint"
```

---

## What's next (not in this plan)

- **`CLAUDE.md`** for the service — will be written once this foundation is in place, so
  it documents the actual package layout rather than a plan.
- **Phase 2 plan**: `FilePlatformClient` + `upload/initiate` + `upload/{id}/confirm`
  endpoints, storing `DRAFT` versions (needs `RuleVersionQueryService` extended with a
  "create" method and a `nextVersionNumber()` query — deliberately not added here since
  nothing in this plan needs it yet).
- **Phase 3 plan**: Drools `RuleEngineAdapter` + `RuleEngineManager` hot-swap, real
  validation wired into `confirm`, `activate`/`rollback` endpoints.
- **Phase 4-6 plans**: BacktestRunner, Kafka pipeline, security (trusted-header auth +
  `ops-admin` role check — this is when the `history`/`upload`/`activate` endpoints stop
  being open).
