# Thiết kế: Fraud Detection Service — Rule Engine sửa được qua Excel

> Tài liệu này là bản thiết kế chi tiết cho module `fraud-detection-service` trong dự án `Core Banking Transaction Service` (mục 05), áp dụng kiến thức từ mục `04b` (Design Patterns & Rule Engine) và mục `13` (AI Engineering).

## 1. Yêu cầu

| # | Yêu cầu | Ghi chú |
|---|---|---|
| 1 | Admin vận hành (ops) sửa ngưỡng/luật fraud qua file Excel, **không cần dev sửa code + deploy** | Core requirement |
| 2 | Rule mới phải được **validate + backtest** trước khi active — tránh 1 rule sai làm chặn nhầm hàng loạt giao dịch hợp lệ | Rủi ro nghiệp vụ nghiêm trọng nếu bỏ qua |
| 3 | Đổi rule **không downtime**, không ảnh hưởng giao dịch đang xử lý | High-concurrency requirement |
| 4 | Mọi thay đổi rule có **audit trail đầy đủ + rollback được** | Compliance requirement |
| 5 | Chỉ role `ops-admin` mới được sửa/active rule | Security requirement |
| 6 | Fraud check không được làm chậm luồng giao dịch chính (SLA P99 < 200ms) | Từ case study tổng hợp |

## 2. Lựa chọn công nghệ: Drools + Decision Table (Excel)

Drools có sẵn cơ chế **Decision Table**: file `.xls`/`.xlsx` với cấu trúc RuleSet/Condition/Action, được compile thành DRL lúc runtime bằng `drools-decisiontables`. Đây là lựa chọn phù hợp nhất vì:
- Admin chỉnh sửa file Excel quen thuộc, không cần biết DRL hay Java.
- Vẫn nằm trong hệ sinh thái Java/Spring Boot đã chọn, không cần thêm ngôn ngữ/hạ tầng mới.
- Có sẵn cơ chế compile-time validation (lỗi cú pháp bị bắt ngay khi build KieContainer, không chờ đến lúc chạy).

**Thiết kế theo hướng "engine-agnostic"**: bọc Drools sau 1 interface `RuleEngineAdapter` (Adapter pattern) — nếu sau này muốn đổi sang GoRules/OpenL Tablets, chỉ cần viết adapter mới, không đụng vào phần còn lại của hệ thống.

## 3. Kiến trúc tổng thể

```
                    ┌─────────────────────────────┐
                    │   Ops Admin Portal (UI)      │
                    │  - Download rule template     │
                    │  - Upload Excel rule file      │
                    │  - Xem kết quả backtest         │
                    │  - Confirm Activate / Rollback   │
                    └───────────────┬─────────────┘
                                    │ (role: ops-admin, mTLS/OAuth2)
                                    ▼
                    ┌─────────────────────────────┐
                    │   Rule Management API         │
                    │  POST /admin/fraud-rules/upload│
                    │  POST /.../{v}/backtest         │
                    │  POST /.../{v}/activate          │
                    │  POST /.../rollback                │
                    └───────────────┬─────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
┌───────────────┐       ┌───────────────────┐       ┌───────────────────┐
│ RuleFile          │      │ RuleValidator        │      │ BacktestRunner        │
│ Validator          │      │ (compile Excel →      │      │ (chạy rule mới trên   │
│ (định dạng file)  │      │  DRL, bắt lỗi cú pháp) │      │  sample giao dịch cũ) │
└───────────────┘       └───────────────────┘       └───────────────────┘
                                    │ (compile OK + admin confirm)
                                    ▼
                    ┌─────────────────────────────┐
                    │  RuleVersionRepository (PostgreSQL) │
                    │  - version, file (MinIO ref), status │
                    │  - uploaded_by, activated_by, ts        │
                    └───────────────┬─────────────┘
                                    │ publish {"versionId": N} qua Redis Pub/Sub
                                    ▼
              ┌──────────────────────────────────────────┐
              │  Redis channel: fraud-rules:activated        │
              │  (broadcast tới MỌI pod đang subscribe)         │
              └──────┬──────────────┬──────────────┬──────┘
                     ▼               ▼               ▼
              ┌───────────┐   ┌───────────┐   ┌───────────┐
              │  Pod A       │   │  Pod B       │   │  Pod C       │
              │ RuleEngine  │   │ RuleEngine  │   │ RuleEngine  │
              │ Manager       │   │ Manager       │   │ Manager       │
              │ (AtomicRef)  │   │ (AtomicRef)  │   │ (AtomicRef)  │
              │ + @Scheduled│   │ + @Scheduled│   │ + @Scheduled│
              │  poll 30s     │   │  poll 30s     │   │  poll 30s     │
              └───────────┘   └───────────┘   └───────────┘
                                    │
                                    ▼
   ══════════ Fraud Detection Pipeline (Chain of Responsibility) ══════════
   TransactionEvent (từ Kafka, sau khi transaction-service xử lý xong)
        │
        ▼
   ┌─────────────┐   ┌─────────────────┐   ┌───────────────┐   ┌──────────────┐
   │ FastPath      │→│ RuleEngineCheck    │→│ AiScoringCheck  │→│ FinalDecision   │
   │ Handler         │  │ Handler                │  │ Handler (mục 13)   │  │ Handler            │
   │ (hard limit,   │  │ (Drools, dùng rule    │  │ (async, không       │  │ (tổng hợp risk    │
   │  velocity đơn  │  │  từ Excel, có thể      │  │  block critical path)│  │  score, publish     │
   │  giản, rẻ)      │  │  đổi runtime)           │  │                         │  │  FraudDecisionMade) │
   └─────────────┘   └─────────────────┘   └───────────────┘   └──────────────┘
```

