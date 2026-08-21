package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Sample banking end-of-day job demonstrating conditional branching and a parallel
 * {@link Flow} split, per docs/batch-banking/banking_batch_test_guide.md. Registered as
 * jobType {@code BANKING_EOD} via {@link BankingEodJobAction}.
 */
@Configuration
public class BankingEodBatchConfig {

    private static final Logger log = LoggerFactory.getLogger(BankingEodBatchConfig.class);

    private final JdbcTemplate jdbcTemplate;

    public BankingEodBatchConfig(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Bean
    public Step checkSystemStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.info("[BANKING_EOD] checkSystemStep - kiểm tra trạng thái hệ thống Core Banking");
            String status = jdbcTemplate.queryForObject(
                    "SELECT status_value FROM sys_status WHERE sys_key = 'CORE_SYSTEM'", String.class);
            if (!"READY".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Hệ thống Core Banking chưa sẵn sàng (trạng thái: " + status + ")");
            }
            log.info("[BANKING_EOD] checkSystemStep - hệ thống READY, tiếp tục batch");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("checkSystemStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step readAtmFilesStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.info("[BANKING_EOD] readAtmFilesStep - đọc file dữ liệu giao dịch ATM");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("readAtmFilesStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step validateAtmTxnsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.info("[BANKING_EOD] validateAtmTxnsStep - đối soát giao dịch ATM");
            jdbcTemplate.update("UPDATE atm_transactions SET status = 'PROCESSED' WHERE status = 'PENDING'");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("validateAtmTxnsStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step calculateInterestStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.info("[BANKING_EOD] calculateInterestStep - tính lãi tiền gửi cuối ngày");
            jdbcTemplate.update("UPDATE saving_accounts "
                    + "SET accrued_interest = accrued_interest + (balance * interest_rate / 365)");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("calculateInterestStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step updateBalanceStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.info("[BANKING_EOD] updateBalanceStep - cập nhật số dư tài khoản tiết kiệm");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("updateBalanceStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step summaryStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.info("[BANKING_EOD] summaryStep - tổng hợp báo cáo EOD");
            Integer processedTxns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM atm_transactions WHERE status = 'PROCESSED'", Integer.class);
            Double totalInterest = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(accrued_interest), 0) FROM saving_accounts", Double.class);
            jdbcTemplate.update(
                    "INSERT INTO eod_summary_report (report_date, total_atm_txns, total_interest_paid,"
                            + " execution_status) VALUES (CURRENT_DATE, ?, ?, 'SUCCESS') ON CONFLICT (report_date)"
                            + " DO UPDATE SET total_atm_txns = EXCLUDED.total_atm_txns,"
                            + " total_interest_paid = EXCLUDED.total_interest_paid, execution_status = 'SUCCESS'",
                    processedTxns,
                    totalInterest);
            log.info("[BANKING_EOD] summaryStep - đã lưu eod_summary_report");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("summaryStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    public Step sendAlertStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        Tasklet tasklet = (contribution, chunkContext) -> {
            log.warn("[BANKING_EOD] sendAlertStep - CRITICAL ALERT: gửi thông báo khẩn tới IT Ops");
            return RepeatStatus.FINISHED;
        };
        return new StepBuilder("sendAlertStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

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
            Step sendAlertStep,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor) {

        Flow parallelProcessingFlow = new FlowBuilder<Flow>("parallelProcessingFlow")
                .start(atmProcessingFlow)
                .split(jobActionTaskExecutor)
                .add(interestCalculationFlow)
                .build();

        return new JobBuilder("bankingEndOfDayJob", jobRepository)
                .start(checkSystemStep)
                .on("FAILED")
                .to(sendAlertStep)
                .from(checkSystemStep)
                .on("COMPLETED")
                .to(parallelProcessingFlow)
                .from(parallelProcessingFlow)
                .on("COMPLETED")
                .to(summaryStep)
                .from(parallelProcessingFlow)
                .on("FAILED")
                .to(sendAlertStep)
                .end()
                .build();
    }
}
