# Design: fraud-detection-service — project scaffold & rule management integration

Status: Approved by user (2026-08-08)
Source design: `files/fraud-detection-service-design.md` (mục 05 learning roadmap)

## 1. Mục đích

Tạo project Spring Boot cho `fraud-detection-service`: quản lý version của rule file Excel
(Drools Decision Table) dùng cho fraud detection, và chạy pipeline chấm điểm fraud cho giao
dịch. Service **không tự lưu trữ file** — mọi upload/download file đi qua `file-platform`,
một service dùng chung cho toàn hệ thống microservice, chuyên trách quản lý file
(upload/download/scan) cho tất cả các service khác.

## 2. Tech stack (đã chốt)

| Thành phần | Lựa chọn |
|---|---|
| Ngôn ngữ / JDK | Java 21 |
| Framework | Spring Boot 4.1 |
| Build tool | Maven |
| Database | PostgreSQL |
| Rule engine | Drools (`drools-decisiontables`) — compile Excel Decision Table → DRL runtime |
| File storage | **Không dùng trực tiếp** — bỏ MinIO khỏi stack, uỷ quyền hoàn toàn cho `file-platform` |
| Messaging | Kafka (consume `TransactionEvent`, publish `RuleActivated`, `FraudDecisionMade`) |

Đây là service độc lập trong kiến trúc microservice chuyên biệt hoá (mỗi service một
nhiệm vụ rõ ràng — `file-platform` chuyên file, `fraud-detection-service` chuyên rule +
scoring), không dùng chung datastore/file storage với service khác.

## 3. Project scaffold

```
core-banking-10000tps/
└── fraud-detection-service/        # Maven project độc lập, chưa có parent pom dùng chung
    ├── pom.xml
    ├── CLAUDE.md
    ├── src/main/java/com/corebanking/frauddetection/
    │   ├── ruleversion/    # entity FraudRuleVersion + audit log, upload/validate/backtest/activate/rollback
    │   ├── ruleengine/     # RuleEngineAdapter (interface) + DroolsRuleEngineAdapter, RuleEngineManager
    │   ├── pipeline/        # Chain of Responsibility: FastPath → RuleEngineCheck → AiScoringCheck → FinalDecision
    │   ├── fileplatform/    # FilePlatformClient (interface) + REST impl
    │   ├── kafka/           # consumers/producers
    │   ├── security/        # trusted-header auth filter, role ops-admin check
    │   ├── api/              # REST controllers + DTOs
    │   └── config/
    ├── src/main/resources/
    │   ├── application.yml
    │   └── templates/fraud-rule-template.xlsx   # template Excel tĩnh, không qua file-platform
    └── src/test/java/...
```

- groupId `com.corebanking`, artifactId `fraud-detection-service`
- Package-by-feature, single module — YAGNI (chưa có service thứ 2 nào cần dùng chung code
  nên chưa tách multi-module).

## 4. Data model (PostgreSQL)

```sql
CREATE TABLE fraud_rule_version (
    id                    BIGSERIAL PRIMARY KEY,
    version_no            INT NOT NULL,
    file_platform_ref_id  UUID NOT NULL,        -- fileAssetId trả về từ file-platform
    status                VARCHAR(20) NOT NULL, -- DRAFT, BACKTESTED, ACTIVE, INACTIVE
    uploaded_by           VARCHAR(100) NOT NULL,
    uploaded_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by          VARCHAR(100),
    activated_at          TIMESTAMPTZ,
    backtest_summary      JSONB,
    UNIQUE(version_no)
);

-- append-only, KHÔNG có UPDATE/DELETE permission cho application user
CREATE TABLE fraud_rule_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    version_id   BIGINT NOT NULL REFERENCES fraud_rule_version(id),
    action       VARCHAR(30) NOT NULL,  -- UPLOADED, VALIDATED, BACKTESTED, ACTIVATED, ROLLED_BACK
    performed_by VARCHAR(100) NOT NULL,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail       JSONB
);
```

Khác biệt so với thiết kế gốc: `file_object_key` (MinIO object key) → `file_platform_ref_id`
(UUID, là `fileAssetId` của file-platform).

## 5. Tích hợp file-platform

File-platform expose flow presigned URL (bypass backend cho phần byte), xác thực bằng
trusted header từ gateway (`X-User-Id`, 401 nếu thiếu/sai — theo `api-docs.json` đính kèm).
fraud-detection-service **forward các header tin cậy này** khi gọi file-platform, và giả
định fraud-detection-service cũng nhận cùng bộ header từ gateway cho auth của chính nó
(xem mục 7 — cần xác nhận tên header/role claim thật khi có tài liệu gateway).

