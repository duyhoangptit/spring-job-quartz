# Tài Liệu Thiết Kế Timezone & Quy Trình EOD Trong Hệ Thống Banking

Tài liệu này đặc tả các nguyên tắc thiết kế, cấu hình kỹ thuật và quy trình xử lý thời gian đối với hệ thống ngân hàng (Banking) sử dụng nền tảng **Java Spring Boot, Jackson, Hibernate** và các hệ quản trị cơ sở dữ liệu phổ biến (**PostgreSQL, Oracle**).

---

## 1. Triết Lý Thiết Kế Timezone Trong Hệ Thống Banking

Hệ thống banking hiện đại tuân thủ nguyên tắc cốt lõi: **"Lưu trữ chuẩn hóa, Hiển thị địa phương hóa"** nhằm bảo toàn tính toàn vẹn dữ liệu, phục vụ kiểm toán và mở rộng quy mô đa quốc gia.

### 1.1. Sử dụng UTC (Coordinated Universal Time) làm gốc
*   **Database & Application Server:** Toàn bộ hạ tầng máy chủ và hệ cơ sở dữ liệu được cấu hình chạy trên mốc giờ UTC (GMT+0).
*   **Mục tiêu:** Loại bỏ hoàn toàn rủi ro sai lệch dữ liệu do Quy ước giờ mùa hè (DST - Daylight Saving Time) và xung đột múi giờ giữa các chi nhánh vùng miền.

### 1.2. Chuẩn hóa Định dạng Truyền tải Dữ liệu (API & Event)
*   **Giao tiếp hệ thống (REST, Kafka, ISO 8583):** Thời gian truyền nhận bắt buộc tuân theo chuẩn **ISO 8601** và phải bao gồm thông tin độ lệch múi giờ (Offset).
*   **Ví dụ:** `2026-08-09T23:30:00.000+07:00` hoặc `2026-08-09T16:30:00.000Z`.

### 1.3. Phân tách rạch ròi mốc Thời gian Vật lý và Thời gian Kế toán
*   **Thời gian Vật lý (Transaction/System Time):** Thời gian thực tế diễn ra giao dịch theo trục thời gian tuyến tính vũ trụ (lưu dưới dạng UTC).
*   **Thời gian Kế toán (Accounting/Financial Date):** Ngày ghi sổ tài chính trực thuộc một chi nhánh cụ thể. Mốc thời gian này được quản lý bằng nghiệp vụ (Date Flip) chứ không phụ thuộc vào giờ hệ thống.

---

## 2. Cấu Hình Tầng Ứng Dụng (Java Spring Boot & Jackson)

### 2.1. Thiết lập Múi giờ Mặc định cho JVM
Ép buộc ứng dụng Java luôn thực thi với timezone UTC ngay khi khởi động:

```java
@SpringBootApplication
public class BankingApplication {
    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(BankingApplication.class, args);
    }
}
```

### 2.2. Quy định Chọn Kiểu Dữ Liệu (Java 8 time)
*   `Instant`: Kiểu dữ liệu tối ưu nhất cho các trường kiểm toán hệ thống (`created_at`, `updated_at`). Luôn đại diện cho mốc UTC chuẩn hóa.
*   `OffsetDateTime`: Khuyên dùng cho các trường nghiệp vụ giao dịch (`transaction_time`). Giữ nguyên vẹn mốc thời gian kèm theo độ lệch múi giờ của client/chi nhánh nơi phát sinh.
*   `LocalDate`: Dùng cho ngày sinh, ngày hết hạn thẻ, hoặc ngày kế toán (`accounting_date`).
*   *Lưu ý:* Tuyệt đối **không** sử dụng `LocalDateTime` (thiếu thông tin múi giờ) và `java.util.Date` (lỗi thời, không thread-safe).

### 2.3. Cấu hình Jackson Serializer/Deserializer (`application.yml`)
Đảm bảo Jackson không tự ý ép múi giờ về giờ local của máy chủ khi chuyển đổi Object sang chuỗi JSON và ngược lại:

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      adjust-dates-to-context-time-zone: false
    date-format: yyyy-MM-dd'T'HH:mm:ss.SSSXXX
    time-zone: UTC
```

---

## 3. Cấu Hình Tầng Dữ Liệu (Database Connection & JPA/Hibernate)

### 3.1. Đối với PostgreSQL
Bắt buộc sử dụng kiểu dữ liệu `TIMESTAMPTZ` (Timestamp with time zone). Cấu hình chuỗi kết nối ép session luôn giao tiếp bằng UTC:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/banking_db?options=-c%20timezone=UTC
```

### 3.2. Đối với Oracle
Sử dụng kiểu dữ liệu `TIMESTAMP WITH TIME ZONE`. Thực hiện cấu hình thuộc tính driver và khởi tạo session zone:

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//localhost:1521/XEPDB1
    hikari:
      connection-init-sql: ALTER SESSION SET TIME_ZONE = 'UTC'
      data-source-properties:
        oracle.jdbc.timezoneAsRegion: false
```

### 3.3. Cấu hình Mapping Hibernate
Ép Hibernate chuẩn hóa dữ liệu ngày tháng về UTC trước khi thực hiện câu lệnh SQL binding parameters:

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
```

---

## 4. Quy Trình Xử Lý Báo Cáo Cuối Ngày (EOD) Đa Chi Nhánh

