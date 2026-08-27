# Company PGP file decryption — Design Spec

## 1. Mục tiêu & bối cảnh

`BANK_SALARY_PAYROLL` (xem `docs/bank-salary-sample/bank-salary-sample.md`) hiện đọc file CSV lương
plaintext trực tiếp từ đĩa (`PayrollBatchConfig.holdFundsTasklet`, `csvEmployeeReader`). Thực tế, khi
một công ty gửi file dữ liệu (payroll, ATM reconciliation, v.v.) cho ngân hàng, file thường được mã hoá
bằng **PGP** — công ty encrypt bằng public key của ngân hàng và ký (sign) bằng private key của họ; ngân
hàng decrypt bằng private key của mình và verify chữ ký bằng public key của công ty.

Mục tiêu của tính năng này:

1. Thêm một bảng cấu hình PGP key theo từng company (`company_pgp_key_config`), quản lý qua REST API.
2. Thêm một **usecase dùng chung** (`DecryptCompanyFileUseCase`) — nhận `companyCode` + đường dẫn file
   đã mã hoá, trả về đường dẫn file plaintext tạm — để bất kỳ `JobAction`/`BatchConfig` nào cần đọc file
   theo company đều có thể gọi mà không cần biết chi tiết PGP.
3. Tích hợp vào `BANK_SALARY_PAYROLL` làm ví dụ đầu tiên, với thay đổi tối thiểu: chỉ sửa
   `PayrollJobAction`, **không sửa `PayrollBatchConfig`** (tasklet/reader vẫn đọc file plaintext y hệt
   hiện tại — chỉ là file đã được decrypt trước khi batch job start).

Đây là bổ sung độc lập, không đổi hành vi của các `JobType` khác (`HTTP_CALL`, `ECHO`, `BANKING_EOD`,
`SPRING_BATCH_USER_EXPORT`). Các job đó tiếp tục đọc file plaintext như cũ; muốn dùng PGP thì tích hợp
tương tự Section 4 khi cần (không nằm trong phạm vi lần này).

## 2. Data model & Migration

Theo đúng convention hiện có: entity extends `BaseEntity` (`id UUID`, sinh ở tầng Java — xem
`domain`/`infrastructure/persistence/entity/BaseEntity.java`), soft-delete qua `is_deleted` +
`@SQLDelete`/`@SQLRestriction` (giống `JobDefinitionEntity`, `TaskEntity`).

Mỗi company chỉ có **1 config đang active** — rotate key là `UPDATE` tại chỗ, không lưu lịch sử nhiều
key/company (YAGNI: chưa có nhu cầu decrypt lại file cũ sau khi đã rotate key trong flow hiện tại). Nếu
sau này cần audit/rotate có lịch sử, tách bảng `company_pgp_key_config_history` lúc đó.

### `V12__create_company_pgp_key_config.sql`

```sql
-- Cấu hình PGP key theo company, dùng bởi DecryptCompanyFileUseCase để decrypt + verify file
-- công ty gửi sang trước khi các JobAction (BANK_SALARY_PAYROLL, ...) đọc file.
-- Xem docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md.
CREATE TABLE company_pgp_key_config (
    id                             UUID PRIMARY KEY,
    company_code                   VARCHAR(30) NOT NULL,
    bank_private_key_encrypted     TEXT NOT NULL, -- PGP private key (ASCII-armored) của ngân hàng,
                                                   -- niêm phong (seal) bằng AES-256-GCM + app.pgp.master-key
    bank_key_passphrase_encrypted  TEXT NOT NULL, -- passphrase của private key trên, seal cùng cơ chế
    company_public_key_armored     TEXT NOT NULL, -- public key của company, dùng verify signature
                                                   -- (không nhạy cảm - lưu plaintext)
    key_fingerprint                VARCHAR(64),   -- fingerprint của bank private key, để đối chiếu khi rotate
    active                         BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted                     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ,
    CONSTRAINT uq_company_pgp_key_config_company UNIQUE (company_code)
);
```

`company_code` không có FK — theo đúng pattern hiện tại (`fpt_company_account.company_code` cũng là
string tự do, chưa có bảng `companies` first-class trong hệ thống).

## 3. Domain model & Ports (Clean Architecture)