## 4. Cấu trúc file Excel Decision Table (mẫu rút gọn)

Admin chỉ chỉnh sửa vùng dữ liệu, không đụng vào 3 dòng header đầu (định dạng chuẩn Drools):

| RuleSet | fraud-rules | | | | | |
|---|---|---|---|---|---|---|
| RuleTable | FraudThresholdRules | | | | | |
| | CONDITION | CONDITION | CONDITION | ACTION | ACTION | |
| | Số tiền giao dịch >= | Quốc gia thuộc | Số lần GD trong 1h > | Risk Score | Quyết định | Ghi chú |
| RuleId=1 | 50000000 | - | - | 40 | FLAG | Giao dịch lớn |
| RuleId=2 | - | "RU","KP","IR" | - | 90 | BLOCK | Quốc gia rủi ro cao |
| RuleId=3 | - | - | 5 | 60 | FLAG | Giao dịch dồn dập bất thường |
| RuleId=4 | 20000000 | - | 3 | 80 | BLOCK | Kết hợp: tiền lớn + tần suất cao |

Admin thêm dòng mới = thêm rule mới. Xoá điều kiện (để trống ô) = điều kiện đó không áp dụng cho rule đó. **Không cần biết Drools/DRL/Java để đọc hiểu và sửa bảng này.**

## 5. Luồng cập nhật rule (từ upload đến production)

```
1. Admin tải template Excel mẫu từ Admin Portal
2. Admin sửa/thêm rule, upload file lên qua API (POST /admin/fraud-rules/upload)
3. RuleFileValidator kiểm tra định dạng (đúng cấu trúc RuleSet/Condition/Action)
4. RuleValidator compile Excel → DRL bằng Drools KieBuilder
      → NẾU LỖI: trả về chi tiết lỗi (dòng nào, cột nào), KHÔNG lưu version
      → NẾU OK: lưu version mới với status = DRAFT, file gốc lưu vào MinIO
5. Admin bấm "Backtest" → BacktestRunner chạy rule mới trên N giao dịch mẫu
   gần nhất (từ read-replica/data warehouse), so sánh với kết quả rule hiện tại:
      - Bao nhiêu giao dịch đổi kết quả (PASS→BLOCK, BLOCK→PASS)?
      - Tỷ lệ block tăng/giảm bao nhiêu % so với baseline?
6. Admin xem báo cáo backtest → quyết định Activate hoặc chỉnh sửa tiếp
7. Admin bấm "Activate" (yêu cầu xác nhận + có thể cần 2-person approval
   cho rule ảnh hưởng lớn — tương tự 4-eyes principle trong banking)
8. RuleEngineManager build KieContainer mới trong background,
   swap AtomicReference khi build xong (không lock, không downtime)
9. Version cũ chuyển status = INACTIVE (vẫn giữ lại, không xoá)
10. Ghi audit_log (ai, khi nào, version nào, kèm diff tóm tắt) + publish
    event RuleActivated lên Kafka cho các service quan tâm (compliance, monitoring)
```

