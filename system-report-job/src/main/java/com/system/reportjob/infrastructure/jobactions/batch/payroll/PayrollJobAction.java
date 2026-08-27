package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.infrastructure.jobactions.JobAction;
import com.system.reportjob.usecase.ports.in.DecryptCompanyFileUseCase;
import com.system.reportjob.usecase.ports.in.HolidayQueryUseCase;

/**
 * Job action cho jobType BANK_SALARY_PAYROLL. Trigger Quartz nên là 1 Cron chạy hàng ngày, gắn
 * calendar "bankHolidays" (xem Task 1 / QuartzTriggerFactory) để Quartz không fire vào cuối
 * tuần/ngày lễ; class này tự quyết định thêm xem HÔM NAY có đúng là ngày trả lương không (ngày
 * 19, hoặc ngày làm việc gần nhất sau đó nếu 19 rơi vào ngày nghỉ) trước khi thực sự chạy batch.
 */
@Component
public class PayrollJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(PayrollJobAction.class);
    private static final int DEFAULT_PAY_DAY_OF_MONTH = 19;

    private final JobOperator jobOperator;
    private final Job fptPayrollJob;
    private final HolidayQueryUseCase holidayQueryUseCase;
    private final ObjectMapper objectMapper;
    private final DecryptCompanyFileUseCase decryptCompanyFileUseCase;
    private final AsyncTaskExecutor jobActionTaskExecutor;
    private final Duration executionTimeout;

    public PayrollJobAction(
            JobOperator jobOperator,
            Job fptPayrollJob,
            HolidayQueryUseCase holidayQueryUseCase,
            ObjectMapper objectMapper,
            DecryptCompanyFileUseCase decryptCompanyFileUseCase,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
            @Value("${app.batch.payroll.execution-timeout:15m}") Duration executionTimeout) {
        this.jobOperator = jobOperator;
        this.fptPayrollJob = fptPayrollJob;
        this.holidayQueryUseCase = holidayQueryUseCase;
        this.objectMapper = objectMapper;
        this.decryptCompanyFileUseCase = decryptCompanyFileUseCase;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean matches(String jobType) {
        return "BANK_SALARY_PAYROLL".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        PayrollExpression expression;
        try {
            expression = objectMapper.readValue(definition.expression(), PayrollExpression.class);
        } catch (Exception e) {
            throw new IllegalStateException("BANK_SALARY_PAYROLL expression không hợp lệ: " + definition.id(), e);
        }
        String countryCode = expression.countryCode() != null ? expression.countryCode() : "VN";
        String branchId = expression.branchId() != null ? expression.branchId() : "ALL";
        int payDayOfMonth = expression.payDayOfMonth() != null ? expression.payDayOfMonth() : DEFAULT_PAY_DAY_OF_MONTH;

        LocalDate today = LocalDate.now();
        LocalDate targetPayDate = resolveTargetPayDate(today, countryCode, branchId, payDayOfMonth);
        if (!today.equals(targetPayDate)) {
            log.info(
                    "BANK_SALARY_PAYROLL: hôm nay ({}) chưa phải ngày trả lương (target={}), bỏ qua",
                    today,
                    targetPayDate);
            return;
        }

        Future<Void> future = jobActionTaskExecutor.submit(() -> runJob(definition, expression, targetPayDate));
        try {
            future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            throw new IllegalStateException(
                    "BANK_SALARY_PAYROLL job action thất bại: " + definition.id() + " (" + message + ")", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "BANK_SALARY_PAYROLL job action quá thời gian chờ (" + executionTimeout + "): " + definition.id(),
                    e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BANK_SALARY_PAYROLL job action bị gián đoạn: " + definition.id(), e);
        }
    }

    /**
     * Ngày payDayOfMonth hàng tháng (mặc định 19, cấu hình được qua expression) nếu là ngày làm
     * việc, ngược lại là ngày làm việc gần nhất sau đó. Clamp về ngày cuối tháng nếu
     * payDayOfMonth lớn hơn số ngày thực tế của tháng đó (vd cấu hình 31 nhưng tháng chỉ có 28/30
     * ngày).
     * getNextWorkingDay(start, ...) trả về ngày làm việc đầu tiên SAU start (không tính start),
     * nên truyền vào ngày liền trước để nó trả về đúng ngày mục tiêu khi đó là ngày làm việc, hoặc
     * ngày làm việc kế tiếp nếu không phải.
     */
    private LocalDate resolveTargetPayDate(LocalDate today, String countryCode, String branchId, int payDayOfMonth) {
        YearMonth month = YearMonth.from(today);
        int clampedDay = Math.max(1, Math.min(payDayOfMonth, month.lengthOfMonth()));
        LocalDate theTargetDay = month.atDay(clampedDay);
        return holidayQueryUseCase.getNextWorkingDay(theTargetDay.minusDays(1), countryCode, branchId);
    }

    private Void runJob(JobDefinition definition, PayrollExpression expression, LocalDate targetPayDate)
            throws Exception {
        String baseFileName = "FPT_PAYROLL_" + targetPayDate;
        boolean pgpEncrypted = Boolean.TRUE.equals(expression.pgpEncrypted());
        Path sourcePath = Path.of(expression.csvDirectory(), baseFileName + (pgpEncrypted ? ".csv.pgp" : ".csv"));

        Path csvFilePath =
                pgpEncrypted ? decryptCompanyFileUseCase.decryptFile(expression.companyCode(), sourcePath) : sourcePath;
        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("companyCode", expression.companyCode())
                    .addString("targetPayDate", targetPayDate.toString())
                    .addString("csvFilePath", csvFilePath.toString())
                    .addLong("runAt", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = jobOperator.start(fptPayrollJob, jobParameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                throw new IllegalStateException("BANK_SALARY_PAYROLL batch job kết thúc với trạng thái "
                        + execution.getStatus() + ": " + definition.id());
            }
            log.info(
                    "BANK_SALARY_PAYROLL job {} hoàn tất cho kỳ lương {}, exitStatus={}",
                    definition.id(),
                    targetPayDate,
                    execution.getExitStatus());
            return null;
        } finally {
            if (pgpEncrypted) {
                Files.deleteIfExists(csvFilePath);
            }
        }
    }

    record PayrollExpression(
            String companyCode,
            String csvDirectory,
            String countryCode,
            String branchId,
            Integer payDayOfMonth,
            Boolean pgpEncrypted) {}
}