**FilePlatformClient (interface, Adapter pattern):**
```java
public interface FilePlatformClient {
    InitiateUploadResult initiateUpload(String categoryCode, String originalFilename,
                                         String mimeType, long sizeBytes, String ownerType);
    FileAssetInfo confirmUpload(UUID fileAssetId, String checksumSha256);
    byte[] downloadFileBytes(UUID fileAssetId); // gọi GET download-url rồi GET presigned URL
}
```
- REST client impl (`RestClient`), base URL cấu hình qua `file-platform.base-url`.
- `categoryCode = "FRAUD_RULE_EXCEL"` — **pre-requisite chéo team**: bên vận hành
  file-platform phải tạo category này trước (mime type `.xlsx`/`.xls`, max size ví dụ 5MB),
  nếu không tồn tại thì `POST /api/files` trả 404. Cần confirm với chủ sở hữu file-platform
  trước khi implement Phase 2.

**Upload flow đã chọn — Proxy qua fraud-detection-service** (Admin Portal không biết gì về
file-platform, chỉ gọi fraud-detection-service):

```
Browser                fraud-detection-svc              file-platform
  |--initiate(meta)------->|                               |
  |                        |--POST /api/files-------------->|
  |                        |<--fileAssetId,uploadUrl---------|
  |<--uploadUrl-------------|                               |
  |--PUT bytes (direct)------------------------------------->| (MinIO, qua presigned URL)
  |--confirm(checksum)----->|                               |
  |                        |--POST /confirm----------------->|
  |                        |<--CLEAN-------------------------|
  |                        |--GET download-url-------------->|
  |                        |<--presigned GET------------------|
  |                        |--GET bytes (validate Drools)---->| (MinIO)
  |<--DRAFT version_id------|                               |
```

1. `POST /admin/fraud-rules/upload/initiate` (filename, mimeType, sizeBytes) → gọi
   `initiateUpload()` → trả `{fileAssetId, uploadUrl, expiresAt}` cho browser
2. Browser PUT bytes thẳng lên `uploadUrl` (bypass backend, đúng thiết kế presigned của
   file-platform)
3. `POST /admin/fraud-rules/upload/{fileAssetId}/confirm` (checksumSha256) → proxy
   `confirmUpload()` → ngay sau đó `downloadFileBytes()` để validate bằng Drools `KieBuilder`
   (compile Excel → DRL)
   - Lỗi cú pháp → 422 kèm chi tiết dòng/cột lỗi, **không lưu version**
   - OK → lưu `fraud_rule_version` (status=DRAFT, `file_platform_ref_id=fileAssetId`), ghi
     audit log `UPLOADED` + `VALIDATED`

Backtest/activate/rollback đều gọi lại `downloadFileBytes()` để lấy bytes build
`KieContainer` — không cache lâu dài vì presigned URL hết hạn nhanh (15-30 phút theo
file-platform), luôn gọi lại `download-url` mỗi lần cần.

## 6. API Endpoints (role `ops-admin`)

| Method | Endpoint | Ghi chú |
|---|---|---|
| GET | `/admin/fraud-rules/template` | Trả file mẫu — classpath resource tĩnh trong service |
| POST | `/admin/fraud-rules/upload/initiate` | Bước 1 upload — trả presigned `uploadUrl` |
| POST | `/admin/fraud-rules/upload/{fileAssetId}/confirm` | Bước 2 — validate + lưu DRAFT |
| POST | `/admin/fraud-rules/{versionId}/backtest` | Chạy backtest, trả báo cáo diff |
| POST | `/admin/fraud-rules/{versionId}/activate` | Kích hoạt version. 4-eyes approval (2 người duyệt) là **backlog**, không nằm trong MVP |
| POST | `/admin/fraud-rules/rollback?to={versionId}` | Rebuild KieContainer từ version cũ qua `downloadFileBytes()` |
| GET | `/admin/fraud-rules/history` | Lịch sử version + audit log |

## 7. Security (giả định — cần xác nhận lại)

fraud-detection-service nằm sau cùng API Gateway với file-platform, nhận cùng bộ trusted
header (`X-User-Id`, `X-User-Roles`) thay vì tự validate JWT. Admin endpoint chỉ cho phép
khi `X-User-Roles` chứa `ops-admin`. Khi gọi file-platform, forward nguyên `X-User-Id`
xuống downstream. **Giả định này cần xác nhận với tài liệu gateway thật** trước khi
implement Phase 6 — nếu sai, chỉ cần đổi lớp `security/` mà không ảnh hưởng phần còn lại.

## 8. Kafka topics (đặt tên tạm — chỉnh khi có event catalog thật)

- Consume: `transaction.events` (từ transaction-service) → nạp `FraudContext` cho pipeline
- Produce: `fraud.rule.activated` (khi activate/rollback thành công)
- Produce: `fraud.decision.made` (từ `FinalDecisionHandler`)

## 9. Concurrency / hot-reload (không đổi so với thiết kế gốc)

