# Hướng dẫn Kiểm thử Cấu hình Spring Batch 6.4 (PostgreSQL)

Tài liệu này hướng dẫn cách thiết lập, cấu hình cấu trúc cơ sở dữ liệu và triển khai mã nguồn cho một **Banking Job phức tạp** sử dụng **Spring Batch 6.4** và **Spring Boot 3.4+** kết nối với cơ sở dữ liệu **PostgreSQL**.

---

## 1. Cấu hình Dự án (Maven Dependencies)

Spring Batch 6.4 yêu cầu **Java 17 hoặc cao hơn** và **Spring Boot 3.4.x**. Thêm các thành phần phụ thuộc sau vào file `pom.xml`:

```xml
<dependencies>
    <!-- Spring Boot Starter Batch (Bao gồm Spring Batch 6.4) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-batch</artifactId>
    </dependency>

    <!-- Spring Boot Starter JDBC để kết nối cơ sở dữ liệu -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jdbc</artifactId>
    </dependency>

    <!-- Driver kết nối PostgreSQL -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

---

## 2. Cấu hình Hệ thống (application.properties)

Cấu hình thông tin kết nối PostgreSQL và bật tính năng tự động tạo bảng Metadata lưu trữ trạng thái của Spring Batch 6.x:

```properties
# Thông tin kết nối PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/banking_batch_db
spring.datasource.username=postgres
spring.datasource.password=yourpassword
spring.datasource.driver-class-name=org.postgresql.Driver

# Tự động khởi tạo cấu trúc bảng Metadata của Spring Batch 6.x trên PostgreSQL
spring.batch.jdbc.initialize-schema=always