### Rollback
`POST /admin/fraud-rules/rollback` → chọn version cũ → build lại KieContainer từ file đã lưu → swap ngay lập tức. Vì mọi version đều lưu trong MinIO + metadata trong Postgres, rollback không cần admin upload lại file.

## 6. Design Pattern áp dụng — vai trò cụ thể

| Pattern | Vai trò trong thiết kế này |
|---|---|
| **Adapter** | `RuleEngineAdapter` bọc Drools KieContainer — cho phép đổi engine (GoRules, OpenL Tablets) sau này mà không sửa pipeline. Cũng dùng để chuẩn hoá input từ nhiều nguồn (transaction-service, device-fingerprint-service, geo-ip-service) về 1 `FraudContext` object thống nhất. |
| **Chain of Responsibility** | Pipeline xử lý fraud: FastPath → RuleEngine → AI Scoring → FinalDecision. Mỗi handler có thể dừng sớm (short-circuit) nếu đã đủ tự tin ra quyết định — tối ưu latency vì không phải giao dịch nào cũng cần chạy hết pipeline. |
| **Builder** | `FraudContextBuilder` build object `FraudContext` từ nhiều nguồn dữ liệu (thông tin giao dịch, lịch sử tài khoản, thông tin thiết bị) — nhiều field optional, tránh constructor khổng lồ. |
| **Strategy** | Nếu có nhiều "chế độ" chấm điểm rủi ro (VD: chế độ nghiêm ngặt cho tài khoản mới, chế độ bình thường cho tài khoản lâu năm) — implement như các `RiskScoringStrategy` khác nhau, chọn theo `AccountRiskProfile`. |
| **Decorator** | Bọc lời gọi `RuleEngineAdapter.evaluate()` bằng decorator đo latency, log, và circuit breaker — nếu rule engine lỗi/chậm bất thường, decorator có thể fallback về FastPath-only mà không crash toàn hệ thống. |
| **Observer (qua Kafka event)** | `FinalDecisionHandler` publish `FraudDecisionMade` — các service khác (notification, case-management cho đội vận hành review, audit) tự lắng nghe, không coupling trực tiếp vào fraud-detection-service. |
| **Factory** | `RuleEngineManager` là nơi duy nhất biết cách build `KieContainer` từ file Excel — các phần khác chỉ gọi `ruleEngineAdapter.evaluate(context)`, không biết chi tiết Drools bên dưới. |

## 7. Xử lý concurrency & đa instance khi hot-reload rule (liên hệ mục 01 — Java Core)

### 7.1. Trong 1 instance: vì sao `AtomicReference` đảm bảo zero-downtime

```java
public class RuleEngineManager {
    private final AtomicReference<KieContainer> active = new AtomicReference<>();

    public FraudResult evaluate(FraudContext ctx) {
        KieContainer container = active.get();       // (1) snapshot cục bộ, chụp 1 lần duy nhất
        KieSession session = container.newKieSession();
        try {
            session.insert(ctx);
            session.fireAllRules();
            return ctx.getResult();
        } finally {
            session.dispose();
        }
    }

    public void reload(long versionId) {
        KieContainer newContainer = buildFromVersion(versionId); // (2) build ở background, không chặn request nào
        KieContainer old = active.getAndSet(newContainer);       // (3) swap atomic, tức thời
        scheduleDispose(old, Duration.ofSeconds(30));             // (4) dọn container cũ sau grace period
    }
}
```

Cơ chế đảm bảo an toàn:
- Mỗi request **chụp snapshot local 1 lần** ở bước (1) — dù `active` bị swap giữa lúc request đang chạy, request đó vẫn giữ tham chiếu tới `KieContainer` cũ (Java không GC khi còn ai giữ reference) → **không request nào bị gián đoạn**.
- Build container mới **tách biệt hoàn toàn khỏi hot path** — chỉ thao tác `getAndSet()` chạm biến chia sẻ, cực rẻ, không lock.
- `active` luôn trỏ tới container hợp lệ (cũ hoặc mới), không bao giờ ở trạng thái dở dang.
- **Giới hạn**: đây chỉ đảm bảo an toàn *trong phạm vi 1 JVM/1 pod*. Không tự động lan sang các pod khác — xem 7.2.

### 7.2. Đa instance: Redis Pub/Sub (broadcast) + Polling Reconciliation (lưới an toàn)

