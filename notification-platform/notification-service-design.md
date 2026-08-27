# Notification Service — Design Document
### Kafka-based Retry + DLQ + Circuit Breaker + Idempotent Reprocess
**Domain:** Banking (SMS / Email / Firebase Realtime)
**Version:** 1.0

---

## 1. Mục tiêu & Phạm vi

Notification Service chịu trách nhiệm gửi thông báo (SMS, Email, Firebase push) tới khách hàng thông qua các nhà cung cấp bên thứ 3 (3rd party gateway), với các yêu cầu:

- Xử lý bất đồng bộ qua Kafka, tách biệt theo channel (sms/email/firebase).
- Tự động retry khi gọi 3rd party thất bại (timeout, connection error) tối đa **3 lần** với backoff tăng dần.
- Phân loại lỗi retriable / non-retriable để tránh retry vô ích.
- Circuit breaker riêng theo từng provider để fail-fast khi provider down.
- Khi hết số lần retry → đẩy vào Dead Letter Queue (DLQ), lưu DB để operator xử lý thủ công.
- Đảm bảo idempotency xuyên suốt (không gửi trùng SMS/email cho khách hàng), có đầy đủ audit trail phục vụ compliance banking.

---

## 2. Kiến trúc tổng quan

```mermaid
flowchart LR
    subgraph Producer
        A[Core Banking / Caller Service]
    end

    subgraph Kafka
        T1[topic.notification.sms]
        T2[topic.notification.sms-retry-0/1/2]
        T3[topic.notification.sms-dlt]
        T4[topic.notification.email ...]
        T5[topic.notification.firebase ...]
    end

    subgraph Consumers
        C1[SMS Listener]
        C2[Email Listener]
        C3[Firebase Listener]
        DLT[DLT Handler]
    end

    subgraph Providers["3rd Party Providers"]
        P1[SMS Gateway A/B]
        P2[Email Provider]
        P3[Firebase Cloud Messaging]
    end

    subgraph DB["PostgreSQL"]
        R[(notification_request)]
        AT[(notification_attempt)]
        DQ[(notification_dlq)]
    end

    subgraph Ops
        OP[Operator Dashboard / Reprocess API]
    end

    A -- publish --> T1 & T4 & T5
    T1 --> C1 --> P1
    C1 -- fail --> T2 --> C1
    C1 -- exhausted --> T3 --> DLT --> DQ
    C1 --> AT
    A --> R
    OP -- reprocess --> T1
    OP --> DQ
```

---

## 3. Topic Design

| Topic | Mục đích | Partitions gợi ý | Ghi chú |
|---|---|---|---|
| `topic.notification.sms` | Request gốc cho SMS | 6+ | Key = `requestId` để đảm bảo order theo request |
| `topic.notification.sms-retry-0/1/2` | Retry tự động (Spring Kafka `@RetryableTopic` tự sinh) | 3 | Backoff tăng dần |
| `topic.notification.sms-dlt` | Dead Letter Topic | 1-3 | Consumer là `NotificationDltHandler` |
| `topic.notification.email` (+ retry/dlt tương tự) | Email | 3+ | SLA lỏng hơn SMS |
| `topic.notification.firebase` (+ retry/dlt) | Push notification | 3+ | |
| `topic.notification.otp` | OTP riêng, **không** qua retry topic chuẩn | 6+ | Fail-fast, xem mục 7 |

**Quy ước header message:**

| Header | Kiểu | Mô tả |
|---|---|---|
| `requestId` | String (UUID) | Định danh duy nhất của notification request |
| `idempotencyKey` | String | SHA-256 hash, dùng để chống trùng |
| `retry-count` | Int | Do Spring Kafka tự quản lý |
| `reprocessed-from-dlq-id` | Long (optional) | Có khi message được operator reprocess |
| `reprocessed-by` | String (optional) | operatorId |

---

## 4. Database Schema