```
domain/model/
  CompanyPgpKeyConfig.java
    record CompanyPgpKeyConfig(
        UUID id, String companyCode,
        String bankPrivateKeyArmored,     // plaintext trong bộ nhớ - KHÔNG bao giờ serialize ra ngoài
        String bankKeyPassphrase,         // plaintext trong bộ nhớ
        String companyPublicKeyArmored,
        String keyFingerprint,
        boolean active)
    -- record thuần Java, không import Spring/JPA, giống các domain model khác.

usecase/ports/out/
  PgpKeyConfigRepositoryPort.java
    Optional<CompanyPgpKeyConfig> findActiveByCompanyCode(String companyCode);
    CompanyPgpKeyConfig save(CompanyPgpKeyConfig config);          // insert hoặc update theo companyCode
    List<CompanyPgpKeyConfig> findAll();
    void deactivate(String companyCode);

  PgpDecryptionGatewayPort.java
    Path decryptAndVerify(Path encryptedFile, CompanyPgpKeyConfig keyConfig, Path outputDir);
    -- decrypt bằng bankPrivateKeyArmored/bankKeyPassphrase, verify signature bằng
    -- companyPublicKeyArmored; ném PgpVerificationException/PgpDecryptionException (checked/unchecked
    -- nội bộ infra) nếu thất bại - usecase service bắt và map sang BusinessException.

usecase/ports/in/
  CompanyPgpKeyConfigUseCase.java   -- CRUD cho admin API
    CompanyPgpKeyConfig create(CreateCompanyPgpKeyConfigCommand cmd);
    CompanyPgpKeyConfig update(String companyCode, UpdateCompanyPgpKeyConfigCommand cmd);
    void deactivate(String companyCode);
    CompanyPgpKeyConfig getByCompanyCode(String companyCode);
    List<CompanyPgpKeyConfig> list();

  DecryptCompanyFileUseCase.java    -- dùng bởi JobAction, KHÔNG dùng bởi controller
    Path decryptFile(String companyCode, Path encryptedFilePath);

usecase/service/
  CompanyPgpKeyConfigService.java   -- implements CompanyPgpKeyConfigUseCase
  PgpFileDecryptionService.java     -- implements DecryptCompanyFileUseCase:
    1. findActiveByCompanyCode qua port; rỗng hoặc active=false -> BusinessException(PGP_KEY_CONFIG_NOT_FOUND)
    2. gọi PgpDecryptionGatewayPort.decryptAndVerify(...)
    3. lỗi decrypt -> BusinessException(PGP_DECRYPTION_FAILED); lỗi verify chữ ký -> BusinessException(PGP_SIGNATURE_INVALID)
    4. trả về Path file plaintext tạm (chưa xoá - caller chịu trách nhiệm dọn dẹp, xem Section 5)
```

## 4. Luồng xử lý & tích hợp vào `PayrollJobAction`

`PayrollExpression` (record nội bộ của `PayrollJobAction`) có thêm field tường minh `pgpEncrypted`
(optional, mặc định `false`). Đây là quyết định có chủ đích: hành vi decrypt được **khai báo rõ trong
JobDefinition**, không suy luận ngầm từ việc "company có config PGP hay không" trong DB — tránh việc
quên seed `company_pgp_key_config` khiến job âm thầm đổi hành vi (đọc nhầm file mã hoá như plaintext).

```java
record PayrollExpression(
        String companyCode, String csvDirectory, String countryCode, String branchId,
        Integer payDayOfMonth, Boolean pgpEncrypted) {}
```

Trong `PayrollJobAction.runJob(...)`, trước khi build `JobParameters`:

```java
String baseFileName = "FPT_PAYROLL_" + targetPayDate;
boolean pgpEncrypted = Boolean.TRUE.equals(expression.pgpEncrypted());
Path sourcePath = Path.of(expression.csvDirectory(),
        baseFileName + (pgpEncrypted ? ".csv.pgp" : ".csv"));

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
    // ... (kiểm tra status như hiện tại)
} finally {
    if (pgpEncrypted) {
        Files.deleteIfExists(csvFilePath); // dọn plaintext temp file, kể cả khi job fail
    }
}
```

`PayrollBatchConfig` (`holdFundsTasklet`, `csvEmployeeReader`) **không thay đổi** — cả hai vẫn nhận
`csvFilePath` qua `JobParameters` và đọc như một file CSV plaintext bình thường.

Bất kỳ `JobAction`/`BatchConfig` nào khác muốn dùng file theo company chỉ cần lặp lại đúng pattern
try/finally ở trên quanh điểm build `JobParameters` (hoặc quanh điểm mở file, nếu không dùng Spring
Batch) — không cần thay đổi gì ở tầng đọc/xử lý dữ liệu bên dưới.

## 5. Bảo mật

- **Encryption at rest**: `bank_private_key_encrypted`, `bank_key_passphrase_encrypted` được seal bằng
  AES-256-GCM trước khi lưu, mở seal khi load — thực hiện ở
  `infrastructure/persistence/adapter/CompanyPgpKeyConfigRepositoryAdapter` qua một helper
  `infrastructure/security/pgp/PgpKeyMaterialCipher`. Master key đọc từ `app.pgp.master-key` (base64
  256-bit, env var — **không** commit vào `application.yml`, tương tự cách các secret khác nên được
  cấu hình qua biến môi trường). `company_public_key_armored` là public key, không seal.
- **Temp file plaintext**: ghi vào `app.pgp.temp-dir` (mặc định `${java.io.tmpdir}`), tên file random
  (`UUID` + `.csv`), set permission `600` khi OS hỗ trợ POSIX
  (`Files.setPosixFilePermissions`/`PosixFilePermissions.asFileAttribute`). Luôn xoá trong `finally`
  của JobAction, kể cả khi batch job fail hoặc bị timeout.
