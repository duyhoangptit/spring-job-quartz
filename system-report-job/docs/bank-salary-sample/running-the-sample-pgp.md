# Chạy thử BANK_SALARY_PAYROLL với file PGP (company FPT Software)

Ví dụ end-to-end: bật `pgpEncrypted` cho JobDefinition `BANK_SALARY_PAYROLL` của công ty
`FPT_SOFTWARE`, dùng key PGP thật (sinh bằng `gpg`) để encrypt+sign file CSV lương, rồi để job tự
decrypt + verify chữ ký trước khi giải ngân — xem thiết kế đầy đủ tại
`docs/superpowers/specs/2026-08-27-company-pgp-file-decryption-design.md`.

Giả định: đã chạy `running-the-sample.md` ít nhất 1 lần trước đó (đã có JobDefinition + Task cho
`BANK_SALARY_PAYROLL`/`FPT_SOFTWARE`, đã có file CSV mẫu plaintext). Cần `gpg` CLI (`brew install
gnupg` nếu chưa có).

## 1. Sinh key pair PGP cho 2 bên (dùng GNUPGHOME riêng, không đụng keyring cá nhân)

```bash
export GNUPGHOME=/tmp/pgp-demo-gnupg   # đường dẫn NGẮN — GNUPGHOME dài quá sẽ lỗi
                                        # "File name too long" khi tạo socket của gpg-agent
mkdir -p "$GNUPGHOME" && chmod 700 "$GNUPGHOME"

cat > /tmp/tpbank-key.batch <<'EOF'
%no-protection
Key-Type: RSA
Key-Length: 2048
Name-Real: TPBank Payroll Receiving
Name-Email: payroll-receiving@tpbank.example
Expire-Date: 0
%commit
EOF

cat > /tmp/fpt-key.batch <<'EOF'
%no-protection
Key-Type: RSA
Key-Length: 2048
Name-Real: FPT Software Payroll Sender
Name-Email: payroll@fptsoftware.example
Expire-Date: 0
%commit
EOF

gpg --batch --generate-key /tmp/tpbank-key.batch
gpg --batch --generate-key /tmp/fpt-key.batch
gpg --list-keys
```

`TPBank Payroll Receiving` đóng vai **ngân hàng** (bên nhận/decrypt — private key của nó phải nạp
vào `company_pgp_key_config.bank_private_key_encrypted`). `FPT Software Payroll Sender` đóng vai
**công ty** (bên gửi/encrypt+sign — chỉ public key của nó cần thiết cho ngân hàng, để verify chữ ký).

`%no-protection` sinh key không passphrase — chỉ để demo nhanh. Với key thật ngoài đời, key của
ngân hàng nên có passphrase và `bank_key_passphrase_encrypted` sẽ là passphrase đó thay vì chuỗi
placeholder.

## 2. Đăng ký PGP key config cho `FPT_SOFTWARE`

```bash
export GNUPGHOME=/tmp/pgp-demo-gnupg
TPBANK_FPR=<fingerprint của "TPBank Payroll Receiving", lấy từ gpg --list-keys>
FPT_FPR=<fingerprint của "FPT Software Payroll Sender">

gpg --armor --export-secret-keys "$TPBANK_FPR" > /tmp/tpbank-private.asc
gpg --armor --export "$FPT_FPR" > /tmp/fpt-public.asc

python3 -c "
import json
payload = {
    'companyCode': 'FPT_SOFTWARE',
    'bankPrivateKeyArmored': open('/tmp/tpbank-private.asc').read(),
    'bankKeyPassphrase': 'no-passphrase-demo-key',
    'companyPublicKeyArmored': open('/tmp/fpt-public.asc').read(),
}
open('/tmp/pgp-config-payload.json', 'w').write(json.dumps(payload))
"

curl -X POST http://localhost:8081/api/company-pgp-key-configs \
  -H "Content-Type: application/json" \
  --data @/tmp/pgp-config-payload.json
```

Response không bao giờ chứa lại private key/passphrase — chỉ `companyCode`, `keyFingerprint`,
`active`. Muốn xoay vòng key: gọi lại `PUT /api/company-pgp-key-configs/FPT_SOFTWARE` với key mới.

## 3. Encrypt + sign file CSV lương