```sql
CREATE TABLE notification_request (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    channel         VARCHAR(20) NOT NULL,      -- SMS, EMAIL, FIREBASE, OTP
    recipient       VARCHAR(255) NOT NULL,
    template_id     VARCHAR(50),
    payload         JSONB NOT NULL,
    priority        VARCHAR(10) DEFAULT 'NORMAL', -- HIGH, NORMAL, LOW
    status          VARCHAR(20) NOT NULL,      -- PENDING, SUCCESS, FAILED_PERMANENT, IN_DLQ
    business_date   DATE NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_attempt (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID NOT NULL REFERENCES notification_request(request_id),
    attempt_number  INT NOT NULL,
    provider_name   VARCHAR(50),
    status          VARCHAR(20) NOT NULL,      -- SUCCESS, FAILED, TIMEOUT, CIRCUIT_OPEN
    provider_response JSONB,
    error_code      VARCHAR(50),
    error_message   TEXT,
    called_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_ms     INT
);
CREATE INDEX idx_attempt_request_status ON notification_attempt(request_id, status);

CREATE TABLE notification_dlq (
    id                  BIGSERIAL PRIMARY KEY,
    request_id          UUID NOT NULL REFERENCES notification_request(request_id),
    channel             VARCHAR(20) NOT NULL,
    payload             JSONB NOT NULL,
    failure_reason      TEXT,
    dlq_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING, REPROCESSING, RESOLVED, ABANDONED
    reprocess_count     INT NOT NULL DEFAULT 0,
    moved_to_dlq_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),
    resolve_note        TEXT
);
CREATE INDEX idx_dlq_status ON notification_dlq(dlq_status);
```

---

## 5. Sequence Diagram — Flow 1: Happy Path (Success)

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Core Banking Service
    participant K as Kafka (topic.notification.sms)
    participant L as SmsNotificationListener
    participant CB as CircuitBreaker(sms-provider-a)
    participant P as SMS Provider
    participant DB as PostgreSQL

    Caller->>DB: INSERT notification_request (status=PENDING)
    Caller->>K: publish(NotificationMessage, key=requestId)
    K->>L: consume()
    L->>DB: check attempt SUCCESS exists? (idempotency guard)
    DB-->>L: false
    L->>CB: decorateSupplier(callProvider)
    CB->>P: send SMS (timeout=3s)
    P-->>CB: 200 OK
    CB-->>L: SmsResult(success)
    L->>DB: INSERT notification_attempt (status=SUCCESS)
    L->>DB: UPDATE notification_request SET status=SUCCESS
    L-->>K: ack offset
```

---

## 6. Sequence Diagram — Flow 2: Retry (Timeout) đến Success

```mermaid
sequenceDiagram
    autonumber
    participant K0 as topic.notification.sms
    participant L as SmsNotificationListener
    participant CB as CircuitBreaker
    participant P as SMS Provider
    participant DB as PostgreSQL
    participant KR as Spring RetryTopic Scheduler
    participant K1 as sms-retry-0 (delay 2s)
    participant K2 as sms-retry-1 (delay 4s)

    K0->>L: consume(attempt=1)
    L->>CB: callProvider()
    CB->>P: send SMS (timeout 3s)
    P--xCB: Timeout
    CB-->>L: throw SmsGatewayTimeoutException
    L->>DB: INSERT notification_attempt(status=TIMEOUT, attempt=1)
    L-->>K0: throw (retriable)
    Note over K0,KR: Spring Kafka @RetryableTopic bắt exception, publish sang retry topic kế tiếp
    KR->>K1: publish(msg, retry-count=1) sau backoff 2s
    K1->>L: consume(attempt=2)
    L->>DB: check attempt SUCCESS exists?
    DB-->>L: false
    L->>CB: callProvider()
    CB->>P: send SMS
    P--xCB: Connection error
    CB-->>L: throw SmsGatewayConnectionException
    L->>DB: INSERT notification_attempt(status=FAILED, attempt=2)
    L-->>K1: throw (retriable)
    KR->>K2: publish(msg, retry-count=2) sau backoff 4s
    K2->>L: consume(attempt=3)
    L->>CB: callProvider()
    CB->>P: send SMS
    P-->>CB: 200 OK
    CB-->>L: SmsResult(success)
    L->>DB: INSERT notification_attempt(status=SUCCESS, attempt=3)
    L->>DB: UPDATE notification_request SET status=SUCCESS
    L-->>K2: ack