`RuleEngineManager` giữ `AtomicReference<KieContainer>` — build KieContainer mới ở
background thread, swap bằng `compareAndSet` khi build xong, không lock hot path. Giao dịch
đang chạy với KieContainer cũ tiếp tục hoàn tất bình thường. Build lỗi → không swap, giữ
nguyên version đang active.

## 10. Design Pattern mapping (không đổi so với thiết kế gốc)

| Pattern | Vai trò |
|---|---|
| Adapter | `RuleEngineAdapter` bọc Drools; `FilePlatformClient` bọc file-platform REST API |
| Chain of Responsibility | Pipeline FastPath → RuleEngine → AI Scoring → FinalDecision |
| Builder | `FraudContextBuilder` |
| Strategy | `RiskScoringStrategy` theo `AccountRiskProfile` |
| Decorator | Bọc `RuleEngineAdapter.evaluate()` đo latency/circuit breaker |
| Observer (Kafka) | `FraudDecisionMade` event, các service khác tự subscribe |
| Factory | `RuleEngineManager` là nơi duy nhất build `KieContainer` |

## 11. CLAUDE.md (file sẽ tạo tại `fraud-detection-service/CLAUDE.md`)

Nội dung: mục đích service, tech stack (mục 2), nguyên tắc "không tự quản lý file — luôn
qua file-platform" (mục 5), package structure + pattern mapping (mục 3, 10), data model
(mục 4), API + Kafka (mục 6, 8), dev conventions (`mvn clean verify`, Testcontainers cho
Postgres/Kafka trong test), pitfalls (mục 12), link ngược lại
`files/fraud-detection-service-design.md`.

## 12. Pitfalls (kế thừa từ thiết kế gốc + bổ sung)

- Không backtest trước khi active → rule sai chặn nhầm hàng loạt giao dịch.
- Không giới hạn role `ops-admin` → lỗ hổng bảo mật nghiêm trọng.
- Build `KieContainer` đồng bộ trên request thread → nghẽn luồng đánh giá fraud.
- Không giữ version cũ → rollback không thực hiện được khi có sự cố.
- File Excel chỉ được Drools compile-check (cú pháp), không check ngữ nghĩa (risk score âm,
  v.v.) → cần thêm business validation layer riêng.
- **Mới**: `categoryCode` chưa tồn tại ở file-platform (`FRAUD_RULE_EXCEL`) sẽ làm
  `initiateUpload()` fail 404 — phải confirm trước với team file-platform, không phải lỗi
  code.
- **Mới**: presigned download URL hết hạn 15-30 phút — không cache URL, luôn gọi lại
  `download-url` mỗi lần cần bytes (backtest, activate, rollback).

## 13. Phân giai đoạn implementation plan

| Phase | Nội dung | Deliverable |
|---|---|---|
| 0 | Project scaffold — Maven, package layout, `application.yml`, health check | `mvn clean verify` chạy được, service boot lên |
| 1 | Data model & Rule Version core — JPA entity, Flyway migration, repository, audit log writer, `GET /history` | CRUD version + audit hoạt động, chưa có Drools |
| 2 | Tích hợp file-platform — `FilePlatformClient`, `upload/initiate` + `upload/{id}/confirm` (lưu DRAFT, chưa validate ngữ nghĩa) | Upload file qua file-platform, version DRAFT được tạo |
| 3 | Drools `RuleEngineAdapter` + `RuleEngineManager` — compile validate khi confirm, hot-swap `AtomicReference<KieContainer>`, `activate`/`rollback` | Validate + activate/rollback hoạt động không downtime |
| 4 | `BacktestRunner` — chạy rule mới trên sample giao dịch cũ, so sánh, endpoint `backtest` | Báo cáo backtest JSON |
| 5 | Fraud Detection Pipeline — Kafka consumer `transaction.events`, Chain of Responsibility (FastPath→RuleEngine→AiScoring stub→FinalDecision), publish `fraud.decision.made` | Pipeline end-to-end chấm điểm giao dịch |
| 6 | Security & hardening — trusted-header auth filter, role `ops-admin` check, Decorator (latency/circuit breaker) quanh `RuleEngineAdapter`, SLA guard | Đáp ứng yêu cầu bảo mật + P99<200ms |

Implementation plan chi tiết cho từng phase (task-level) sẽ được viết ở bước tiếp theo
bằng skill `writing-plans`.

## 14. Open items cần xác nhận trước/khi implement (không blocking bước viết plan)

1. Tên category code + giới hạn mime/size thật cho `FRAUD_RULE_EXCEL` ở file-platform.
2. Tên header trusted-auth thật từ gateway (giả định `X-User-Id` / `X-User-Roles`).
3. Tên topic Kafka thật (nếu đã có event catalog chung cho hệ thống).
4. Có cần 4-eyes approval cho `activate` ngay ở MVP hay để backlog (hiện đang để backlog).