Khi ngân hàng hoạt động đa quốc gia (Ví dụ: Chi nhánh Việt Nam `UTC+7` và Chi nhánh London `UTC+0`), quy trình chốt sổ kế toán (Cut-off) sẽ diễn ra độc lập dựa trên cấu trúc dữ liệu sau:

### 4.1. Thiết kế Bảng Giao Dịch (Transaction Table)
Cấu trúc bảng phân tách cấu trúc thời gian vật lý (`transaction_time`) và thời gian kế toán (`accounting_date`).

```sql
CREATE TABLE transactions (
    transaction_id VARCHAR(36) PRIMARY KEY,
    account_number VARCHAR(20) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    branch_id VARCHAR(10) NOT NULL,
    transaction_time TIMESTAMP WITH TIME ZONE NOT NULL, -- Định dạng UTC, phục vụ Audit
    accounting_date DATE NOT NULL,                      -- Định dạng Ngày, phục vụ Kế toán
    branch_timezone VARCHAR(50) NOT NULL,               -- Lưu thông tin múi giờ gốc của chi nhánh
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_tx_branch_accounting ON transactions (branch_id, accounting_date);
CREATE INDEX idx_tx_time_utc ON transactions (transaction_time);
```

### 4.2. Viết Truy Vấn Quy Đổi Dynamic Theo Múi Giờ Chi Nhánh (JPA Specification)
Khi chi nhánh yêu cầu truy vấn báo cáo theo một ngày làm việc cụ thể, hệ thống tự động tính toán khoảng `Instant` (UTC) tương ứng nhằm tận dụng tối đa Database Index:

```java
public class DateTimeUtils {
    public static Range<Instant> getUtcRangeForBranch(LocalDate targetDate, String zoneIdStr) {
        ZoneId branchZone = ZoneId.of(zoneIdStr);
        ZonedDateTime startOfLocalDay = targetDate.atStartOfDay(branchZone);
        ZonedDateTime endOfLocalDay = targetDate.atTime(LocalTime.MAX).atZone(branchZone);
        return Range.between(startOfLocalDay.toInstant(), endOfLocalDay.toInstant(), Comparator.naturalOrder());
    }
}
```

```java
public class TransactionSpecification {
    public static Specification<Transaction> isDetailInAccountingDate(
            LocalDate targetDate, String branchId, String zoneIdStr) {
        return (root, query, cb) -> {
            Range<Instant> utcRange = DateTimeUtils.getUtcRangeForBranch(targetDate, zoneIdStr);
            Predicate sameBranch = cb.equal(root.get("branchId"), branchId);
            Predicate withinUtcRange = cb.between(
                root.get("transactionTime"), 
                utcRange.getMinimum(), 
                utcRange.getMaximum()
            );
            return cb.and(sameBranch, withinUtcRange);
        };
    }
}
```

---

## 5. Xử Lý Batch Job Song Song Phân Tán (Spring Batch)

Hệ thống lập lịch (Scheduler) sẽ kích hoạt các tiến trình chạy batch (Tính lãi, quét phí) độc lập theo giờ ban đêm của từng quốc gia bằng cơ chế **Parameter Driven Batch**.

### 5.1. Định cấu hình Reader theo StepScope
Nhận tham số động từ `JobParameters` của từng phiên chạy chi nhánh để quét đúng tập dữ liệu:

```java
@Bean
@StepScope
public RepositoryItemReader<Transaction> transactionReader(
        TransactionRepository repo,
        @Value("#{jobParameters['branchId']}") String branchId,
        @Value("#{jobParameters['accountingDate']}") String dateStr,
        @Value("#{jobParameters['zoneId']}") String zoneIdStr) {

    LocalDate accountingDate = LocalDate.parse(dateStr);
    Range<Instant> utcRange = DateTimeUtils.getUtcRangeForBranch(accountingDate, zoneIdStr);

    RepositoryItemReader<Transaction> reader = new RepositoryItemReader<>();
    reader.setRepository(repo);
    reader.setMethodName("findByBranchIdAndTransactionTimeBetween");
    reader.setArguments(List.of(branchId, utcRange.getMinimum(), utcRange.getMaximum()));
    reader.setSort(Map.of("transactionTime", Sort.Direction.ASC));
    return reader;
}
```

### 5.2. Lập lịch kích hoạt (Scheduler) đa múi giờ
Sử dụng thuộc tính `zone` trong annotation `@Scheduled` để kích hoạt chính xác thời điểm đóng sổ cục bộ của chi nhánh:

```java
@Component
public class BankingJobScheduler {
    @Autowired
    private JobLauncher jobLauncher;
    @Autowired
    private Job interestCalculationJob;

    // Chạy lúc 23:00 tối theo giờ Việt Nam (UTC+7)
    @Scheduled(cron = "0 0 23 * * ?", zone = "Asia/Ho_Chi_Minh")
    public void runVietnamEodJob() throws Exception {
        runJobForBranch("BR_VN_01", "Asia/Ho_Chi_Minh");
    }

    // Chạy lúc 23:00 tối theo giờ Luân Đôn (UTC+0)
    @Scheduled(cron = "0 0 23 * * ?", zone = "Europe/London")
    public void runLondonEodJob() throws Exception {
        runJobForBranch("BR_UK_01", "Europe/London");
    }

    private void runJobForBranch(String branchId, String zoneId) throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addString("branchId", branchId)
            .addString("zoneId", zoneId)
            .addString("accountingDate", LocalDate.now(ZoneId.of(zoneId)).toString())
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();
            
        jobLauncher.run(interestCalculationJob, params);
    }
}
```