```

---

## 7. Sequence Diagram — Flow 3: Retry hết lượt → DLQ

```mermaid
sequenceDiagram
    autonumber
    participant K2 as sms-retry-2 (attempt cuối)
    participant L as SmsNotificationListener
    participant CB as CircuitBreaker
    participant P as SMS Provider
    participant DB as PostgreSQL
    participant KR as Spring RetryTopic Scheduler
    participant KD as sms-dlt
    participant DLT as NotificationDltHandler
    participant Alert as Alerting (Slack/PagerDuty)

    K2->>L: consume(attempt=4, cuối cùng)
    L->>CB: callProvider()
    CB->>P: send SMS
    P--xCB: Timeout
    CB-->>L: throw SmsGatewayTimeoutException
    L->>DB: INSERT notification_attempt(status=TIMEOUT, attempt=4)
    L-->>K2: throw
    Note over K2,KR: Đã đạt maxAttempts=4 (1 gốc + 3 retry) -> route to DLT
    KR->>KD: publish(msg, headers[exceptionMessage, originalTopic])
    KD->>DLT: consume()
    DLT->>DB: INSERT notification_dlq(status=PENDING, failure_reason=...)
    DLT->>DB: UPDATE notification_request SET status=IN_DLQ
    DLT->>Alert: notify (nếu volume DLQ bất thường)
    DLT-->>KD: ack
```

---

## 8. Sequence Diagram — Flow 4: Circuit Breaker OPEN (fail-fast)

```mermaid
sequenceDiagram
    autonumber
    participant L as SmsNotificationListener
    participant CB as CircuitBreaker(sms-provider-a)
    participant P as SMS Provider A
    participant PB as SMS Provider B (backup)
    participant DB as PostgreSQL

    Note over CB: failure-rate 50% trong 20 call gần nhất -> CB chuyển OPEN
    L->>CB: callProvider()
    CB--xL: CallNotPermittedException (fail-fast, KHÔNG gọi network)
    L->>DB: INSERT notification_attempt(status=CIRCUIT_OPEN)
    alt Có provider backup
        L->>PB: sendWithFailover()
        PB-->>L: SmsResult(success)
        L->>DB: INSERT notification_attempt(status=SUCCESS, provider=B)
        L->>DB: UPDATE notification_request SET status=SUCCESS
    else Không có backup / backup cũng fail
        L-->>L: throw SmsGatewayUnavailableException (retriable)
        Note over L: đi vào flow retry như Flow 2/3
    end
```

---

## 9. Sequence Diagram — Flow 5: Manual Reprocess từ DLQ (Idempotent)

```mermaid
sequenceDiagram
    autonumber
    participant Op as Operator
    participant API as DlqReprocessService (REST API)
    participant DB as PostgreSQL
    participant K as topic.notification.sms
    participant L as SmsNotificationListener
    participant P as SMS Provider

    Op->>API: POST /dlq/{id}/reprocess
    API->>DB: SELECT notification_dlq FOR UPDATE (pessimistic lock)
    DB-->>API: dlq record (status=PENDING)

    alt status != PENDING
        API-->>Op: 409 Conflict - already processed
    else status == PENDING
        API->>DB: EXISTS attempt WHERE requestId AND status=SUCCESS ?
        alt Đã từng SUCCESS (network glitch khi ack trước đó)
            DB-->>API: true
            API->>DB: UPDATE dlq SET status=RESOLVED, note='already succeeded'
            API-->>Op: 200 Skipped, no resend
        else Chưa từng SUCCESS
            DB-->>API: false
            API->>DB: UPDATE dlq SET status=REPROCESSING, reprocess_count+=1
            API->>K: publish(msg, headers[reprocessed-by, reprocessed-from-dlq-id])
            API-->>Op: 202 Accepted - submitted

            K->>L: consume()
            L->>DB: EXISTS attempt WHERE requestId AND status=SUCCESS ? (double-guard)
            DB-->>L: false
            L->>P: send SMS
            P-->>L: 200 OK
            L->>DB: INSERT notification_attempt(status=SUCCESS)
            L->>DB: UPDATE notification_request SET status=SUCCESS
            L->>DB: UPDATE notification_dlq SET status=RESOLVED, resolved_at=now(), resolved_by=operatorId
        end
    end