- **Verify chữ ký bắt buộc**: `PgpDecryptionGatewayPort.decryptAndVerify` luôn verify signature bằng
  `companyPublicKeyArmored`; verify thất bại → ném lỗi, **không** trả về nội dung đã decrypt (tránh xử
  lý nhầm file giả mạo/bị sửa đổi dù có decrypt được).
- **Logging**: không bao giờ log private key, passphrase, hay nội dung file. Chỉ log `companyCode`, tên
  file, kết quả (success/fail), `keyFingerprint`.
- **API response**: `CompanyPgpKeyConfigController` không bao giờ trả `bankPrivateKeyArmored` /
  `bankKeyPassphrase` trong response (kể cả `GET`) — response DTO riêng, chỉ có
  `companyCode`, `keyFingerprint`, `active`, `updatedAt`.

## 6. REST API

```
POST   /api/company-pgp-key-configs
       body: { companyCode, bankPrivateKeyArmored, bankKeyPassphrase, companyPublicKeyArmored }
       -> 201, response không chứa key material

GET    /api/company-pgp-key-configs/{companyCode}   -> 200 | 404 (PGP_KEY_CONFIG_NOT_FOUND)
GET    /api/company-pgp-key-configs                 -> 200, danh sách (không chứa key material)
PUT    /api/company-pgp-key-configs/{companyCode}   -> 200 (rotate key: thay bankPrivateKeyArmored/
                                                        bankKeyPassphrase/companyPublicKeyArmored)
DELETE /api/company-pgp-key-configs/{companyCode}   -> 204 (soft-delete/deactivate, giống pattern
                                                        xoá JobDefinition/Task hiện có)
```

Controller chỉ gọi `CompanyPgpKeyConfigUseCase` (in-port), mapping DTO ⇄ domain ở tầng controller —
đúng nguyên tắc hiện có của `infrastructure/web`.

## 7. Error handling

Thêm vào `domain/exception/ErrorCode` (và bắt buộc thêm case tương ứng vào
`GlobalExceptionHandler.statusFor(ErrorCode)` — switch exhaustive, compiler sẽ báo lỗi nếu quên):

| ErrorCode                  | HTTP Status | Message key                    | Khi nào |
|-----------------------------|-------------|---------------------------------|---------|
| `PGP_KEY_CONFIG_NOT_FOUND`  | 404         | `pgp_key_config.not_found`      | Company chưa có config, hoặc config `active=false` |
| `PGP_DECRYPTION_FAILED`     | 422         | `pgp.decryption_failed`         | Sai private key/passphrase, file hỏng/không phải PGP hợp lệ |
| `PGP_SIGNATURE_INVALID`     | 422         | `pgp.signature_invalid`         | Verify chữ ký bằng company public key thất bại |

## 8. Testing

- `PgpFileDecryptionServiceTest` (Mockito thuần, không Spring context — giống style `usecase/service`
  hiện có): key config not found, decrypt fail, signature invalid, happy path.
- `CompanyPgpKeyConfigServiceTest`: CRUD, validate input, deactivate.
- `BouncyCastlePgpDecryptionAdapterTest`: sinh key pair test bằng Bouncy Castle trong `@BeforeAll`,
  encrypt + sign một file mẫu bằng key đó rồi decrypt lại qua adapter, assert nội dung khớp byte-for-byte;
  case sai passphrase; case verify chữ ký bằng public key khác (phải fail).
- `CompanyPgpKeyConfigRepositoryAdapterTest` (Testcontainers PostgreSQL, giống
  `infrastructure/persistence` hiện có): roundtrip save → load, assert giá trị trong DB thực sự là
  ciphertext (khác plaintext ban đầu) và load lại ra đúng plaintext.
- Cập nhật `PayrollJobActionTest`: thêm case `pgpEncrypted=true` (mock `DecryptCompanyFileUseCase`,
  assert file tạm được xoá ở `finally` kể cả khi job fail).

## 9. Dependency mới

Thêm `org.bouncycastle:bcpg-jdk18on` và `org.bouncycastle:bcprov-jdk18on` vào `pom.xml` (chuẩn de-facto
cho OpenPGP trên JVM, chưa có dependency PGP nào trong project trước đây).

## 10. Ngoài phạm vi (YAGNI)

- Lưu lịch sử nhiều key/company (key rotation có audit trail) — xem Section 2.
- Tự động phát hiện file có mã hoá PGP hay không (dựa vào magic bytes/extension) — hiện tại dùng field
  tường minh `pgpEncrypted` trong expression.
- Tích hợp PGP vào `BANKING_EOD`, `SPRING_BATCH_USER_EXPORT` hay `HTTP_CALL` — thiết kế đã đảm bảo các
  job này có thể dùng lại `DecryptCompanyFileUseCase` sau này theo đúng Section 4, nhưng việc tích hợp
  cụ thể không nằm trong lần thay đổi này.
- Secret manager ngoài (Vault/AWS KMS) cho master key — dùng biến môi trường `app.pgp.master-key` là đủ
  cho scope hiện tại của project (chưa có tích hợp secret manager nào khác).