```bash
export GNUPGHOME=/tmp/pgp-demo-gnupg
cd system-report-job

# File nguồn: FPT_PAYROLL_<target-pay-date>.csv (đã có sẵn hoặc sinh bằng
# scripts/generate-fpt-payroll-csv.py như running-the-sample.md bước 1)
gpg --batch --yes \
  --local-user "FPT Software Payroll Sender" \
  --trust-model always \
  --recipient "TPBank Payroll Receiving" \
  --armor --sign --encrypt \
  --output docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv.pgp \
  docs/bank-salary-sample/sample-data/FPT_PAYROLL_<target-pay-date>.csv
```

Đúng thứ tự thật ngoài đời: **công ty** (FPT Software) encrypt bằng public key của **ngân hàng**,
sign bằng private key của chính họ — file `.csv.pgp` là thứ họ thực sự gửi qua.

## 4. Bật `pgpEncrypted` trên JobDefinition

```bash
curl -X PUT http://localhost:8081/api/job-definitions/<job-definition-id> \
  -H "Content-Type: application/json" \
  --data '{
    "jobType": "BANK_SALARY_PAYROLL",
    "expression": "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"<đường dẫn tuyệt đối tới sample-data>\",\"countryCode\":\"VN\",\"branchId\":\"ALL\",\"payDayOfMonth\":<ngày trong tháng>,\"pgpEncrypted\":true}",
    "description": "Chuyển lương hàng loạt FPT Software (PGP encrypted)"
  }'
```

`pgpEncrypted: true` khiến `PayrollJobAction` tìm file `FPT_PAYROLL_<targetPayDate>.csv.pgp` (thay
vì `.csv`), gọi `DecryptCompanyFileUseCase` để decrypt + verify trước khi build `JobParameters` —
`PayrollBatchConfig` (tasklet/reader) không đổi gì, vẫn đọc `csvFilePath` như file CSV plaintext
bình thường.

`payDayOfMonth` phải khớp (hoặc `resolveTargetPayDate` phải resolve ra) đúng ngày hôm nay để
`trigger-now` thực sự chạy batch thay vì chỉ log rồi bỏ qua — xem bước 3 của
`running-the-sample.md`.

## 5. Trigger và kiểm tra kết quả

```bash
curl -X POST http://localhost:8081/api/tasks/trigger-now/<task-id>
```

```sql
SELECT * FROM payroll_batch_run ORDER BY started_at DESC LIMIT 1;
SELECT status, COUNT(*) FROM payroll_disbursement WHERE batch_run_id = <id> GROUP BY status;
```

Log ứng dụng in đúng job param `csvFilePath` là một **file tạm đã decrypt**
(`<uuid>.decrypted` trong `${java.io.tmpdir}`), không phải file `.csv.pgp` gốc — bằng chứng decrypt
đã chạy trước khi Spring Batch bắt đầu đọc. File tạm này bị xoá ngay sau khi job kết thúc (thành
công hay thất bại đều xoá) — kiểm tra lại thư mục temp sẽ không còn file đó.

## 6. Dọn dẹp

```bash
rm -rf /tmp/pgp-demo-gnupg /tmp/tpbank-key.batch /tmp/fpt-key.batch \
       /tmp/pgp-config-payload.json /tmp/tpbank-private.asc /tmp/fpt-public.asc
```

Private key của ngân hàng chỉ tồn tại trong DB dạng đã seal (AES-256-GCM qua
`PgpKeyMaterialCipher`) — không có bản plaintext nào cần dọn thêm ngoài các file tạm ở trên.

## Đã chạy thử thành công (2026-08-27)

- 2 key pair RSA-2048 sinh bằng `gpg --batch --generate-key`, đăng ký cho `FPT_SOFTWARE` qua
  `POST /api/company-pgp-key-configs`.
- File `FPT_PAYROLL_2026-08-27.csv.pgp` (encrypt cho TPBank, sign bởi FPT Software) đã được job
  decrypt + verify chữ ký thành công — job log ghi nhận `csvFilePath` là file `.decrypted` tạm.
- `payroll_batch_run`: 30.000 nhân viên, `COMPLETED`; `payroll_disbursement`: 29.703 `SUCCESS` +
  297 `SKIPPED` (dòng lỗi cố tình trong file mẫu) — khớp chính xác với kết quả chạy plaintext trước
  đó (`FPT_PAYROLL_2026-08-25.csv`), chứng minh nội dung sau decrypt giống hệt bản gốc.
- File tạm decrypt bị xoá sạch sau khi job hoàn tất — verify bằng cách kiểm tra lại `${java.io.tmpdir}`.