```

---

## 10. Sequence Diagram — Flow 6: OTP riêng (Fail-fast, không qua DLQ retry chuẩn)

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Auth Service
    participant K as topic.notification.otp
    participant L as OtpNotificationListener (maxAttempts=1)
    participant CB as CircuitBreaker(sms-provider-a, timeout=short)
    participant P as SMS Provider
    participant DB as PostgreSQL
    participant User as End User (UI)

    Caller->>K: publish(OTP message, TTL ngắn)
    K->>L: consume()
    L->>CB: callProvider(timeout=1.5s)
    alt Success
        CB->>P: send OTP
        P-->>CB: 200 OK
        L->>DB: INSERT notification_attempt(SUCCESS)
        L->>DB: UPDATE request status=SUCCESS
    else Fail / Timeout / Circuit Open
        CB--xL: exception
        L->>DB: INSERT notification_attempt(FAILED)
        L->>DB: UPDATE request status=FAILED_PERMANENT
        Note over L: KHÔNG retry tự động qua Kafka -> OTP hết hạn nhanh
        User->>Caller: Bấm "Gửi lại OTP" (resend chủ động, có rate limit riêng)
        Caller->>K: publish OTP message mới (requestId mới)
    end
```

---

## 11. Trạng thái DLQ (State Machine)

```mermaid
stateDiagram-v2
    [*] --> PENDING: DLT handler lưu record
    PENDING --> REPROCESSING: Operator bấm Reprocess
    REPROCESSING --> RESOLVED: Gửi thành công / đã từng thành công
    REPROCESSING --> PENDING: Reprocess thất bại, quay lại chờ xử lý
    PENDING --> ABANDONED: Operator quyết định không gửi nữa
    RESOLVED --> [*]
    ABANDONED --> [*]
```

---

## 12. Cấu trúc code gợi ý (package layout)

```
notification-service/
├── config/
│   ├── KafkaRetryTopicConfig.java
│   ├── CircuitBreakerConfig.java
│   └── KafkaConsumerConfig.java
├── listener/
│   ├── SmsNotificationListener.java
│   ├── EmailNotificationListener.java
│   ├── FirebaseNotificationListener.java
│   ├── OtpNotificationListener.java
│   └── NotificationDltHandler.java
├── client/
│   ├── SmsProviderClient.java (Provider A + B failover)
│   ├── EmailProviderClient.java
│   └── FirebaseClient.java
├── service/
│   ├── DlqReprocessService.java
│   └── NotificationRequestService.java
├── repository/
│   ├── NotificationRequestRepository.java
│   ├── NotificationAttemptRepository.java
│   └── NotificationDlqRepository.java
├── entity/
│   ├── NotificationRequest.java
│   ├── NotificationAttempt.java
│   └── NotificationDlq.java
├── dto/
│   ├── NotificationMessage.java
│   └── ReprocessResult.java
├── exception/
│   ├── InvalidPhoneNumberException.java     (non-retriable)
│   ├── TemplateNotFoundException.java       (non-retriable)
│   ├── SmsGatewayTimeoutException.java      (retriable)
│   ├── SmsGatewayConnectionException.java   (retriable)
│   └── SmsGatewayUnavailableException.java  (retriable, circuit open)
├── controller/
│   └── DlqAdminController.java  (REST API cho dashboard operator)
└── NotificationServiceApplication.java
```

---

## 13. Checklist khi generate code từ tài liệu này

- [ ] Config `RetryTopicConfiguration` riêng cho từng channel (sms/email/firebase), maxAttempts=4, exponential backoff.
- [ ] Config riêng cho OTP: **không** dùng `@RetryableTopic`, xử lý fail-fast trong try/catch thông thường.
- [ ] Danh sách exception non-retriable đưa vào `exclude` của `@RetryableTopic`.
- [ ] Circuit breaker config riêng theo `instances` cho từng provider (sms-provider-a, sms-provider-b, email-provider, firebase).
- [ ] Idempotency guard 2 lớp: (1) trước khi publish reprocess ở `DlqReprocessService`, (2) trong consumer trước khi gọi 3rd party.
- [ ] Pessimistic lock (`SELECT ... FOR UPDATE`) khi đọc `notification_dlq` để tránh race condition reprocess.
- [ ] Toàn bộ `notification_attempt` là bảng ghi log immutable — không update, chỉ insert (audit trail).
- [ ] Mask số điện thoại/email khi log ra console/log file, chỉ giữ full trong DB có access control.
- [ ] Metric/alert: đếm số lượng bản ghi mới trong `notification_dlq` theo channel mỗi khoảng thời gian, cảnh báo khi vượt ngưỡng.
