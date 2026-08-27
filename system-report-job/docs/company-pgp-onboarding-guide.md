# Guide: Áp dụng PGP decrypt cho company mới / job mới

Hướng dẫn tổng quát để dùng lại flow PGP decrypt (xem
`docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md`) cho:
- **Part 1** — một company mới, dùng **job đã hỗ trợ PGP sẵn** (hiện tại: `BANK_SALARY_PAYROLL`).
  Không cần sửa code, chỉ cần cấu hình.
- **Part 2** — một job type mới (chưa hỗ trợ PGP), cần thêm ~10 dòng code vào `JobAction` đó theo
  đúng pattern đã dùng cho `PayrollJobAction`.

Ví dụ cụ thể đã chạy thử thành công cho `BANK_SALARY_PAYROLL`/`FPT_SOFTWARE`:
`docs/bank-salary-sample/running-the-sample-pgp.md`.

## Part 1: Company mới, job đã hỗ trợ PGP sẵn

Không cần code. 4 bước:

### 1. Lấy/sinh key pair PGP cho 2 bên

- **Ngoài đời thật**: công ty gửi cho ngân hàng public key của họ; ngân hàng gửi cho công ty public
  key của ngân hàng để họ encrypt file gửi sang. Ngân hàng giữ private key của mình.
- **Demo/test**: sinh bằng `gpg` (xem `docs/bank-salary-sample/running-the-sample-pgp.md` bước 1 —
  nhớ dùng `GNUPGHOME` đường dẫn ngắn, tránh lỗi "File name too long").

Kết quả cần có: `<bank>-private.asc` (private key ngân hàng, armored), passphrase của nó, và
`<company>-public.asc` (public key công ty, armored).

### 2. Đăng ký `company_pgp_key_config`

```bash
curl -X POST http://localhost:8081/api/company-pgp-key-configs \
  -H "Content-Type: application/json" \
  --data '{
    "companyCode": "<COMPANY_CODE_MOI>",
    "bankPrivateKeyArmored": "<nội dung private key ngân hàng, armored>",
    "bankKeyPassphrase": "<passphrase>",
    "companyPublicKeyArmored": "<nội dung public key công ty, armored>"
  }'
```

`companyCode` phải khớp CHÍNH XÁC với `companyCode` mà job dùng để tra cứu file (thường là field
`companyCode` trong `expression` JSON của `JobDefinition`, hoặc cột `company_code` của bảng account
tương ứng nếu job đó có — xem Part 2 nếu job mới không có sẵn khái niệm `companyCode`).

Rotate key (đổi key định kỳ hoặc khi bị lộ): `PUT /api/company-pgp-key-configs/{companyCode}` với
key mới — không cần xoá/tạo lại. Tạm khoá 1 company mà không xoá config: `PUT` với `"active": false`
(job sẽ báo lỗi `PGP_KEY_CONFIG_NOT_FOUND` — cùng lỗi với "chưa có config" — cho tới khi bật lại).

### 3. Công ty encrypt + sign file gửi sang

Công ty (bên gửi) chạy trên hệ thống của họ (hoặc demo bằng `gpg` cục bộ):

```bash
gpg --armor --sign --encrypt \
  --local-user "<key công ty>" \
  --recipient "<key ngân hàng>" \
  --output <ten-file-goc>.pgp \
  <ten-file-goc>
```

Tên file `.pgp` phải khớp với quy ước mà `JobAction` đó tìm kiếm (với `BANK_SALARY_PAYROLL` là
`FPT_PAYROLL_<targetPayDate>.csv.pgp`, xem `PayrollJobAction.runJob()`).

### 4. Bật cờ PGP trên `JobDefinition` của company đó

```bash
curl -X PUT http://localhost:8081/api/job-definitions/<job-definition-id> \
  -H "Content-Type: application/json" \
  --data '{
    "jobType": "<JOB_TYPE_DA_HO_TRO_PGP>",
    "expression": "{...cac field hien co..., \"companyCode\":\"<COMPANY_CODE_MOI>\", \"pgpEncrypted\":true}",
    "description": "..."
  }'
```

`pgpEncrypted` là cờ tường minh, mặc định `false`/vắng mặt — không tự suy ra từ việc có config PGP
hay không, nên phải bật thủ công cho từng `JobDefinition`. Sau đó `trigger-now`/để Quartz tự chạy
như bình thường.

**Xong** — không cần đổi gì ở JPA entity, Flyway migration, hay batch config. Company mới chỉ là
một dòng mới trong `company_pgp_key_config`.

## Part 2: Job type mới, chưa hỗ trợ PGP

Cần sửa đúng 1 file: `JobAction` của job đó (production code, cần review + test như mọi thay đổi
khác). Pattern tham khảo trực tiếp: `PayrollJobAction.java` (constructor, field `pgpEncrypted`
trong record expression, method `runJob()`).