Mỗi pod chạy 1 `RuleEngineManager` độc lập với `AtomicReference` riêng trong bộ nhớ của nó — swap ở pod A không tự lan sang pod B. Giải pháp 2 lớp:

**Lớp 1 — Push qua Redis Pub/Sub (nhanh)**
Khi admin activate version mới, service publish 1 tín hiệu nhẹ (chỉ chứa `versionId`, không nhét nội dung rule) lên channel Redis — mọi pod đang subscribe channel đó nhận được **đồng thời** (Redis Pub/Sub broadcast tới mọi subscriber, không cần "lách" như Kafka consumer group):

```java
// Khi activate (chỉ chạy 1 lần, ở service xử lý API activate)
redisTemplate.convertAndSend("fraud-rules:activated",
    "{\"versionId\": " + versionId + "}");

// Mỗi pod, đăng ký lúc startup
@EventListener(ApplicationReadyEvent.class)
void subscribeRuleUpdates() {
    redisMessageListener.addMessageListener((message, pattern) -> {
        long versionId = parseVersionId(message);
        ruleEngineManager.reload(versionId);   // build container mới từ versionId, rồi swap AtomicReference
    }, new ChannelTopic("fraud-rules:activated"));
}
```

**Lớp 2 — Poll định kỳ (lưới an toàn, giữ nguyên bắt buộc)**
Redis Pub/Sub là **fire-and-forget** — pod nào mất kết nối/đang restart đúng lúc publish sẽ **mất tín hiệu vĩnh viễn**, không có gì để đọc lại. Vì vậy job polling vẫn phải giữ:

```java
@Scheduled(fixedDelay = 30_000)
void reconcile() {
    long dbActiveVersion = ruleVersionRepository.findActiveVersionId();
    if (dbActiveVersion != ruleEngineManager.currentVersionId()) {
        ruleEngineManager.reload(dbActiveVersion);  // tự đồng bộ nếu lệch, dù Pub/Sub có bị miss
    }
}
```

**Khởi động pod mới**: luôn pull trực tiếp version đang ACTIVE từ DB trước (không chờ Pub/Sub, không đọc "lịch sử" nào cả vì Pub/Sub không có persistence) — Redis Pub/Sub chỉ dùng để rút ngắn độ trễ giữa lúc admin activate và lúc pod áp dụng, còn nguồn sự thật luôn là `RuleVersionRepository`.

```
Pod startup:
  1. Pull ngay: query RuleVersionRepository.findActiveVersionId() → build container ban đầu
  2. Subscribe Redis channel "fraud-rules:activated" → reload ngay khi có tín hiệu (độ trễ ~vài trăm ms)
  3. Background @Scheduled poll DB mỗi 30s → lưới an toàn nếu bước 2 bị miss (Redis restart, network drop...)
```

### 7.3. Vì sao Redis Pub/Sub thay vì Kafka cho đúng use case này
Kafka làm broadcast bằng cách "lách" (mỗi pod tự đặt `group.id` riêng), phải chủ động bỏ qua tính năng cốt lõi của Kafka (durable log, replay — set `auto.offset.reset=latest`, không commit offset) mới dùng được cho đúng nhu cầu này. Redis Pub/Sub có ngữ nghĩa broadcast gốc, nhẹ hơn về vận hành, và hệ thống đã có Redis sẵn trong stack (rate-limit/Lua). **Kafka vẫn giữ lại** cho luồng cần durable event log thật sự — `TransactionCompleted`/Outbox Pattern.

### 7.4. Về "eventual consistency toàn fleet"
Độ trễ giữa các pod dùng rule mới thường ~vài trăm ms (Redis publish + build KieContainer), trường hợp Pub/Sub bị miss thì tối đa 1 chu kỳ poll (~30s). Chấp nhận được vì:
- Mỗi giao dịch ghi kèm `rule_version_id` thực tế đã dùng ngay tại thời điểm evaluate — audit vẫn chính xác tuyệt đối dù 2 giao dịch cạnh nhau ở 2 pod có thể dùng 2 version khác nhau trong vài giây chuyển tiếp.
- Nếu có nhu cầu hiếm gặp cần đồng bộ tuyệt đối tức thời trên mọi pod (VD: block khẩn cấp 1 quốc gia đang bị tấn công), nên xử lý ở tầng API Gateway/WAF với cấu hình tập trung, không đặt yêu cầu này lên rule engine.