# Không tự động chạy Job khi ứng dụng Spring Boot khởi động (Kích hoạt thủ công qua JobLauncher)
spring.batch.job.enabled=false
```

---

## 3. Thiết kế Bảng Cơ sở Dữ liệu Giả lập (SQL DDL & DML)

Sử dụng tập lệnh SQL sau trên cơ sở dữ liệu PostgreSQL để giả lập dữ liệu nghiệp vụ cho hệ thống ngân hàng:

```sql
-- 1. Bảng kiểm tra trạng thái hệ thống Core Banking
CREATE TABLE IF NOT EXISTS sys_status (
    sys_key VARCHAR(50) PRIMARY KEY,
    status_value VARCHAR(20) NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Dữ liệu mẫu ban đầu: Hệ thống sẵn sàng cho chốt số dư (READY)
INSERT INTO sys_status (sys_key, status_value) 
VALUES ('CORE_SYSTEM', 'READY')
ON CONFLICT (sys_key) DO UPDATE SET status_value = 'READY';


-- 2. Bảng lưu trữ file dữ liệu giao dịch từ ATM/POS
CREATE TABLE IF NOT EXISTS atm_transactions (
    txn_id SERIAL PRIMARY KEY,
    account_number VARCHAR(20) NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    txn_type VARCHAR(10) NOT NULL, -- DEPOSIT, WITHDRAWAL
    status VARCHAR(20) DEFAULT 'PENDING'
);

INSERT INTO atm_transactions (account_number, amount, txn_type) VALUES 
('ACC1001', 5000000.00, 'DEPOSIT'),
('ACC1002', 2000000.00, 'WITHDRAWAL'),
('ACC1003', 1500000.00, 'DEPOSIT');


-- 3. Bảng danh sách tài khoản tiết kiệm tính lãi
CREATE TABLE IF NOT EXISTS saving_accounts (
    account_number VARCHAR(20) PRIMARY KEY,
    balance NUMERIC(15, 2) NOT NULL,
    interest_rate NUMERIC(5, 4) NOT NULL, -- ví dụ: 0.0550 (5.5%)
    accrued_interest NUMERIC(15, 2) DEFAULT 0.00
);

INSERT INTO saving_accounts (account_number, balance, interest_rate) VALUES 
('ACC1001', 100000000.00, 0.0550),
('ACC1002', 500000000.00, 0.0600);


-- 4. Bảng tổng hợp báo cáo cuối ngày (EOD)
CREATE TABLE IF NOT EXISTS eod_summary_report (
    report_date DATE PRIMARY KEY DEFAULT CURRENT_DATE,
    total_atm_txns INT DEFAULT 0,
    total_interest_paid NUMERIC(15, 2) DEFAULT 0.00,
    execution_status VARCHAR(20)
);
```

---

## 4. Mã nguồn Cấu hình Job hoàn chỉnh (Chuẩn Spring Batch 6.4)

Trong **Spring Batch 6.4**, toàn bộ cấu trúc tạo `JobBuilder` và `StepBuilder` yêu cầu truyền rõ ràng một cấu trúc `JobRepository`. Dưới đây là triển khai đầy đủ các bước xử lý nghiệp vụ tích hợp tương tác với PostgreSQL:

```java
package com.example.bankingbatch.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class BankingBatchConfig {

    private final JdbcTemplate jdbcTemplate;

    // Inject DataSource để thực hiện các câu lệnh kiểm tra/cập nhật DB thực tế
    public BankingBatchConfig(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // ==========================================
    // ĐỊNH NGHĨA CÁC BƯỚC XỬ LÝ (STEPS - TASKLET)
    // ==========================================

    @Bean
    public Step checkSystemStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            System.out.println("[STEP 1] --- Kiểm tra trạng thái hệ thống Core Banking ---");
            String status = jdbcTemplate.queryForObject(
                    "SELECT status_value FROM sys_status WHERE sys_key = 'CORE_SYSTEM'", String.class);
            
            if ("READY".equalsIgnoreCase(status)) {
                System.out.println(">> Hệ thống ở trạng thái READY. Tiếp tục chạy Batch.");
                return RepeatStatus.FINISHED;
            } else {
                System.err.println(">> CẢNH BÁO: Hệ thống chưa sẵn sàng (Trạng thái: " + status + ").");
                throw new IllegalStateException("Hệ thống Core Banking Lock chặn vận hành cuối ngày!");
            }
        };

        return new StepBuilder("checkSystemStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step readAtmFilesStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("readAtmFilesStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("[BRANCH ATM - STEP 1] --- Đọc file dữ liệu giao dịch ATM ---");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step validateAtmTxnsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("validateAtmTxnsStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("[BRANCH ATM - STEP 2] --- Đối soát giao dịch ATM lên PostgreSQL ---");
                    jdbcTemplate.update("UPDATE atm_transactions SET status = 'PROCESSED' WHERE status = 'PENDING'");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step calculateInterestStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("calculateInterestStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("[BRANCH INTEREST - STEP 1] --- Tính lãi tiền gửi cuối ngày ---");
                    // Giả lập công thức: Tiền lãi cộng dồn = Số dư * Lãi suất / 365 ngày
                    jdbcTemplate.update("UPDATE saving_accounts SET accrued_interest = accrued_interest + (balance * interest_rate / 365)");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step updateBalanceStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("updateBalanceStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("[BRANCH INTEREST - STEP 2] --- Cập nhật số dư tài khoản tiết kiệm ---");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step summaryStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("summaryStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.out.println("[STEP FINAL] --- Tổng hợp dữ liệu & Kết xuất báo cáo EOD sang PostgreSQL ---");
                    
                    Integer processedTxns = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM atm_transactions WHERE status = 'PROCESSED'", Integer.class);
                    Double totalInterest = jdbcTemplate.queryForObject(
                            "SELECT COALESCE(SUM(accrued_interest), 0) FROM saving_accounts", Double.class);

                    jdbcTemplate.update(
                            "INSERT INTO eod_summary_report (report_date, total_atm_txns, total_interest_paid, execution_status) " +
                            "VALUES (CURRENT_DATE, ?, ?, 'SUCCESS') " +
                            "ON CONFLICT (report_date) DO UPDATE SET total_atm_txns = EXCLUDED.total_atm_txns, " +
                            "total_interest_paid = EXCLUDED.total_interest_paid, execution_status = 'SUCCESS'",
                            processedTxns, totalInterest);
                    
                    System.out.println(">> Báo cáo cuối ngày đã lưu thành công vào bảng eod_summary_report.");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Step sendAlertStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("sendAlertStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    System.err.println("[🚨 CRITICAL ALERT] --- Gửi thông báo khẩn tới IT Ops cứu hộ hệ thống! ---");
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    // ==========================================
    // CẤU HÌNH LUỒNG SONG SONG VÀ ĐIỀU HƯỚNG JOB
    // ==========================================

    @Bean
    public Flow atmProcessingFlow(Step readAtmFilesStep, Step validateAtmTxnsStep) {
        return new FlowBuilder<Flow>("atmProcessingFlow")
                .start(readAtmFilesStep)
                .next(validateAtmTxnsStep)
                .build();
    }

    @Bean
    public Flow interestCalculationFlow(Step calculateInterestStep, Step updateBalanceStep) {
        return new FlowBuilder<Flow>("interestCalculationFlow")
                .start(calculateInterestStep)
                .next(updateBalanceStep)
                .build();
    }

    @Bean
    public Job bankingEndOfDayJob(
            JobRepository jobRepository,
            Step checkSystemStep,
            Flow atmProcessingFlow,
            Flow interestCalculationFlow,
            Step summaryStep,
            Step sendAlertStep) {

        // Tạo luồng song song kết hợp 2 quy trình ATM và Quy trình tính toán lãi tiền gửi
        Flow parallelProcessingFlow = new FlowBuilder<Flow>("parallelProcessingFlow")
                .start(atmProcessingFlow)
                .split(new SimpleAsyncTaskExecutor()) // Thực thi đa luồng không đồng bộ
                .add(interestCalculationFlow)
                .build();

        // Xây dựng Job chính cấu hình rẽ nhánh điều kiện dựa trên trạng thái thực thi
        return new JobBuilder("bankingEndOfDayJob", jobRepository)
                .start(checkSystemStep)
                    .on("FAILED").to(sendAlertStep) // Nếu kiểm tra hệ thống lỗi -> Gửi cảnh báo Ops
                .from(checkSystemStep)
                    .on("COMPLETED").to(parallelProcessingFlow) // Nếu thành công -> Kích hoạt song song dữ liệu
                
                .from(parallelProcessingFlow)
                    .on("COMPLETED").to(summaryStep) // Nhánh dữ liệu hoàn thành -> Chốt và xuất báo cáo
                .from(parallelProcessingFlow)
                    .on("FAILED").to(sendAlertStep) // Một trong hai nhánh xử lý lỗi dữ liệu -> Gửi cảnh báo Ops

                .end() // Đóng luồng cấu hình điều kiện
                .build();
    }
}
```

---

## 5. Hướng dẫn Kịch bản Kiểm thử Luồng (Testing Playbook)

Để kiểm chứng tính năng rẽ nhánh và kiểm soát luồng chạy của mã nguồn AI tạo ra, bạn thực hiện cấu hình thay đổi dữ liệu trong PostgreSQL:

### Kịch bản 1: Kiểm thử luồng chạy thành công (Happy Path)
1. Thực hiện thiết lập trạng thái hệ thống hợp lệ trong DB:
   ```sql
   UPDATE sys_status SET status_value = 'READY' WHERE sys_key = 'CORE_SYSTEM';
   ```
2. Kích hoạt chạy Job.
3. **Kết quả kỳ vọng trên Console:** 
   * `checkSystemStep` thông báo thành công.
   * Log của nhóm công việc `atmProcessingFlow` và `interestCalculationFlow` in ra đan xen nhau (chứng minh tính năng đa luồng chạy song song `.split()`).
   * `summaryStep` hoàn thành và bản ghi mới được sinh ra trong bảng `eod_summary_report`.

### Kịch bản 2: Kiểm thử luồng xử lý lỗi hệ thống (Failure Branching Path)
1. Giả lập lỗi hệ thống khiến Core Banking bị khóa đột ngột:
   ```sql
   UPDATE sys_status SET status_value = 'MAINTENANCE' WHERE sys_key = 'CORE_SYSTEM';
   ```
2. Kích hoạt chạy Job.
3. **Kết quả kỳ vọng trên Console:**
   * `checkSystemStep` ném ra ngoại lệ `IllegalStateException`.
   * Luồng xử lý lập tức bỏ qua nhánh xử lý dữ liệu (`parallelProcessingFlow`) và đi thẳng vào hàm `sendAlertStep`.
   * Log hiển thị chuỗi cảnh báo khẩn cấp `[🚨 CRITICAL ALERT]`.
