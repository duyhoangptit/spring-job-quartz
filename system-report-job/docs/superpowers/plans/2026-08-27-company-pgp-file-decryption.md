# Company PGP File Decryption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable, per-company PGP decrypt-and-verify flow (`company_pgp_key_config` table +
`DecryptCompanyFileUseCase`) and wire it into `BANK_SALARY_PAYROLL` as the first consumer, with zero
changes to `PayrollBatchConfig`.

**Architecture:** Clean Architecture additions across all three layers — a pure `CompanyPgpKeyConfig`
domain record; `usecase/ports` for the repository and the decryption gateway plus two usecases
(admin CRUD, file decryption); Bouncy Castle-backed infrastructure adapters for AES-GCM key-material
sealing and OpenPGP decrypt+verify; a REST controller for admin CRUD; and a small, explicit
`pgpEncrypted` opt-in wired into `PayrollJobAction` only.

**Tech Stack:** Spring Boot 4.1 / Java 21, Spring Data JPA + Flyway (Postgres), Bouncy Castle
(`bcpg-jdk18on`/`bcprov-jdk18on` 1.80, pure lightweight `org.bouncycastle.openpgp.operator.bc.*` API —
no JCE `Security.addProvider` needed), JUnit 5 + Mockito + AssertJ + Testcontainers (existing stack).

**Spec:** `system-report-job/docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md`

## Global Constraints

- `domain/` must never import Spring, JPA, Quartz, or Bouncy Castle types (CLAUDE.md) — BC only appears
  under `infrastructure/security/pgp`.
- Every `ErrorCode` added must also get a case in `GlobalExceptionHandler.statusFor(ErrorCode)` — the
  switch is exhaustive, the compiler enforces it.
- Schema changes only via Flyway migrations under `src/main/resources/db/migration/` — never `ddl-auto`.
- One PGP key config **active per company** — rotate by `UPDATE`, no history table (YAGNI per spec §2).
- Decrypt **always** verifies the OpenPGP signature too — a file that decrypts but fails signature
  verification must never be returned to the caller (spec §5).
- `bank_private_key_encrypted` / `bank_key_passphrase_encrypted` are sealed with AES-256-GCM using a
  master key from `app.pgp.master-key` (base64, must decode to exactly 32 bytes) — never stored or
  logged in plaintext. `company_public_key_armored` is not sensitive and stays plaintext.
- The admin REST API (`CompanyPgpKeyConfigController`) must never return `bankPrivateKeyArmored` or
  `bankKeyPassphrase` in any response, including `GET`.
- `PayrollBatchConfig` (`holdFundsTasklet`, `csvEmployeeReader`) must not change — decryption happens in
  `PayrollJobAction.runJob()` before `JobParameters` are built, and the decrypted temp file must be
  deleted in a `finally` block even when the batch job fails (spec §4).
- `pgpEncrypted` is an explicit, optional field on `PayrollExpression` (default `false`) — behavior is
  never inferred from whether a `company_pgp_key_config` row happens to exist (spec §4).
- Run `mvn spotless:apply` before every commit that touches `.java` files — `spotless:check` gates the
  `compile` phase and will fail the build otherwise.
- Deviations from the spec's literal wording, made during planning for consistency with existing code
  conventions, are called out explicitly in each task where they occur (see Task 3, Task 5, Task 7).

---

### Task 1: Add Bouncy Castle dependency

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Produces: `org.bouncycastle:bcpg-jdk18on:1.80` and `org.bouncycastle:bcprov-jdk18on:1.80` on the
  compile classpath, used by Task 5 onward.

- [ ] **Step 1: Add the `bouncycastle.version` property**

In `pom.xml`, inside `<properties>`, add (right after `<springdoc.version>2.7.0</springdoc.version>`):

```xml
<bouncycastle.version>1.80</bouncycastle.version>
```

- [ ] **Step 2: Add the two dependencies**

Inside `<dependencies>`, right after the `springdoc-openapi-starter-webmvc-ui` dependency block, add:

```xml
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpg-jdk18on</artifactId>
    <version>${bouncycastle.version}</version>
</dependency>
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk18on</artifactId>
    <version>${bouncycastle.version}</version>
</dependency>
```

- [ ] **Step 3: Verify the dependencies resolve and the project still compiles**

Run: `mvn -q compile`
Expected: `BUILD SUCCESS`, no dependency resolution errors.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: add Bouncy Castle PGP dependencies"
```

---

### Task 2: Domain model, exceptions, error codes, i18n messages

**Files:**
- Create: `src/main/java/com/system/reportjob/domain/model/CompanyPgpKeyConfig.java`
- Create: `src/main/java/com/system/reportjob/domain/exception/PgpKeyConfigNotFoundException.java`
- Create: `src/main/java/com/system/reportjob/domain/exception/PgpKeyConfigAlreadyExistsException.java`
- Create: `src/main/java/com/system/reportjob/domain/exception/PgpDecryptionFailedException.java`
- Create: `src/main/java/com/system/reportjob/domain/exception/PgpSignatureInvalidException.java`
- Modify: `src/main/java/com/system/reportjob/domain/exception/ErrorCode.java`
- Modify: `src/main/java/com/system/reportjob/infrastructure/common/GlobalExceptionHandler.java`
- Modify: `src/main/resources/messages.properties`
- Modify: `src/main/resources/messages_en.properties`
- Test: `src/test/java/com/system/reportjob/domain/model/CompanyPgpKeyConfigTest.java`
- Test: `src/test/java/com/system/reportjob/infrastructure/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `record CompanyPgpKeyConfig(UUID id, String companyCode, String bankPrivateKeyArmored,
  String bankKeyPassphrase, String companyPublicKeyArmored, String keyFingerprint, boolean active)`
  — the shared domain type every later task depends on. `PgpKeyConfigNotFoundException(String
  companyCode)`, `PgpKeyConfigAlreadyExistsException(String companyCode)`,
  `PgpDecryptionFailedException(String companyCode, String reason)`,
  `PgpSignatureInvalidException(String companyCode, String reason)` — all `extends BusinessException`.
  `ErrorCode.PGP_KEY_CONFIG_NOT_FOUND`, `PGP_KEY_CONFIG_ALREADY_EXISTS`, `PGP_DECRYPTION_FAILED`,
  `PGP_SIGNATURE_INVALID`.

Note: `PGP_KEY_CONFIG_ALREADY_EXISTS` (409) is an addition beyond the spec's Section 7 table, needed so
`POST /api/company-pgp-key-configs` can reject a duplicate `companyCode` with a proper 409 instead of
letting the DB's `UNIQUE` constraint surface as a raw 500.

- [ ] **Step 1: Write the failing domain model test**

```java
package com.system.reportjob.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CompanyPgpKeyConfigTest {

    @Test
    void rejectsBlankCompanyCode() {
        assertThatThrownBy(() -> new CompanyPgpKeyConfig(UUID.randomUUID(), " ", "priv", "pass", "pub", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankBankPrivateKey() {
        assertThatThrownBy(
                        () -> new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", " ", "pass", "pub", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankPassphrase() {
        assertThatThrownBy(
                        () -> new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", " ", "pub", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankCompanyPublicKey() {
        assertThatThrownBy(
                        () -> new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", " ", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsValidValues() {
        UUID id = UUID.randomUUID();
        CompanyPgpKeyConfig config =
                new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv", "pass", "pub", "AB12", true);

        assertThat(config.id()).isEqualTo(id);
        assertThat(config.active()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigTest`
Expected: FAIL — compile error, `CompanyPgpKeyConfig` does not exist yet.

- [ ] **Step 3: Create the domain record**

