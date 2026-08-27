package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.io.BufferedReader;
import java.io.FileReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Sample job cho BANK_SALARY_PAYROLL (chuyển lương hàng loạt FPT Software), theo
 * docs/bank-salary-sample/bank-salary-sample.md: holdFundsStep (tasklet) -&gt; disburseStep
 * (chunk, có skip) -&gt; notifyStep (tasklet). Đăng ký qua {@link PayrollJobAction}.
 */
@Configuration
public class PayrollBatchConfig {

    private static final Logger log = LoggerFactory.getLogger(PayrollBatchConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public PayrollBatchConfig(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Bean
    @StepScope
    public Tasklet holdFundsTasklet(
            @Value("#{jobParameters['companyCode']}") String companyCode,
            @Value("#{jobParameters['targetPayDate']}") String targetPayDate,
            @Value("#{jobParameters['csvFilePath']}") String csvFilePath) {
        return (contribution, chunkContext) -> {
            BigDecimal totalAmount = BigDecimal.ZERO;
            int employeeCount = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(csvFilePath))) {
                reader.readLine(); // bỏ dòng header
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    employeeCount++;
                    String[] cols = line.split(",", -1);
                    if (cols.length >= 4) {
                        try {
                            BigDecimal amount = new BigDecimal(cols[3].trim());
                            if (amount.signum() > 0) {
                                totalAmount = totalAmount.add(amount);
                            }
                        } catch (NumberFormatException ex) {
                            // Dòng lỗi định dạng số tiền - disburseStep sẽ skip khi xử lý, không
                            // tính vào tổng giữ tiền ở đây.
                        }
                    }
                }
            }

            BigDecimal balance = jdbcTemplate.queryForObject(
                    "SELECT balance FROM fpt_company_account WHERE company_code = ?", BigDecimal.class, companyCode);
            if (balance == null || balance.compareTo(totalAmount) < 0) {
                throw new IllegalStateException("Tài khoản " + companyCode + " không đủ số dư để trả lương: cần "
                        + totalAmount + ", hiện có " + balance);
            }

            jdbcTemplate.update(
                    "UPDATE fpt_company_account SET balance = balance - ?, updated_at = now() WHERE company_code = ?",
                    totalAmount,
                    companyCode);
            jdbcTemplate.update(
                    "UPDATE gl_suspense_account SET balance = balance + ?, updated_at = now() "
                            + "WHERE account_code = 'PAYROLL_SUSPENSE_GL'",
                    totalAmount);
            jdbcTemplate.update(
                    "INSERT INTO payroll_batch_run "
                            + "(company_code, target_pay_date, total_employees, total_amount, status) "
                            + "VALUES (?, ?, ?, ?, 'HOLD_SUCCESS')",
                    companyCode,
                    LocalDate.parse(targetPayDate),
                    employeeCount,
                    totalAmount);

            log.info(
                    "[BANK_SALARY_PAYROLL] holdFundsStep - đã giữ {} VND cho {} nhân viên (company={}, targetPayDate={})",
                    totalAmount,
                    employeeCount,
                    companyCode,
                    targetPayDate);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step holdFundsStep(
            JobRepository jobRepository, PlatformTransactionManager transactionManager, Tasklet holdFundsTasklet) {
        return new StepBuilder("holdFundsStep", jobRepository)
                .tasklet(holdFundsTasklet, transactionManager)
                .listener(new HoldFundsStepExecutionListener())
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<PayrollCsvRecord> csvEmployeeReader(
            @Value("#{jobParameters['csvFilePath']}") String csvFilePath) {
        return new FlatFileItemReaderBuilder<PayrollCsvRecord>()
                .name("csvEmployeeReader")
                .resource(new FileSystemResource(csvFilePath))
                .delimited()
                .delimiter(",")
                .names("employeeId", "accountNumber", "fullName", "salaryAmount")
                .fieldSetMapper(fieldSet -> {
                    BigDecimal salaryAmount;
                    try {
                        salaryAmount = fieldSet.readBigDecimal("salaryAmount");
                    } catch (NumberFormatException ex) {
                        salaryAmount =
                                null; // dòng lỗi định dạng số tiền - sẽ bị PayrollValidationProcessor skip vì null
                    }
                    return new PayrollCsvRecord(
                            fieldSet.readString("employeeId"),
                            fieldSet.readString("accountNumber"),
                            fieldSet.readString("fullName"),
                            salaryAmount);
                })
                .linesToSkip(1)
                .build();
    }

    @Bean
    public ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord> payrollValidationProcessor() {
        return new PayrollValidationProcessor();
    }

    @Bean
    @StepScope
    public PayrollDisbursementWriter payrollDisbursementWriter(
            @Value("#{jobParameters['companyCode']}") String companyCode,
            @Value("#{jobParameters['targetPayDate']}") String targetPayDate) {
        return new PayrollDisbursementWriter(jdbcTemplate, companyCode, LocalDate.parse(targetPayDate));
    }

    @Bean
    public Step disburseStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<PayrollCsvRecord> csvEmployeeReader,
            ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord> payrollValidationProcessor,
            PayrollDisbursementWriter payrollDisbursementWriter,
            @Value("${app.batch.payroll.chunk-size:500}") int chunkSize,
            @Value("${app.batch.payroll.skip-limit:1000}") int skipLimit) {
        return new StepBuilder("disburseStep", jobRepository)
                .<PayrollCsvRecord, PayrollDisbursementRecord>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(csvEmployeeReader)
                .processor(payrollValidationProcessor)
                .writer(payrollDisbursementWriter)
                .faultTolerant()
                .skip(PayrollValidationException.class)
                .skipLimit(skipLimit)
                .skipListener(payrollDisbursementWriter)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet notifyTasklet(
            @Value("#{jobParameters['companyCode']}") String companyCode,
            @Value("#{jobParameters['targetPayDate']}") String targetPayDate) {
        return (contribution, chunkContext) -> {
            LocalDate payDate = LocalDate.parse(targetPayDate);
            Long batchRunId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payroll_batch_run WHERE company_code = ? AND target_pay_date = ?",
                    Long.class,
                    companyCode,
                    payDate);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, COUNT(*) AS cnt FROM payroll_disbursement WHERE batch_run_id = ? GROUP BY status",
                    batchRunId);

            long success = 0;
            long skipped = 0;
            for (Map<String, Object> row : rows) {
                long count = ((Number) row.get("cnt")).longValue();
                if ("SUCCESS".equals(row.get("status"))) {
                    success = count;
                } else if ("SKIPPED".equals(row.get("status"))) {
                    skipped = count;
                }
            }

            log.info(
                    "[BANK_SALARY_PAYROLL] notifyStep - Kỳ lương {} ({}): {}/{} nhân viên nhận lương thành công,"
                            + " {} bị skip do lỗi",
                    targetPayDate,
                    companyCode,
                    success,
                    success + skipped,
                    skipped);

            jdbcTemplate.update(
                    "UPDATE payroll_batch_run SET status = 'COMPLETED', completed_at = now() WHERE id = ?", batchRunId);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step notifyStep(
            JobRepository jobRepository, PlatformTransactionManager transactionManager, Tasklet notifyTasklet) {
        return new StepBuilder("notifyStep", jobRepository)
                .tasklet(notifyTasklet, transactionManager)
                .build();
    }

    @Bean
    public Job fptPayrollJob(JobRepository jobRepository, Step holdFundsStep, Step disburseStep, Step notifyStep) {
        return new JobBuilder("fptPayrollJob", jobRepository)
                .start(holdFundsStep)
                .next(disburseStep)
                .next(notifyStep)
                .listener(new PayrollBatchNotificationListener())
                .build();
    }
}