**Phương án nâng cấp nếu sau này thấy Pub/Sub thuần quá mong manh**: Redis Streams (`XADD`/`XREAD`) — có persistence nhẹ, pod đọc từ vị trí cuối đã xử lý dù mất kết nối tạm thời, vẫn nhẹ hơn Kafka.

## 8. Bảng dữ liệu chính (PostgreSQL)

```sql
CREATE TABLE fraud_rule_version (
    id              BIGSERIAL PRIMARY KEY,
    version_no      INT NOT NULL,
    file_object_key TEXT NOT NULL,        -- reference tới MinIO
    status          VARCHAR(20) NOT NULL, -- DRAFT, BACKTESTED, ACTIVE, INACTIVE
    uploaded_by     VARCHAR(100) NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_by    VARCHAR(100),
    activated_at    TIMESTAMPTZ,
    backtest_summary JSONB,               -- kết quả backtest, tham chiếu báo cáo
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

## 9. API Endpoints (Admin, cần role `ops-admin`)

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/admin/fraud-rules/template` | Tải file Excel mẫu |
| POST | `/admin/fraud-rules/upload` | Upload file mới, trả về `version_id` nếu compile OK |
| POST | `/admin/fraud-rules/{versionId}/backtest` | Chạy backtest, trả báo cáo diff |
| POST | `/admin/fraud-rules/{versionId}/activate` | Kích hoạt version (yêu cầu xác nhận) |
| POST | `/admin/fraud-rules/rollback?to={versionId}` | Rollback về version cũ |
| GET | `/admin/fraud-rules/history` | Xem lịch sử version + audit log |

## 10. Điểm cần chú ý (Pitfalls)

- **Không backtest trước khi active** là rủi ro lớn nhất — 1 rule sai (VD: gõ nhầm dấu phẩy thành 5.000.000 thay vì 50.000.000) có thể chặn nhầm hàng loạt giao dịch hợp lệ trong vài giây trước khi ai đó nhận ra.
- **Không giới hạn quyền**: nếu bất kỳ ai cũng sửa được rule (không chỉ `ops-admin`), đây là lỗ hổng bảo mật nghiêm trọng — 1 rule ác ý có thể cho phép giao dịch gian lận đi qua. Nên cân nhắc **4-eyes principle** (2 người duyệt) cho rule ảnh hưởng lớn.
- **Build KieContainer đồng bộ trên request thread** sẽ làm nghẽn — luôn build ở background, swap khi xong.
- **Không giữ lại version cũ** khiến rollback không thể thực hiện nhanh khi rule mới gây sự cố ở production.
- **File Excel không có validate ngữ nghĩa** (chỉ check định dạng, không check rule có logic vô lý như risk score âm) — nên thêm business validation layer riêng ngoài compile-check của Drools.
- **Chỉ dựa vào Redis Pub/Sub mà bỏ qua polling reconciliation**: Pub/Sub là fire-and-forget, pod mất kết nối đúng lúc publish sẽ lệch version vĩnh viễn cho tới lần deploy sau nếu không có job poll định kỳ đối chiếu lại với DB.
- **Nhét nội dung rule trực tiếp vào message Redis** thay vì chỉ gửi `versionId`: làm message lớn không cần thiết, và tạo 2 nguồn sự thật (message vs DB) dễ lệch nhau — luôn để DB/MinIO là nguồn sự thật duy nhất, Pub/Sub chỉ là tín hiệu "có gì đó đổi".

## 11. Bài tập thực hành mở rộng
1. Implement `RuleEngineAdapter` interface + `DroolsRuleEngineAdapter`, viết test chứng minh hot-swap không làm mất/lỗi request đang xử lý đồng thời (dùng nhiều thread giả lập giao dịch trong lúc reload rule).
2. Implement `BacktestRunner` so sánh kết quả rule cũ vs rule mới trên tập giao dịch mẫu, xuất báo cáo dạng JSON (số lượng thay đổi, % tăng/giảm block rate).
3. Thử viết thêm `GoRulesEngineAdapter` implement cùng interface — chứng minh Adapter pattern cho phép đổi engine mà không sửa `FraudDetectionPipeline`.
4. Dựng 3 instance của `fraud-detection-service` cục bộ (docker-compose), test kịch bản: publish `{"versionId": N}` qua Redis, đo thời gian tất cả instance đồng bộ xong; sau đó tắt Redis giữa lúc publish để test job polling reconciliation tự sửa lại đúng version sau tối đa 30s.