```java
package com.system.reportjob.domain.model;

import java.util.UUID;

public record CompanyPgpKeyConfig(
        UUID id,
        String companyCode,
        String bankPrivateKeyArmored,
        String bankKeyPassphrase,
        String companyPublicKeyArmored,
        String keyFingerprint,
        boolean active) {
    public CompanyPgpKeyConfig {
        if (companyCode == null || companyCode.isBlank()) {
            throw new IllegalArgumentException("companyCode không được rỗng");
        }
        if (bankPrivateKeyArmored == null || bankPrivateKeyArmored.isBlank()) {
            throw new IllegalArgumentException("bankPrivateKeyArmored không được rỗng");
        }
        if (bankKeyPassphrase == null || bankKeyPassphrase.isBlank()) {
            throw new IllegalArgumentException("bankKeyPassphrase không được rỗng");
        }
        if (companyPublicKeyArmored == null || companyPublicKeyArmored.isBlank()) {
            throw new IllegalArgumentException("companyPublicKeyArmored không được rỗng");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the error codes**

In `src/main/java/com/system/reportjob/domain/exception/ErrorCode.java`, replace the enum constant list:

```java
public enum ErrorCode {
    TASK_NOT_FOUND("task.not_found"),
    TASK_NOT_SCHEDULED("task.not_scheduled"),
    JOB_DEFINITION_NOT_FOUND("job_definition.not_found"),
    JOB_DEFINITION_IN_USE("job_definition.in_use"),
    CRON_INVALID("cron.invalid"),
    VALIDATION_ERROR("validation.error"),
    PGP_KEY_CONFIG_NOT_FOUND("pgp_key_config.not_found"),
    PGP_KEY_CONFIG_ALREADY_EXISTS("pgp_key_config.already_exists"),
    PGP_DECRYPTION_FAILED("pgp.decryption_failed"),
    PGP_SIGNATURE_INVALID("pgp.signature_invalid");
```

- [ ] **Step 6: Add the exception classes**

```java
// src/main/java/com/system/reportjob/domain/exception/PgpKeyConfigNotFoundException.java
package com.system.reportjob.domain.exception;

public class PgpKeyConfigNotFoundException extends BusinessException {
    public PgpKeyConfigNotFoundException(String companyCode) {
        super(ErrorCode.PGP_KEY_CONFIG_NOT_FOUND, companyCode);
    }
}
```

```java
// src/main/java/com/system/reportjob/domain/exception/PgpKeyConfigAlreadyExistsException.java
package com.system.reportjob.domain.exception;

public class PgpKeyConfigAlreadyExistsException extends BusinessException {
    public PgpKeyConfigAlreadyExistsException(String companyCode) {
        super(ErrorCode.PGP_KEY_CONFIG_ALREADY_EXISTS, companyCode);
    }
}
```

```java
// src/main/java/com/system/reportjob/domain/exception/PgpDecryptionFailedException.java
package com.system.reportjob.domain.exception;

public class PgpDecryptionFailedException extends BusinessException {
    public PgpDecryptionFailedException(String companyCode, String reason) {
        super(ErrorCode.PGP_DECRYPTION_FAILED, companyCode, reason);
    }
}
```

```java
// src/main/java/com/system/reportjob/domain/exception/PgpSignatureInvalidException.java
package com.system.reportjob.domain.exception;

public class PgpSignatureInvalidException extends BusinessException {
    public PgpSignatureInvalidException(String companyCode, String reason) {
        super(ErrorCode.PGP_SIGNATURE_INVALID, companyCode, reason);
    }
}
```

- [ ] **Step 7: Add the `statusFor` switch cases**

In `GlobalExceptionHandler.statusFor(ErrorCode)`, replace the switch body:

```java
private HttpStatus statusFor(ErrorCode errorCode) {
    return switch (errorCode) {
        case TASK_NOT_FOUND, JOB_DEFINITION_NOT_FOUND, PGP_KEY_CONFIG_NOT_FOUND -> HttpStatus.NOT_FOUND;
        case JOB_DEFINITION_IN_USE, TASK_NOT_SCHEDULED, PGP_KEY_CONFIG_ALREADY_EXISTS -> HttpStatus.CONFLICT;
        case CRON_INVALID, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        case PGP_DECRYPTION_FAILED, PGP_SIGNATURE_INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
}
```

- [ ] **Step 8: Add the i18n messages**

Append to `src/main/resources/messages.properties`:

```properties
pgp_key_config.not_found=Không tìm thấy cấu hình PGP key đang active cho company {0}
pgp_key_config.already_exists=Company {0} đã có cấu hình PGP key, dùng PUT để cập nhật thay vì tạo mới
pgp.decryption_failed=Giải mã file PGP thất bại cho company {0}: {1}
pgp.signature_invalid=Xác thực chữ ký PGP thất bại cho company {0}: {1}
```

Append to `src/main/resources/messages_en.properties`:

```properties
pgp_key_config.not_found=No active PGP key config found for company {0}
pgp_key_config.already_exists=Company {0} already has a PGP key config, use PUT to update instead
pgp.decryption_failed=PGP file decryption failed for company {0}: {1}
pgp.signature_invalid=PGP signature verification failed for company {0}: {1}
```

- [ ] **Step 9: Write the failing `GlobalExceptionHandler` tests**

Add to `src/test/java/com/system/reportjob/infrastructure/common/GlobalExceptionHandlerTest.java` (new
imports: `com.system.reportjob.domain.exception.PgpDecryptionFailedException`,
`com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException`):

```java
@Test
void mapsPgpKeyConfigNotFoundTo404() {
    ResponseEntity<ApiResponse<Object>> response =
            handler.handleBusinessException(new PgpKeyConfigNotFoundException("FPT_SOFTWARE"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().message()).contains("FPT_SOFTWARE");
}

@Test
void mapsPgpDecryptionFailedTo422() {
    ResponseEntity<ApiResponse<Object>> response = handler.handleBusinessException(
            new PgpDecryptionFailedException("FPT_SOFTWARE", "sai passphrase"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody().message()).contains("FPT_SOFTWARE").contains("sai passphrase");
}
```

- [ ] **Step 10: Run test to verify it fails**

Run: `mvn -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — compile error (new exception classes referenced before Step 6 is picked up by the
build) or, if Step 6 already applied, PASS is expected instead; run this after Step 6+8 to confirm the
switch/messages are wired correctly.

- [ ] **Step 11: Run the full test file again to verify everything passes**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigTest,GlobalExceptionHandlerTest`
Expected: PASS (all tests green).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/system/reportjob/domain src/main/java/com/system/reportjob/infrastructure/common/GlobalExceptionHandler.java src/main/resources/messages.properties src/main/resources/messages_en.properties src/test/java/com/system/reportjob/domain/model/CompanyPgpKeyConfigTest.java src/test/java/com/system/reportjob/infrastructure/common/GlobalExceptionHandlerTest.java
git commit -m "feat: add CompanyPgpKeyConfig domain model and PGP error codes"
```

---

### Task 3: `PgpKeyMaterialCipher` (AES-256-GCM envelope) + master key config

**Files:**
- Create: `src/main/java/com/system/reportjob/infrastructure/security/pgp/PgpKeyMaterialCipher.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/system/reportjob/infrastructure/security/pgp/PgpKeyMaterialCipherTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (only JDK `javax.crypto`).
- Produces: `PgpKeyMaterialCipher.seal(String plaintext) -> String` and `unseal(String sealedBase64) ->
  String`, injected by Task 4's `CompanyPgpKeyConfigRepositoryAdapter`.

Deviation from spec wording: spec §5 describes the seal/unseal step happening "ở persistence adapter" —
this task extracts it into its own `@Component` (`PgpKeyMaterialCipher`) so it is independently unit
testable without a database, and Task 4 injects it. Same outcome, smaller unit.

- [ ] **Step 1: Write the failing round-trip test**

```java
package com.system.reportjob.infrastructure.security.pgp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class PgpKeyMaterialCipherTest {

    private static final String MASTER_KEY_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void sealThenUnsealReturnsOriginalPlaintext() {
        PgpKeyMaterialCipher cipher = new PgpKeyMaterialCipher(MASTER_KEY_BASE64);

        String sealed = cipher.seal("-----BEGIN PGP PRIVATE KEY BLOCK-----\nsecret\n-----END-----");

        assertThat(sealed).doesNotContain("secret");
        assertThat(cipher.unseal(sealed)).isEqualTo("-----BEGIN PGP PRIVATE KEY BLOCK-----\nsecret\n-----END-----");
    }

    @Test
    void sealIsNonDeterministic() {
        PgpKeyMaterialCipher cipher = new PgpKeyMaterialCipher(MASTER_KEY_BASE64);

        assertThat(cipher.seal("same-plaintext")).isNotEqualTo(cipher.seal("same-plaintext"));
    }

    @Test
    void rejectsMasterKeyThatIsNot32BytesAfterDecoding() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new PgpKeyMaterialCipher(shortKey)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=PgpKeyMaterialCipherTest`
Expected: FAIL — `PgpKeyMaterialCipher` does not exist yet.

- [ ] **Step 3: Implement `PgpKeyMaterialCipher`**

```java
package com.system.reportjob.infrastructure.security.pgp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seal/unseal PGP private key + passphrase trước khi lưu DB, dùng AES-256-GCM với 1 master key đọc
 * từ app.pgp.master-key. Không dùng cho company_public_key_armored (không nhạy cảm). Xem
 * docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md, Section 5.
 */
@Component
public class PgpKeyMaterialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final SecretKeySpec masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public PgpKeyMaterialCipher(@Value("${app.pgp.master-key}") String masterKeyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(masterKeyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("app.pgp.master-key phải là 256-bit (32 byte) sau khi decode base64,"
                    + " hiện tại là " + keyBytes.length + " byte");
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không seal được PGP key material", e);
        }
    }

    public String unseal(String sealedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(sealedBase64);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            byte[] ciphertext = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không unseal được PGP key material", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=PgpKeyMaterialCipherTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Add `app.pgp` config block to `application.yml`**

In `src/main/resources/application.yml`, inside the `app:` block, after the `payroll:` block, add:

```yaml
  pgp:
    # 256-bit key, base64. CHỈ dùng giá trị mẫu này cho local dev/test — đổi qua biến môi trường
    # APP_PGP_MASTER-KEY (hoặc override application.yml) trước khi dùng ở môi trường thật.
    master-key: 6XOHGExLShOjstzY7wYHtfAm+3NZppiNiCrNbqswRxo=
    # Thư mục ghi file plaintext tạm sau khi decrypt - xoá ngay sau khi JobAction dùng xong.
    temp-dir: ${java.io.tmpdir}
```

- [ ] **Step 6: Verify the app still starts with the new config**

Run: `mvn -q compile` (full boot smoke test happens naturally in Task 4/5's Spring-context tests)
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/system/reportjob/infrastructure/security/pgp/PgpKeyMaterialCipher.java src/main/resources/application.yml src/test/java/com/system/reportjob/infrastructure/security/pgp/PgpKeyMaterialCipherTest.java
git commit -m "feat: add AES-GCM PgpKeyMaterialCipher for sealing PGP key material"
```

---

### Task 4: Migration, entity, repository port + adapter for `company_pgp_key_config`

**Files:**
- Create: `src/main/resources/db/migration/V12__create_company_pgp_key_config.sql`
- Create: `src/main/java/com/system/reportjob/infrastructure/persistence/entity/CompanyPgpKeyConfigEntity.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/persistence/repository/CompanyPgpKeyConfigJpaRepository.java`
- Create: `src/main/java/com/system/reportjob/usecase/ports/out/PgpKeyConfigRepositoryPort.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/persistence/adapter/CompanyPgpKeyConfigRepositoryAdapter.java`
- Test: `src/test/java/com/system/reportjob/infrastructure/persistence/adapter/CompanyPgpKeyConfigRepositoryAdapterTest.java`

**Interfaces:**
- Consumes: `CompanyPgpKeyConfig` (Task 2), `PgpKeyMaterialCipher.seal/unseal` (Task 3).
- Produces: `PgpKeyConfigRepositoryPort` — `save(CompanyPgpKeyConfig) -> CompanyPgpKeyConfig`,
  `findByCompanyCode(String) -> Optional<CompanyPgpKeyConfig>`, `findAll() -> List<CompanyPgpKeyConfig>`,
  `delete(String companyCode)` — used by Task 6 (`PgpFileDecryptionService`) and Task 7
  (`CompanyPgpKeyConfigService`).

Deviation from spec wording: the spec's port sketch (§3) had a separate `findActiveByCompanyCode` and a
`deactivate` method. This plan collapses that to one `findByCompanyCode` (the caller filters on
`.active()`) and drops `deactivate` in favor of the existing soft-delete convention (`delete`, backed by
`@SQLDelete`/`@SQLRestriction` on `is_deleted`, exactly like `JobDefinitionEntity`/`TaskEntity`) — the
`active` boolean stays a separately toggleable field via `PUT` (pause a company's flow without deleting
its config), while `DELETE` removes the row from view entirely. This keeps the port surface smaller and
consistent with the rest of the codebase.

- [ ] **Step 1: Add the migration**

```sql
-- src/main/resources/db/migration/V12__create_company_pgp_key_config.sql
-- Cấu hình PGP key theo company, dùng bởi DecryptCompanyFileUseCase để decrypt + verify file công
-- ty gửi sang trước khi các JobAction (BANK_SALARY_PAYROLL, ...) đọc file.
-- Xem docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md.
CREATE TABLE company_pgp_key_config (
    id                             UUID PRIMARY KEY,
    company_code                   VARCHAR(30) NOT NULL,
    bank_private_key_encrypted     TEXT NOT NULL,
    bank_key_passphrase_encrypted  TEXT NOT NULL,
    company_public_key_armored     TEXT NOT NULL,
    key_fingerprint                VARCHAR(64),
    active                         BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted                     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_company_pgp_key_config_company UNIQUE (company_code)
);
```

- [ ] **Step 2: Write the failing adapter test**

```java
package com.system.reportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.persistence.repository.CompanyPgpKeyConfigJpaRepository;
import com.system.reportjob.infrastructure.security.pgp.PgpKeyMaterialCipher;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import({CompanyPgpKeyConfigRepositoryAdapter.class, PgpKeyMaterialCipher.class})
class CompanyPgpKeyConfigRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.pgp.master-key", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    CompanyPgpKeyConfigRepositoryAdapter adapter;

    @Autowired
    CompanyPgpKeyConfigJpaRepository jpaRepository;

    @Test
    void savesAndReloadsWithKeyMaterialDecryptedOnRead() {
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(
                UUID.randomUUID(), "FPT_SOFTWARE", "priv-key-armored", "s3cr3t", "pub-key-armored", null, true);

        adapter.save(config);

        assertThat(adapter.findByCompanyCode("FPT_SOFTWARE")).contains(config);
    }

    @Test
    void keyMaterialIsEncryptedAtRestInTheDatabase() {
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(
                UUID.randomUUID(), "FPT_SOFTWARE", "priv-key-armored", "s3cr3t", "pub-key-armored", null, true);

        adapter.save(config);

        var entity = jpaRepository.findByCompanyCode("FPT_SOFTWARE").orElseThrow();
        assertThat(entity.getBankPrivateKeyEncrypted()).doesNotContain("priv-key-armored");
        assertThat(entity.getBankKeyPassphraseEncrypted()).doesNotContain("s3cr3t");
        assertThat(entity.getCompanyPublicKeyArmored()).isEqualTo("pub-key-armored");
    }

    @Test
    void updateOverwritesTheExistingRowInsteadOfInsertingANewOne() {
        UUID id = UUID.randomUUID();
        adapter.save(new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv-v1", "pass-v1", "pub-v1", null, true));

        adapter.save(new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv-v2", "pass-v2", "pub-v2", null, true));

        assertThat(jpaRepository.findAll()).hasSize(1);
        assertThat(adapter.findByCompanyCode("FPT_SOFTWARE")).get().extracting(CompanyPgpKeyConfig::bankPrivateKeyArmored)
                .isEqualTo("priv-v2");
    }

    @Test
    void deletedConfigIsNoLongerFound() {
        adapter.save(new CompanyPgpKeyConfig(
                UUID.randomUUID(), "FPT_SOFTWARE", "priv-key-armored", "s3cr3t", "pub-key-armored", null, true));

        adapter.delete("FPT_SOFTWARE");

        assertThat(adapter.findByCompanyCode("FPT_SOFTWARE")).isEmpty();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigRepositoryAdapterTest`
Expected: FAIL — `CompanyPgpKeyConfigRepositoryAdapter` and friends do not exist yet (requires Docker
for Testcontainers, per this repo's existing persistence tests).

- [ ] **Step 4: Create the entity**

```java
package com.system.reportjob.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "company_pgp_key_config")
@SQLDelete(sql = "UPDATE company_pgp_key_config SET is_deleted = true WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class CompanyPgpKeyConfigEntity extends BaseEntity {
    @Column(name = "company_code", nullable = false, unique = true)
    private String companyCode;

    @Column(name = "bank_private_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String bankPrivateKeyEncrypted;

    @Column(name = "bank_key_passphrase_encrypted", nullable = false, columnDefinition = "TEXT")
    private String bankKeyPassphraseEncrypted;

    @Column(name = "company_public_key_armored", nullable = false, columnDefinition = "TEXT")
    private String companyPublicKeyArmored;

    @Column(name = "key_fingerprint")
    private String keyFingerprint;

    @Column(name = "active", nullable = false)
    private boolean active;
}
```

- [ ] **Step 5: Create the JPA repository**

```java
package com.system.reportjob.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.system.reportjob.infrastructure.persistence.entity.CompanyPgpKeyConfigEntity;

public interface CompanyPgpKeyConfigJpaRepository extends JpaRepository<CompanyPgpKeyConfigEntity, UUID> {
    Optional<CompanyPgpKeyConfigEntity> findByCompanyCode(String companyCode);
}
```

- [ ] **Step 6: Create the repository port**

```java
package com.system.reportjob.usecase.ports.out;

import java.util.List;
import java.util.Optional;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface PgpKeyConfigRepositoryPort {
    CompanyPgpKeyConfig save(CompanyPgpKeyConfig config);

    Optional<CompanyPgpKeyConfig> findByCompanyCode(String companyCode);

    List<CompanyPgpKeyConfig> findAll();

    void delete(String companyCode);
}
```

- [ ] **Step 7: Create the adapter**

```java
package com.system.reportjob.infrastructure.persistence.adapter;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.persistence.entity.CompanyPgpKeyConfigEntity;
import com.system.reportjob.infrastructure.persistence.repository.CompanyPgpKeyConfigJpaRepository;
import com.system.reportjob.infrastructure.security.pgp.PgpKeyMaterialCipher;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Component
public class CompanyPgpKeyConfigRepositoryAdapter implements PgpKeyConfigRepositoryPort {

    private final CompanyPgpKeyConfigJpaRepository jpaRepository;
    private final PgpKeyMaterialCipher cipher;

    public CompanyPgpKeyConfigRepositoryAdapter(
            CompanyPgpKeyConfigJpaRepository jpaRepository, PgpKeyMaterialCipher cipher) {
        this.jpaRepository = jpaRepository;
        this.cipher = cipher;
    }

    @Override
    public CompanyPgpKeyConfig save(CompanyPgpKeyConfig config) {
        CompanyPgpKeyConfigEntity entity = jpaRepository
                .findByCompanyCode(config.companyCode())
                .orElseGet(CompanyPgpKeyConfigEntity::new);
        entity.setId(config.id());
        entity.setCompanyCode(config.companyCode());
        entity.setBankPrivateKeyEncrypted(cipher.seal(config.bankPrivateKeyArmored()));
        entity.setBankKeyPassphraseEncrypted(cipher.seal(config.bankKeyPassphrase()));
        entity.setCompanyPublicKeyArmored(config.companyPublicKeyArmored());
        entity.setKeyFingerprint(config.keyFingerprint());
        entity.setActive(config.active());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    public Optional<CompanyPgpKeyConfig> findByCompanyCode(String companyCode) {
        return jpaRepository.findByCompanyCode(companyCode).map(this::toDomain);
    }

    @Override
    public List<CompanyPgpKeyConfig> findAll() {
        return jpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public void delete(String companyCode) {
        jpaRepository.findByCompanyCode(companyCode).ifPresent(entity -> jpaRepository.deleteById(entity.getId()));
    }

    private CompanyPgpKeyConfig toDomain(CompanyPgpKeyConfigEntity entity) {
        return new CompanyPgpKeyConfig(
                entity.getId(),
                entity.getCompanyCode(),
                cipher.unseal(entity.getBankPrivateKeyEncrypted()),
                cipher.unseal(entity.getBankKeyPassphraseEncrypted()),
                entity.getCompanyPublicKeyArmored(),
                entity.getKeyFingerprint(),
                entity.isActive());
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigRepositoryAdapterTest`
Expected: PASS (4 tests). Requires Docker running locally (Testcontainers), same as every other test
under `infrastructure/persistence`.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V12__create_company_pgp_key_config.sql src/main/java/com/system/reportjob/infrastructure/persistence/entity/CompanyPgpKeyConfigEntity.java src/main/java/com/system/reportjob/infrastructure/persistence/repository/CompanyPgpKeyConfigJpaRepository.java src/main/java/com/system/reportjob/usecase/ports/out/PgpKeyConfigRepositoryPort.java src/main/java/com/system/reportjob/infrastructure/persistence/adapter/CompanyPgpKeyConfigRepositoryAdapter.java src/test/java/com/system/reportjob/infrastructure/persistence/adapter/CompanyPgpKeyConfigRepositoryAdapterTest.java
git commit -m "feat: add company_pgp_key_config persistence (migration, entity, adapter)"
```

---

### Task 5: PGP decrypt+verify adapter (Bouncy Castle)

**Files:**
- Create: `src/main/java/com/system/reportjob/usecase/ports/out/PgpDecryptionGatewayPort.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/security/pgp/BouncyCastlePgpDecryptionAdapter.java`
- Test: `src/test/java/com/system/reportjob/infrastructure/security/pgp/PgpTestFixtures.java` (test
  support, not a `@Test` class itself)
- Test: `src/test/java/com/system/reportjob/infrastructure/security/pgp/BouncyCastlePgpDecryptionAdapterTest.java`

**Interfaces:**
- Consumes: `CompanyPgpKeyConfig` (Task 2).
- Produces: `PgpDecryptionGatewayPort.decryptAndVerify(Path encryptedFile, CompanyPgpKeyConfig keyConfig)
  -> Path` — used by Task 6's `PgpFileDecryptionService`.

Deviation from spec wording: spec §3 sketched `decryptAndVerify(Path, CompanyPgpKeyConfig, Path
outputDir)` with the caller choosing the output directory. This plan drops the `outputDir` parameter —
the adapter owns `app.pgp.temp-dir` itself via `@Value`, matching this codebase's convention that only
`infrastructure/` classes read `@Value` config (no `usecase/service` class does today; see
`JobDefinitionService` for comparison). `PgpFileDecryptionService` (Task 6) stays config-free.

This adapter uses Bouncy Castle's **lightweight** `org.bouncycastle.openpgp.operator.bc.*` API
throughout (`BcPGPDigestCalculatorProvider`, `BcPBESecretKeyDecryptorBuilder`,
`BcPublicKeyDataDecryptorFactory`, `BcPGPContentVerifierBuilderProvider`, `BcKeyFingerprintCalculator`) —
no `java.security.Security.addProvider(...)` registration is needed anywhere. The traversal pattern
(encrypted → optionally-compressed → one-pass-signature → literal-data → signature) mirrors Bouncy
Castle's own `org.bouncycastle.openpgp.examples.KeyBasedFileProcessor`, with **mandatory** signature
verification added (the example itself treats it as optional; this adapter never returns decrypted
content without a verified signature — see the plan's Global Constraints).

- [ ] **Step 1: Write the PGP test fixtures (test support, no assertions)**

```java
package com.system.reportjob.infrastructure.security.pgp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Date;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator;

/**
 * Sinh key pair PGP test và encrypt+sign một payload - mô phỏng phía "company" (họ encrypt bằng
 * public key ngân hàng, sign bằng private key của họ) để test
 * {@link BouncyCastlePgpDecryptionAdapter} (đóng vai "ngân hàng", decrypt + verify). Chỉ dùng
 * trong test, không phải production code.
 */
final class PgpTestFixtures {

    private PgpTestFixtures() {}

    record PgpKeyPairArmored(String publicKeyArmored, String secretKeyArmored) {}

    static PgpKeyPairArmored generateKeyPair(String userId, char[] passphrase) throws Exception {
        RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
        generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 80));
        AsymmetricCipherKeyPair keyPair = generator.generateKeyPair();
        PGPKeyPair pgpKeyPair = new BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, new Date());

        PGPDigestCalculator sha1Calc = new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1);
        PBESecretKeyEncryptor keyEncryptor =
                new BcPBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1Calc).build(passphrase);

        PGPKeyRingGenerator keyRingGenerator = new PGPKeyRingGenerator(
                org.bouncycastle.openpgp.PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                userId,
                sha1Calc,
                null,
                null,
                new BcPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                keyEncryptor);

        return new PgpKeyPairArmored(
                armor(keyRingGenerator.generatePublicKeyRing().getEncoded()),
                armor(keyRingGenerator.generateSecretKeyRing().getEncoded()));
    }

    static byte[] encryptAndSign(
            byte[] plaintext,
            String fileName,
            String signerSecretKeyArmored,
            char[] signerPassphrase,
            String recipientPublicKeyArmored)
            throws Exception {
        PGPSecretKeyRing signerKeyRing = new PGPSecretKeyRing(
                PGPUtil.getDecoderStream(armoredStream(signerSecretKeyArmored)), new BcKeyFingerprintCalculator());
        PGPSecretKey signerSecretKey = signerKeyRing.getSecretKey();
        PGPPrivateKey signerPrivateKey = signerSecretKey.extractPrivateKey(
                new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(signerPassphrase));

        PGPPublicKeyRing recipientKeyRing = new PGPPublicKeyRing(
                PGPUtil.getDecoderStream(armoredStream(recipientPublicKeyArmored)), new BcKeyFingerprintCalculator());
        PGPPublicKey recipientEncryptionKey = recipientKeyRing.getPublicKey();

        PGPSignatureGenerator signatureGenerator = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(signerSecretKey.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256));
        signatureGenerator.init(org.bouncycastle.openpgp.PGPSignature.BINARY_DOCUMENT, signerPrivateKey);

        ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
        PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
                new BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true));
        encryptedDataGenerator.addMethod(new BcPublicKeyKeyEncryptionMethodGenerator(recipientEncryptionKey));

        try (ArmoredOutputStream armoredOut = new ArmoredOutputStream(encryptedOut);
                OutputStream cipherOut = encryptedDataGenerator.open(armoredOut, new byte[1 << 16])) {
            PGPCompressedDataGenerator compressedDataGenerator =
                    new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
            try (OutputStream compressedOut = compressedDataGenerator.open(cipherOut)) {
                signatureGenerator.generateOnePassVersion(false).encode(compressedOut);

                PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
                try (OutputStream literalOut = literalDataGenerator.open(
                        compressedOut, PGPLiteralData.BINARY, fileName, plaintext.length, new Date())) {
                    literalOut.write(plaintext);
                    signatureGenerator.update(plaintext);
                }
                signatureGenerator.generate().encode(compressedOut);
            }
        }
        return encryptedOut.toByteArray();
    }

    private static String armor(byte[] encoded) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
            armored.write(encoded);
        }
        return out.toString(StandardCharsets.US_ASCII);
    }

    private static ByteArrayInputStream armoredStream(String armored) {
        return new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII));
    }
}
```

- [ ] **Step 2: Write the failing adapter test**

```java
package com.system.reportjob.infrastructure.security.pgp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.system.reportjob.domain.exception.PgpDecryptionFailedException;
import com.system.reportjob.domain.exception.PgpSignatureInvalidException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.security.pgp.PgpTestFixtures.PgpKeyPairArmored;

class BouncyCastlePgpDecryptionAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void decryptsAndVerifiesAFileEncryptedAndSignedByTheCompany() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] plaintext = "employeeId,accountNumber,fullName,salaryAmount\nE1,123,Nguyen Van A,1000000\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                plaintext,
                "payroll.csv",
                companyKeyPair.secretKeyArmored(),
                "company-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig keyConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        Path decryptedFile = adapter.decryptAndVerify(encryptedFile, keyConfig);

        assertThat(Files.readAllBytes(decryptedFile)).isEqualTo(plaintext);
    }

    @Test
    void throwsDecryptionFailedWhenThePassphraseIsWrong() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                "data".getBytes(StandardCharsets.UTF_8),
                "payroll.csv",
                companyKeyPair.secretKeyArmored(),
                "company-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig wrongPassphraseConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "WRONG-PASSPHRASE",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, wrongPassphraseConfig))
                .isInstanceOf(PgpDecryptionFailedException.class);
    }

    @Test
    void throwsSignatureInvalidWhenSignedByAnUnrelatedKey() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        PgpKeyPairArmored impostorKeyPair =
                PgpTestFixtures.generateKeyPair("impostor@evil.test", "impostor-pass".toCharArray());
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                "data".getBytes(StandardCharsets.UTF_8),
                "payroll.csv",
                impostorKeyPair.secretKeyArmored(),
                "impostor-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig keyConfigExpectingCompanyKey = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());

        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, keyConfigExpectingCompanyKey))
                .isInstanceOf(PgpSignatureInvalidException.class);
    }

    @Test
    void deletesTheDecryptedFileWhenSignatureVerificationFails() throws Exception {
        PgpKeyPairArmored bankKeyPair = PgpTestFixtures.generateKeyPair("bank@tpbank.test", "bank-pass".toCharArray());
        PgpKeyPairArmored impostorKeyPair =
                PgpTestFixtures.generateKeyPair("impostor@evil.test", "impostor-pass".toCharArray());
        PgpKeyPairArmored companyKeyPair =
                PgpTestFixtures.generateKeyPair("fpt@fpt.test", "company-pass".toCharArray());
        byte[] encrypted = PgpTestFixtures.encryptAndSign(
                "data".getBytes(StandardCharsets.UTF_8),
                "payroll.csv",
                impostorKeyPair.secretKeyArmored(),
                "impostor-pass".toCharArray(),
                bankKeyPair.publicKeyArmored());
        Path encryptedFile = tempDir.resolve("payroll.csv.pgp");
        Files.write(encryptedFile, encrypted);
        CompanyPgpKeyConfig keyConfig = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                "FPT_SOFTWARE",
                bankKeyPair.secretKeyArmored(),
                "bank-pass",
                companyKeyPair.publicKeyArmored(),
                null,
                true);
        BouncyCastlePgpDecryptionAdapter adapter = new BouncyCastlePgpDecryptionAdapter(tempDir.toString());
        long filesBefore = Files.list(tempDir).count();

        assertThatThrownBy(() -> adapter.decryptAndVerify(encryptedFile, keyConfig))
                .isInstanceOf(PgpSignatureInvalidException.class);

        assertThat(Files.list(tempDir).count()).isEqualTo(filesBefore);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=BouncyCastlePgpDecryptionAdapterTest`
Expected: FAIL — `BouncyCastlePgpDecryptionAdapter` does not exist yet.

- [ ] **Step 4: Create the port**

```java
package com.system.reportjob.usecase.ports.out;

import java.nio.file.Path;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface PgpDecryptionGatewayPort {
    Path decryptAndVerify(Path encryptedFile, CompanyPgpKeyConfig keyConfig);
}
```

- [ ] **Step 5: Implement `BouncyCastlePgpDecryptionAdapter`**

```java
package com.system.reportjob.infrastructure.security.pgp;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.UUID;

import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPOnePassSignature;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.exception.PgpDecryptionFailedException;
import com.system.reportjob.domain.exception.PgpSignatureInvalidException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.out.PgpDecryptionGatewayPort;

/**
 * Decrypt + verify chữ ký file PGP bằng Bouncy Castle, dùng API lightweight thuần
 * org.bouncycastle.openpgp.operator.bc.* (không cần đăng ký JCE Security provider). Traversal dựa
 * trên pattern chuẩn org.bouncycastle.openpgp.examples.KeyBasedFileProcessor, với verify signature
 * BẮT BUỘC (xem docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md, Section 5).
 */
@Component
public class BouncyCastlePgpDecryptionAdapter implements PgpDecryptionGatewayPort {

    private final Path tempDir;

    public BouncyCastlePgpDecryptionAdapter(@Value("${app.pgp.temp-dir:${java.io.tmpdir}}") String tempDir) {
        this.tempDir = Path.of(tempDir);
    }

    @Override
    public Path decryptAndVerify(Path encryptedFile, CompanyPgpKeyConfig keyConfig) {
        String companyCode = keyConfig.companyCode();
        try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(encryptedFile))) {
            PGPObjectFactory pgpFactory =
                    new PGPObjectFactory(PGPUtil.getDecoderStream(fileIn), new BcKeyFingerprintCalculator());

            Object firstObject = pgpFactory.nextObject();
            PGPEncryptedDataList encryptedDataList = firstObject instanceof PGPEncryptedDataList list
                    ? list
                    : (PGPEncryptedDataList) pgpFactory.nextObject();

            PGPSecretKeyRingCollection secretKeyRings = new PGPSecretKeyRingCollection(
                    PGPUtil.getDecoderStream(armoredStream(keyConfig.bankPrivateKeyArmored())),
                    new BcKeyFingerprintCalculator());

            PGPPrivateKey privateKey = null;
            PGPPublicKeyEncryptedData encryptedData = null;
            Iterator<PGPEncryptedData> encryptedObjects = encryptedDataList.getEncryptedDataObjects();
            while (privateKey == null && encryptedObjects.hasNext()) {
                PGPPublicKeyEncryptedData candidate = (PGPPublicKeyEncryptedData) encryptedObjects.next();
                PGPSecretKey secretKey = secretKeyRings.getSecretKey(candidate.getKeyID());
                if (secretKey != null) {
                    privateKey = secretKey.extractPrivateKey(new BcPBESecretKeyDecryptorBuilder(
                                    new BcPGPDigestCalculatorProvider())
                            .build(keyConfig.bankKeyPassphrase().toCharArray()));
                    encryptedData = candidate;
                }
            }
            if (privateKey == null || encryptedData == null) {
                throw new PgpDecryptionFailedException(
                        companyCode, "Không tìm thấy private key khớp với file (key ID không trùng)");
            }

            InputStream clearStream = encryptedData.getDataStream(new BcPublicKeyDataDecryptorFactory(privateKey));
            PGPObjectFactory plainFactory = new PGPObjectFactory(clearStream, new BcKeyFingerprintCalculator());
            Object message = plainFactory.nextObject();
            if (message instanceof PGPCompressedData compressedData) {
                plainFactory = new PGPObjectFactory(compressedData.getDataStream(), new BcKeyFingerprintCalculator());
                message = plainFactory.nextObject();
            }

            if (!(message instanceof PGPOnePassSignatureList onePassSignatureList) || onePassSignatureList.isEmpty()) {
                throw new PgpSignatureInvalidException(
                        companyCode, "File không có chữ ký PGP đi kèm (thiếu one-pass signature)");
            }
            PGPOnePassSignature onePassSignature = onePassSignatureList.get(0);

            PGPPublicKeyRingCollection publicKeyRings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(armoredStream(keyConfig.companyPublicKeyArmored())),
                    new BcKeyFingerprintCalculator());
            PGPPublicKey signerKey = publicKeyRings.getPublicKey(onePassSignature.getKeyID());
            if (signerKey == null) {
                throw new PgpSignatureInvalidException(
                        companyCode, "Không tìm thấy public key của company để verify chữ ký (key ID không trùng)");
            }
            onePassSignature.init(new BcPGPContentVerifierBuilderProvider(), signerKey);

            message = plainFactory.nextObject();
            if (!(message instanceof PGPLiteralData literalData)) {
                throw new PgpDecryptionFailedException(companyCode, "Định dạng PGP không hợp lệ: thiếu literal data");
            }

            Files.createDirectories(tempDir);
            Path decryptedFile = tempDir.resolve(UUID.randomUUID() + ".decrypted");
            try (InputStream literalIn = literalData.getInputStream();
                    OutputStream fileOut = Files.newOutputStream(decryptedFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = literalIn.read(buffer)) >= 0) {
                    onePassSignature.update(buffer, 0, read);
                    fileOut.write(buffer, 0, read);
                }
            }
            setOwnerOnlyPermissionsIfSupported(decryptedFile);

            Object signatureObject = plainFactory.nextObject();
            if (!(signatureObject instanceof PGPSignatureList signatureList) || signatureList.isEmpty()) {
                Files.deleteIfExists(decryptedFile);
                throw new PgpSignatureInvalidException(
                        companyCode, "File không có chữ ký PGP hợp lệ đi kèm (thiếu signature packet)");
            }
            PGPSignature signature = signatureList.get(0);
            boolean verified;
            try {
                verified = onePassSignature.verify(signature);
            } catch (PGPException e) {
                Files.deleteIfExists(decryptedFile);
                throw new PgpSignatureInvalidException(companyCode, "Lỗi khi verify chữ ký: " + e.getMessage());
            }
            if (!verified) {
                Files.deleteIfExists(decryptedFile);
                throw new PgpSignatureInvalidException(
                        companyCode, "Chữ ký PGP không khớp - file có thể đã bị sửa đổi hoặc giả mạo");
            }

            return decryptedFile;
        } catch (IOException | PGPException e) {
            throw new PgpDecryptionFailedException(companyCode, e.getMessage());
        }
    }

    private static InputStream armoredStream(String armored) {
        return new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII));
    }

    private static void setOwnerOnlyPermissionsIfSupported(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Hệ điều hành không hỗ trợ POSIX permission (vd Windows) - bỏ qua, không phải lỗi.
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=BouncyCastlePgpDecryptionAdapterTest`
Expected: PASS (4 tests). If any Bouncy Castle method call fails to compile because the pinned `1.80`
API differs from what's written here, consult the javadoc for the installed
`bcpg-jdk18on`/`bcprov-jdk18on` jars (`mvn dependency:build-classpath` + `javap`) for the equivalent
call — do not change the security semantics (signature verification must remain mandatory and must run
before the decrypted file is returned).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/system/reportjob/usecase/ports/out/PgpDecryptionGatewayPort.java src/main/java/com/system/reportjob/infrastructure/security/pgp/BouncyCastlePgpDecryptionAdapter.java src/test/java/com/system/reportjob/infrastructure/security/pgp/PgpTestFixtures.java src/test/java/com/system/reportjob/infrastructure/security/pgp/BouncyCastlePgpDecryptionAdapterTest.java
git commit -m "feat: add Bouncy Castle PGP decrypt+verify adapter"
```

---

### Task 6: `DecryptCompanyFileUseCase` + `PgpFileDecryptionService`

**Files:**
- Create: `src/main/java/com/system/reportjob/usecase/ports/in/DecryptCompanyFileUseCase.java`
- Create: `src/main/java/com/system/reportjob/usecase/service/PgpFileDecryptionService.java`
- Test: `src/test/java/com/system/reportjob/usecase/service/PgpFileDecryptionServiceTest.java`

**Interfaces:**
- Consumes: `PgpKeyConfigRepositoryPort.findByCompanyCode` (Task 4),
  `PgpDecryptionGatewayPort.decryptAndVerify` (Task 5).
- Produces: `DecryptCompanyFileUseCase.decryptFile(String companyCode, Path encryptedFilePath) -> Path`
  — used by Task 9's `PayrollJobAction`.

- [ ] **Step 1: Write the failing service test**

```java
package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.out.PgpDecryptionGatewayPort;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

class PgpFileDecryptionServiceTest {

    private final PgpKeyConfigRepositoryPort keyConfigRepositoryPort = mock(PgpKeyConfigRepositoryPort.class);
    private final PgpDecryptionGatewayPort decryptionGatewayPort = mock(PgpDecryptionGatewayPort.class);
    private final PgpFileDecryptionService service =
            new PgpFileDecryptionService(keyConfigRepositoryPort, decryptionGatewayPort);

    @Test
    void decryptsUsingTheActiveKeyConfigForTheCompany() {
        CompanyPgpKeyConfig config =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true);
        when(keyConfigRepositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(config));
        Path decrypted = Path.of("/tmp/decrypted.csv");
        when(decryptionGatewayPort.decryptAndVerify(any(), any())).thenReturn(decrypted);

        Path result = service.decryptFile("FPT_SOFTWARE", Path.of("/tmp/encrypted.csv.pgp"));

        assertThat(result).isEqualTo(decrypted);
        verify(decryptionGatewayPort).decryptAndVerify(Path.of("/tmp/encrypted.csv.pgp"), config);
    }

    @Test
    void throwsWhenNoKeyConfigExistsForTheCompany() {
        when(keyConfigRepositoryPort.findByCompanyCode("UNKNOWN_CO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decryptFile("UNKNOWN_CO", Path.of("/tmp/x.pgp")))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }

    @Test
    void throwsWhenTheKeyConfigIsInactive() {
        CompanyPgpKeyConfig inactiveConfig =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, false);
        when(keyConfigRepositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(inactiveConfig));

        assertThatThrownBy(() -> service.decryptFile("FPT_SOFTWARE", Path.of("/tmp/x.pgp")))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=PgpFileDecryptionServiceTest`
Expected: FAIL — `DecryptCompanyFileUseCase`/`PgpFileDecryptionService` do not exist yet.

- [ ] **Step 3: Create the use case interface**

```java
package com.system.reportjob.usecase.ports.in;

import java.nio.file.Path;

public interface DecryptCompanyFileUseCase {
    Path decryptFile(String companyCode, Path encryptedFilePath);
}
```

- [ ] **Step 4: Implement the service**

```java
package com.system.reportjob.usecase.service;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.DecryptCompanyFileUseCase;
import com.system.reportjob.usecase.ports.out.PgpDecryptionGatewayPort;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Service
public class PgpFileDecryptionService implements DecryptCompanyFileUseCase {

    private final PgpKeyConfigRepositoryPort keyConfigRepositoryPort;
    private final PgpDecryptionGatewayPort decryptionGatewayPort;

    public PgpFileDecryptionService(
            PgpKeyConfigRepositoryPort keyConfigRepositoryPort, PgpDecryptionGatewayPort decryptionGatewayPort) {
        this.keyConfigRepositoryPort = keyConfigRepositoryPort;
        this.decryptionGatewayPort = decryptionGatewayPort;
    }

    @Override
    public Path decryptFile(String companyCode, Path encryptedFilePath) {
        CompanyPgpKeyConfig keyConfig = keyConfigRepositoryPort
                .findByCompanyCode(companyCode)
                .filter(CompanyPgpKeyConfig::active)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        return decryptionGatewayPort.decryptAndVerify(encryptedFilePath, keyConfig);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=PgpFileDecryptionServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/system/reportjob/usecase/ports/in/DecryptCompanyFileUseCase.java src/main/java/com/system/reportjob/usecase/service/PgpFileDecryptionService.java src/test/java/com/system/reportjob/usecase/service/PgpFileDecryptionServiceTest.java
git commit -m "feat: add DecryptCompanyFileUseCase"
```

---

### Task 7: Admin CRUD use case (`CompanyPgpKeyConfigUseCase` + service)

**Files:**
- Create: `src/main/java/com/system/reportjob/usecase/ports/in/CompanyPgpKeyConfigUseCase.java`
- Create: `src/main/java/com/system/reportjob/usecase/ports/in/CreateCompanyPgpKeyConfigCommand.java`
- Create: `src/main/java/com/system/reportjob/usecase/ports/in/UpdateCompanyPgpKeyConfigCommand.java`
- Create: `src/main/java/com/system/reportjob/usecase/service/CompanyPgpKeyConfigService.java`
- Test: `src/test/java/com/system/reportjob/usecase/service/CompanyPgpKeyConfigServiceTest.java`

**Interfaces:**
- Consumes: `PgpKeyConfigRepositoryPort` (Task 4).
- Produces: `CompanyPgpKeyConfigUseCase` — `create(CreateCompanyPgpKeyConfigCommand) ->
  CompanyPgpKeyConfig`, `update(String companyCode, UpdateCompanyPgpKeyConfigCommand) ->
  CompanyPgpKeyConfig`, `delete(String companyCode)`, `getByCompanyCode(String companyCode) ->
  CompanyPgpKeyConfig`, `list() -> List<CompanyPgpKeyConfig>` — used by Task 8's controller.
  `CreateCompanyPgpKeyConfigCommand(String companyCode, String bankPrivateKeyArmored, String
  bankKeyPassphrase, String companyPublicKeyArmored)`, `UpdateCompanyPgpKeyConfigCommand(String
  bankPrivateKeyArmored, String bankKeyPassphrase, String companyPublicKeyArmored, Boolean active)`.

- [ ] **Step 1: Write the failing service test**

```java
package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.system.reportjob.domain.exception.PgpKeyConfigAlreadyExistsException;
import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.CreateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.in.UpdateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

class CompanyPgpKeyConfigServiceTest {

    private final PgpKeyConfigRepositoryPort repositoryPort = mock(PgpKeyConfigRepositoryPort.class);
    private final CompanyPgpKeyConfigService service = new CompanyPgpKeyConfigService(repositoryPort);

    @Test
    void createSavesANewActiveConfig() {
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.empty());
        when(repositoryPort.save(any(CompanyPgpKeyConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        CompanyPgpKeyConfig result =
                service.create(new CreateCompanyPgpKeyConfigCommand("FPT_SOFTWARE", "priv", "pass", "pub"));

        assertThat(result.companyCode()).isEqualTo("FPT_SOFTWARE");
        assertThat(result.active()).isTrue();
    }

    @Test
    void createRejectsADuplicateCompanyCode() {
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE"))
                .thenReturn(Optional.of(
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true)));

        assertThatThrownBy(
                        () -> service.create(new CreateCompanyPgpKeyConfigCommand("FPT_SOFTWARE", "p2", "pw2", "pub2")))
                .isInstanceOf(PgpKeyConfigAlreadyExistsException.class);
        verify(repositoryPort, never()).save(any());
    }

    @Test
    void updateReplacesKeyMaterialButKeepsTheId() {
        UUID id = UUID.randomUUID();
        CompanyPgpKeyConfig existing =
                new CompanyPgpKeyConfig(id, "FPT_SOFTWARE", "priv-v1", "pass-v1", "pub-v1", null, true);
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE")).thenReturn(Optional.of(existing));
        when(repositoryPort.save(any(CompanyPgpKeyConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        CompanyPgpKeyConfig result = service.update(
                "FPT_SOFTWARE", new UpdateCompanyPgpKeyConfigCommand("priv-v2", "pass-v2", "pub-v2", null));

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.bankPrivateKeyArmored()).isEqualTo("priv-v2");
        assertThat(result.active()).isTrue();
    }

    @Test
    void updateThrowsWhenMissing() {
        when(repositoryPort.findByCompanyCode("UNKNOWN_CO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                        "UNKNOWN_CO", new UpdateCompanyPgpKeyConfigCommand("p", "pw", "pub", null)))
                .isInstanceOf(PgpKeyConfigNotFoundException.class);
    }

    @Test
    void deleteDelegatesToRepositoryPort() {
        when(repositoryPort.findByCompanyCode("FPT_SOFTWARE"))
                .thenReturn(Optional.of(
                        new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true)));

        service.delete("FPT_SOFTWARE");

        verify(repositoryPort).delete("FPT_SOFTWARE");
    }

    @Test
    void listReturnsEverythingFromTheRepository() {
        CompanyPgpKeyConfig config =
                new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "priv", "pass", "pub", null, true);
        when(repositoryPort.findAll()).thenReturn(List.of(config));

        assertThat(service.list()).containsExactly(config);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigServiceTest`
Expected: FAIL — types do not exist yet.

- [ ] **Step 3: Create the commands and use case interface**

```java
package com.system.reportjob.usecase.ports.in;

public record CreateCompanyPgpKeyConfigCommand(
        String companyCode, String bankPrivateKeyArmored, String bankKeyPassphrase, String companyPublicKeyArmored) {}
```

```java
package com.system.reportjob.usecase.ports.in;

public record UpdateCompanyPgpKeyConfigCommand(
        String bankPrivateKeyArmored, String bankKeyPassphrase, String companyPublicKeyArmored, Boolean active) {}
```

```java
package com.system.reportjob.usecase.ports.in;

import java.util.List;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public interface CompanyPgpKeyConfigUseCase {
    CompanyPgpKeyConfig create(CreateCompanyPgpKeyConfigCommand command);

    CompanyPgpKeyConfig update(String companyCode, UpdateCompanyPgpKeyConfigCommand command);

    void delete(String companyCode);

    CompanyPgpKeyConfig getByCompanyCode(String companyCode);

    List<CompanyPgpKeyConfig> list();
}
```

- [ ] **Step 4: Implement the service**

```java
package com.system.reportjob.usecase.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.PgpKeyConfigAlreadyExistsException;
import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.usecase.ports.in.CompanyPgpKeyConfigUseCase;
import com.system.reportjob.usecase.ports.in.CreateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.in.UpdateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.out.PgpKeyConfigRepositoryPort;

@Service
public class CompanyPgpKeyConfigService implements CompanyPgpKeyConfigUseCase {

    private final PgpKeyConfigRepositoryPort repositoryPort;

    public CompanyPgpKeyConfigService(PgpKeyConfigRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public CompanyPgpKeyConfig create(CreateCompanyPgpKeyConfigCommand command) {
        if (repositoryPort.findByCompanyCode(command.companyCode()).isPresent()) {
            throw new PgpKeyConfigAlreadyExistsException(command.companyCode());
        }
        CompanyPgpKeyConfig config = new CompanyPgpKeyConfig(
                UUID.randomUUID(),
                command.companyCode(),
                command.bankPrivateKeyArmored(),
                command.bankKeyPassphrase(),
                command.companyPublicKeyArmored(),
                null,
                true);
        return repositoryPort.save(config);
    }

    @Override
    public CompanyPgpKeyConfig update(String companyCode, UpdateCompanyPgpKeyConfigCommand command) {
        CompanyPgpKeyConfig existing = repositoryPort
                .findByCompanyCode(companyCode)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        CompanyPgpKeyConfig updated = new CompanyPgpKeyConfig(
                existing.id(),
                companyCode,
                command.bankPrivateKeyArmored(),
                command.bankKeyPassphrase(),
                command.companyPublicKeyArmored(),
                existing.keyFingerprint(),
                command.active() != null ? command.active() : existing.active());
        return repositoryPort.save(updated);
    }

    @Override
    public void delete(String companyCode) {
        repositoryPort.findByCompanyCode(companyCode).orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
        repositoryPort.delete(companyCode);
    }

    @Override
    public CompanyPgpKeyConfig getByCompanyCode(String companyCode) {
        return repositoryPort
                .findByCompanyCode(companyCode)
                .orElseThrow(() -> new PgpKeyConfigNotFoundException(companyCode));
    }

    @Override
    public List<CompanyPgpKeyConfig> list() {
        return repositoryPort.findAll();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigServiceTest`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/system/reportjob/usecase/ports/in/CompanyPgpKeyConfigUseCase.java src/main/java/com/system/reportjob/usecase/ports/in/CreateCompanyPgpKeyConfigCommand.java src/main/java/com/system/reportjob/usecase/ports/in/UpdateCompanyPgpKeyConfigCommand.java src/main/java/com/system/reportjob/usecase/service/CompanyPgpKeyConfigService.java src/test/java/com/system/reportjob/usecase/service/CompanyPgpKeyConfigServiceTest.java
git commit -m "feat: add CompanyPgpKeyConfig admin CRUD use case"
```

---

### Task 8: REST API for PGP key config admin

**Files:**
- Create: `src/main/java/com/system/reportjob/infrastructure/web/dto/request/CreateCompanyPgpKeyConfigRequest.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/web/dto/request/UpdateCompanyPgpKeyConfigRequest.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/web/dto/response/CompanyPgpKeyConfigResponse.java`
- Create: `src/main/java/com/system/reportjob/infrastructure/web/controller/CompanyPgpKeyConfigController.java`
- Test: `src/test/java/com/system/reportjob/infrastructure/web/controller/CompanyPgpKeyConfigControllerTest.java`

**Interfaces:**
- Consumes: `CompanyPgpKeyConfigUseCase` (Task 7).
- Produces: `POST/GET/PUT/DELETE /api/company-pgp-key-configs[...]`, `CompanyPgpKeyConfigResponse(UUID
  id, String companyCode, String keyFingerprint, boolean active)` (never carries key material).

- [ ] **Step 1: Write the failing controller test**

```java
package com.system.reportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.system.reportjob.domain.exception.PgpKeyConfigAlreadyExistsException;
import com.system.reportjob.domain.exception.PgpKeyConfigNotFoundException;
import com.system.reportjob.domain.model.CompanyPgpKeyConfig;
import com.system.reportjob.infrastructure.common.GlobalExceptionHandler;
import com.system.reportjob.usecase.ports.in.CompanyPgpKeyConfigUseCase;

@WebMvcTest(CompanyPgpKeyConfigController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class CompanyPgpKeyConfigControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CompanyPgpKeyConfigUseCase useCase;

    @Test
    void createReturnsOkAndNeverEchoesKeyMaterial() throws Exception {
        when(useCase.create(any()))
                .thenReturn(new CompanyPgpKeyConfig(
                        UUID.randomUUID(), "FPT_SOFTWARE", "priv-secret", "pass-secret", "pub", null, true));

        mockMvc.perform(post("/api/company-pgp-key-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"companyCode\":\"FPT_SOFTWARE\",\"bankPrivateKeyArmored\":\"priv-secret\","
                                        + "\"bankKeyPassphrase\":\"pass-secret\",\"companyPublicKeyArmored\":\"pub\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyCode").value("FPT_SOFTWARE"))
                .andExpect(jsonPath("$.data.bankPrivateKeyArmored").doesNotExist())
                .andExpect(jsonPath("$.data.bankKeyPassphrase").doesNotExist());
    }

    @Test
    void createReturns409WhenCompanyCodeAlreadyExists() throws Exception {
        doThrow(new PgpKeyConfigAlreadyExistsException("FPT_SOFTWARE")).when(useCase).create(any());

        mockMvc.perform(post("/api/company-pgp-key-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"companyCode\":\"FPT_SOFTWARE\",\"bankPrivateKeyArmored\":\"p\","
                                        + "\"bankKeyPassphrase\":\"pw\",\"companyPublicKeyArmored\":\"pub\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getReturns404WhenMissing() throws Exception {
        when(useCase.getByCompanyCode("UNKNOWN_CO")).thenThrow(new PgpKeyConfigNotFoundException("UNKNOWN_CO"));

        mockMvc.perform(get("/api/company-pgp-key-configs/{companyCode}", "UNKNOWN_CO"))
                .andExpect(status().isNotFound());
    }

    @Test
    void putCallsUpdateNotDelete() throws Exception {
        when(useCase.update(eq("FPT_SOFTWARE"), any()))
                .thenReturn(new CompanyPgpKeyConfig(UUID.randomUUID(), "FPT_SOFTWARE", "p2", "pw2", "pub2", null, true));

        mockMvc.perform(put("/api/company-pgp-key-configs/{companyCode}", "FPT_SOFTWARE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"bankPrivateKeyArmored\":\"p2\",\"bankKeyPassphrase\":\"pw2\","
                                        + "\"companyPublicKeyArmored\":\"pub2\"}"))
                .andExpect(status().isOk());

        verify(useCase).update(eq("FPT_SOFTWARE"), any());
        verify(useCase, never()).delete(any());
    }

    @Test
    void deleteReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/company-pgp-key-configs/{companyCode}", "FPT_SOFTWARE"))
                .andExpect(status().isOk());

        verify(useCase).delete("FPT_SOFTWARE");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigControllerTest`
Expected: FAIL — controller and DTOs do not exist yet.

- [ ] **Step 3: Create the request DTOs**

```java
package com.system.reportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateCompanyPgpKeyConfigRequest(
        @NotBlank String companyCode,
        @NotBlank String bankPrivateKeyArmored,
        @NotBlank String bankKeyPassphrase,
        @NotBlank String companyPublicKeyArmored) {}
```

```java
package com.system.reportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateCompanyPgpKeyConfigRequest(
        @NotBlank String bankPrivateKeyArmored,
        @NotBlank String bankKeyPassphrase,
        @NotBlank String companyPublicKeyArmored,
        Boolean active) {}
```

- [ ] **Step 4: Create the response DTO**

```java
package com.system.reportjob.infrastructure.web.dto.response;

import java.util.UUID;

import com.system.reportjob.domain.model.CompanyPgpKeyConfig;

public record CompanyPgpKeyConfigResponse(UUID id, String companyCode, String keyFingerprint, boolean active) {
    public static CompanyPgpKeyConfigResponse from(CompanyPgpKeyConfig config) {
        return new CompanyPgpKeyConfigResponse(config.id(), config.companyCode(), config.keyFingerprint(), config.active());
    }
}
```

- [ ] **Step 5: Create the controller**

```java
package com.system.reportjob.infrastructure.web.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.system.reportjob.infrastructure.common.ApiResponse;
import com.system.reportjob.infrastructure.web.dto.request.CreateCompanyPgpKeyConfigRequest;
import com.system.reportjob.infrastructure.web.dto.request.UpdateCompanyPgpKeyConfigRequest;
import com.system.reportjob.infrastructure.web.dto.response.CompanyPgpKeyConfigResponse;
import com.system.reportjob.usecase.ports.in.CompanyPgpKeyConfigUseCase;
import com.system.reportjob.usecase.ports.in.CreateCompanyPgpKeyConfigCommand;
import com.system.reportjob.usecase.ports.in.UpdateCompanyPgpKeyConfigCommand;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/company-pgp-key-configs")
@Tag(name = "Company PGP key config", description = "Quản lý PGP key theo company, dùng để decrypt file trước khi xử lý")
@SecurityRequirement(name = "bearerAuth")
public class CompanyPgpKeyConfigController {

    private final CompanyPgpKeyConfigUseCase useCase;

    public CompanyPgpKeyConfigController(CompanyPgpKeyConfigUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public ApiResponse<CompanyPgpKeyConfigResponse> create(
            @RequestBody @Valid CreateCompanyPgpKeyConfigRequest request) {
        var config = useCase.create(new CreateCompanyPgpKeyConfigCommand(
                request.companyCode(),
                request.bankPrivateKeyArmored(),
                request.bankKeyPassphrase(),
                request.companyPublicKeyArmored()));
        return ApiResponse.ok(CompanyPgpKeyConfigResponse.from(config));
    }

    @GetMapping("/{companyCode}")
    public ApiResponse<CompanyPgpKeyConfigResponse> get(@PathVariable String companyCode) {
        return ApiResponse.ok(CompanyPgpKeyConfigResponse.from(useCase.getByCompanyCode(companyCode)));
    }

    @GetMapping
    public ApiResponse<List<CompanyPgpKeyConfigResponse>> list() {
        return ApiResponse.ok(
                useCase.list().stream().map(CompanyPgpKeyConfigResponse::from).toList());
    }

    @PutMapping("/{companyCode}")
    public ApiResponse<CompanyPgpKeyConfigResponse> update(
            @PathVariable String companyCode, @RequestBody @Valid UpdateCompanyPgpKeyConfigRequest request) {
        var config = useCase.update(
                companyCode,
                new UpdateCompanyPgpKeyConfigCommand(
                        request.bankPrivateKeyArmored(),
                        request.bankKeyPassphrase(),
                        request.companyPublicKeyArmored(),
                        request.active()));
        return ApiResponse.ok(CompanyPgpKeyConfigResponse.from(config));
    }

    @DeleteMapping("/{companyCode}")
    public ApiResponse<Void> delete(@PathVariable String companyCode) {
        useCase.delete(companyCode);
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=CompanyPgpKeyConfigControllerTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/system/reportjob/infrastructure/web src/test/java/com/system/reportjob/infrastructure/web/controller/CompanyPgpKeyConfigControllerTest.java
git commit -m "feat: add REST API for company PGP key config admin"
```

---

### Task 9: Wire PGP decryption into `PayrollJobAction`

**Files:**
- Modify: `src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobAction.java`
- Modify: `src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobActionTest.java`

**Interfaces:**
- Consumes: `DecryptCompanyFileUseCase.decryptFile(String, Path)` (Task 6).
- Produces: `PayrollExpression` gains a `Boolean pgpEncrypted` field (optional, default treated as
  `false`); no change to `PayrollBatchConfig`'s public surface.

- [ ] **Step 1: Update `PayrollJobActionTest`'s constructor helper for the new dependency**

In `src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobActionTest.java`,
add the import `com.system.reportjob.usecase.ports.in.DecryptCompanyFileUseCase` and change:

```java
private PayrollJobAction newAction(JobOperator jobOperator, HolidayQueryUseCase holidayQueryUseCase) {
    return new PayrollJobAction(
            jobOperator,
            fptPayrollJob,
            holidayQueryUseCase,
            objectMapper,
            new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()),
            Duration.ofSeconds(30));
}
```

to:

```java
private final DecryptCompanyFileUseCase decryptCompanyFileUseCase = mock(DecryptCompanyFileUseCase.class);

private PayrollJobAction newAction(JobOperator jobOperator, HolidayQueryUseCase holidayQueryUseCase) {
    return new PayrollJobAction(
            jobOperator,
            fptPayrollJob,
            holidayQueryUseCase,
            objectMapper,
            decryptCompanyFileUseCase,
            new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()),
            Duration.ofSeconds(30));
}
```

- [ ] **Step 2: Add the failing PGP-encrypted test case**

Add to `PayrollJobActionTest`:

```java
@Test
void decryptsTheCsvFileFirstWhenPgpEncryptedIsTrue() throws Exception {
    HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
    when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
    JobOperator jobOperator = mock(JobOperator.class);
    JobExecution completed = mock(JobExecution.class);
    when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
    when(completed.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
    when(jobOperator.start(eq(fptPayrollJob), any(JobParameters.class))).thenReturn(completed);
    Path decryptedFile = Files.createTempFile("payroll-decrypted", ".csv");
    when(decryptCompanyFileUseCase.decryptFile(eq("FPT_SOFTWARE"), any())).thenReturn(decryptedFile);
    PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
    String expression = "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\","
            + "\"branchId\":\"ALL\",\"pgpEncrypted\":true}";
    JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", expression, null);

    action.execute(definition);

    ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
    verify(jobOperator).start(eq(fptPayrollJob), paramsCaptor.capture());
    assertThat(paramsCaptor.getValue().getString("csvFilePath")).isEqualTo(decryptedFile.toString());
    assertThat(Files.exists(decryptedFile)).isFalse(); // đã bị xoá trong finally sau khi job chạy xong
}

@Test
void deletesTheDecryptedFileEvenWhenTheBatchJobFails() throws Exception {
    HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
    when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
    JobOperator jobOperator = mock(JobOperator.class);
    JobExecution failed = mock(JobExecution.class);
    when(failed.getStatus()).thenReturn(BatchStatus.FAILED);
    when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(failed);
    Path decryptedFile = Files.createTempFile("payroll-decrypted", ".csv");
    when(decryptCompanyFileUseCase.decryptFile(eq("FPT_SOFTWARE"), any())).thenReturn(decryptedFile);
    PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
    String expression = "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\","
            + "\"branchId\":\"ALL\",\"pgpEncrypted\":true}";
    JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", expression, null);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> action.execute(definition))
            .isInstanceOf(IllegalStateException.class);

    assertThat(Files.exists(decryptedFile)).isFalse();
}
```

Also add the new imports this test needs: `java.nio.file.Files`, `java.nio.file.Path`, and
`static org.assertj.core.api.Assertions.assertThat` (already present).

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=PayrollJobActionTest`
Expected: FAIL — `PayrollJobAction`'s constructor doesn't accept `DecryptCompanyFileUseCase` yet, and
`PayrollExpression` has no `pgpEncrypted` field.

- [ ] **Step 4: Update `PayrollJobAction`**

Add the import `com.system.reportjob.usecase.ports.in.DecryptCompanyFileUseCase`,
`java.nio.file.Files`, and `java.nio.file.Path`. Add the field and constructor parameter:

```java
private final DecryptCompanyFileUseCase decryptCompanyFileUseCase;
```

```java
public PayrollJobAction(
        JobOperator jobOperator,
        Job fptPayrollJob,
        HolidayQueryUseCase holidayQueryUseCase,
        ObjectMapper objectMapper,
        DecryptCompanyFileUseCase decryptCompanyFileUseCase,
        @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
        @Value("${app.batch.payroll.execution-timeout:15m}") Duration executionTimeout) {
    this.jobOperator = jobOperator;
    this.fptPayrollJob = fptPayrollJob;
    this.holidayQueryUseCase = holidayQueryUseCase;
    this.objectMapper = objectMapper;
    this.decryptCompanyFileUseCase = decryptCompanyFileUseCase;
    this.jobActionTaskExecutor = jobActionTaskExecutor;
    this.executionTimeout = executionTimeout;
}
```

Replace the body of `runJob(...)`:

```java
private Void runJob(JobDefinition definition, PayrollExpression expression, LocalDate targetPayDate)
        throws Exception {
    String baseFileName = "FPT_PAYROLL_" + targetPayDate;
    boolean pgpEncrypted = Boolean.TRUE.equals(expression.pgpEncrypted());
    Path sourcePath =
            Path.of(expression.csvDirectory(), baseFileName + (pgpEncrypted ? ".csv.pgp" : ".csv"));

    Path csvFilePath = pgpEncrypted
            ? decryptCompanyFileUseCase.decryptFile(expression.companyCode(), sourcePath)
            : sourcePath;
    try {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("companyCode", expression.companyCode())
                .addString("targetPayDate", targetPayDate.toString())
                .addString("csvFilePath", csvFilePath.toString())
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
    } finally {
        if (pgpEncrypted) {
            Files.deleteIfExists(csvFilePath);
        }
    }
}
```

Update the `PayrollExpression` record:

```java
record PayrollExpression(
        String companyCode,
        String csvDirectory,
        String countryCode,
        String branchId,
        Integer payDayOfMonth,
        Boolean pgpEncrypted) {}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=PayrollJobActionTest`
Expected: PASS (all 7 tests — the 5 existing plus the 2 new ones).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobAction.java src/test/java/com/system/reportjob/infrastructure/jobactions/batch/payroll/PayrollJobActionTest.java
git commit -m "feat: opt-in PGP decryption for BANK_SALARY_PAYROLL via pgpEncrypted"
```

---

### Task 10: Full regression pass

**Files:** none (verification only).

**Interfaces:** none — this task only runs the existing and newly-added test suites together.

- [ ] **Step 1: Run spotless**

Run: `mvn -q spotless:apply`
Expected: exits 0; if it reformats files, `git diff` to confirm only whitespace/import-order changes.

- [ ] **Step 2: Run the full test suite**

Run: `mvn test`
Expected: `BUILD SUCCESS`, all tests pass (Docker must be running locally for the Testcontainers-backed
tests under `infrastructure/persistence`).

- [ ] **Step 3: Commit if spotless changed anything**

```bash
git add -A
git commit -m "chore: spotless:apply after PGP feature" --allow-empty
```

If step 1 made no changes, skip this commit (or the `--allow-empty` commit is a harmless no-op record).

---

## Self-Review

**Spec coverage:**
- §2 Data model → Task 4 (migration, entity, adapter).
- §3 Domain model & ports → Task 2 (`CompanyPgpKeyConfig`, exceptions), Task 4
  (`PgpKeyConfigRepositoryPort`), Task 5 (`PgpDecryptionGatewayPort`), Task 6
  (`DecryptCompanyFileUseCase`), Task 7 (`CompanyPgpKeyConfigUseCase`).
- §4 Integration flow → Task 9 (`PayrollJobAction`, `pgpEncrypted`, `PayrollBatchConfig` untouched).
- §5 Security → Task 3 (AES-GCM sealing), Task 5 (mandatory signature verification, owner-only temp
  file permissions, temp file cleanup on every failure path), Task 8 (response DTO never carries key
  material), Task 9 (`finally` cleanup even on batch failure).
- §6 REST API → Task 8.
- §7 Error handling → Task 2 (plus the added `PGP_KEY_CONFIG_ALREADY_EXISTS`, justified inline there).
- §8 Testing → covered by each task's own test; `PgpTestFixtures`/`BouncyCastlePgpDecryptionAdapterTest`
  in Task 5 implement the spec's "sinh key pair test bằng Bouncy Castle... encrypt+sign... decrypt lại"
  plan, including the wrong-passphrase and wrong-signer cases.
- §9 Dependency → Task 1.
- §10 Out of scope (key rotation history, auto-detection of encryption, other job types,
  external secret manager) → untouched by this plan, as intended.

**Placeholder scan:** no `TBD`/`TODO`, no "add appropriate error handling"-style steps — every step
either shows the literal file content or a literal shell command.

**Type consistency:** `CompanyPgpKeyConfig` fields/order are identical everywhere they're constructed
(Tasks 2, 4, 5, 6, 7, 8). `PgpDecryptionGatewayPort.decryptAndVerify(Path, CompanyPgpKeyConfig)` matches
between the port (Task 5) and its only caller (Task 6). `DecryptCompanyFileUseCase.decryptFile(String,
Path)` matches between Task 6's interface and Task 9's call site. `PgpKeyConfigRepositoryPort`'s four
methods (`save`, `findByCompanyCode`, `findAll`, `delete`) are used consistently by both Task 6
(`PgpFileDecryptionService`) and Task 7 (`CompanyPgpKeyConfigService`) — no method beyond that set is
referenced anywhere.