### Bước 1: Thêm field `pgpEncrypted` vào expression record của job đó

```java
record YourJobExpression(
        String companyCode,
        // ...các field hiện có...
        Boolean pgpEncrypted) {}   // Boolean (boxed), KHÔNG dùng primitive boolean —
                                   // JobDefinition cũ không có field này sẽ deserialize ra null,
                                   // primitive boolean sẽ NPE khi Jackson cố unbox null
```

### Bước 2: Inject `DecryptCompanyFileUseCase` vào `JobAction`

```java
private final DecryptCompanyFileUseCase decryptCompanyFileUseCase;

public YourJobAction(/* ...các dependency hiện có..., */ DecryptCompanyFileUseCase decryptCompanyFileUseCase) {
    // ...
    this.decryptCompanyFileUseCase = decryptCompanyFileUseCase;
}
```

Spring tự inject — `DecryptCompanyFileUseCase` (impl: `PgpFileDecryptionService`) đã là bean sẵn có
trong context, không cần đăng ký gì thêm.

### Bước 3: Decrypt TRƯỚC khi build tham số cho phần xử lý file (Spring Batch `JobParameters`,
hoặc tương đương nếu job không dùng Spring Batch)

```java
boolean pgpEncrypted = Boolean.TRUE.equals(expression.pgpEncrypted());
Path sourcePath = Path.of(expression.someDirectory(),
        baseFileName + (pgpEncrypted ? ".pgp" : ""));   // quy ước đặt tên: tuỳ bạn chọn,
                                                          // chỉ cần nhất quán với bước encrypt

Path filePath = pgpEncrypted
        ? decryptCompanyFileUseCase.decryptFile(expression.companyCode(), sourcePath)
        : sourcePath;
try {
    // ...logic xử lý file HIỆN CÓ, không đổi gì — vẫn đọc `filePath` như file plaintext...
} finally {
    if (pgpEncrypted) {
        Files.deleteIfExists(filePath);   // BẮT BUỘC — filePath là temp file plaintext,
                                           // xoá dù thành công hay thất bại
    }
}
```

**Nguyên tắc bất biến, không được vi phạm:**
- Toàn bộ logic xử lý file bên dưới (Spring Batch tasklet/reader, hay code đọc file khác) **không
  đổi gì** — chúng chỉ nhận một đường dẫn file plaintext, y hệt trước khi có PGP. Đây là lý do thiết
  kế này áp dụng dễ vào job nào cũng được.
- File tạm sau decrypt **luôn luôn** phải bị xoá trong `finally`, kể cả khi phần xử lý bên dưới lỗi
  — đây là plaintext PII/dữ liệu nhạy cảm nằm trên đĩa, không được để sót lại.
- Nếu `decryptCompanyFileUseCase.decryptFile(...)` bản thân nó ném lỗi (sai key, verify chữ ký thất
  bại, không có config cho company đó) — để lỗi đó propagate tự nhiên (đã là
  `PgpKeyConfigNotFoundException` / `PgpDecryptionFailedException` / `PgpSignatureInvalidException`,
  tự map ra HTTP 404/422 nếu có API nào expose ra ngoài) — không nuốt lỗi, không catch rồi làm gì
  khác.

### Bước 4: Test

Theo đúng pattern `PayrollJobActionTest`:
- Mock `DecryptCompanyFileUseCase`, thêm 1 test case `pgpEncrypted=true` xác nhận
  `csvFilePath`/tham số tương ứng lấy từ `decryptFile(...)` trả về, và file đó bị xoá sau khi chạy
  xong (kể cả khi job thất bại — 2 test case riêng: happy path + failure path).
- Không cần Testcontainers/Docker cho việc này — `DecryptCompanyFileUseCase` bị mock hoàn toàn,
  không chạm tới BC/DB thật.

Sau bước này, job mới đã hỗ trợ PGP y hệt `BANK_SALARY_PAYROLL` — quay lại Part 1 để onboard company
cho job đó.

## Tham khảo nhanh

| Muốn làm gì | Xem ở đâu |
|---|---|
| Company mới, job đã hỗ trợ PGP | Part 1 ở trên |
| Job mới chưa hỗ trợ PGP | Part 2 ở trên |
| Ví dụ đã chạy thành công (FPT_SOFTWARE/BANK_SALARY_PAYROLL) | `docs/bank-salary-sample/running-the-sample-pgp.md` |
| Thiết kế đầy đủ, error code, bảo mật | `docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md` |
| Code tham khảo cho Bước 1-3 của Part 2 | `PayrollJobAction.java` (`runJob()`, record `PayrollExpression`) |
| Code adapter decrypt+verify (không cần đụng vào) | `BouncyCastlePgpDecryptionAdapter.java` |
