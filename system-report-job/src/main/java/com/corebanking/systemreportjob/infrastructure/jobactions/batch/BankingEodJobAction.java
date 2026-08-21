package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import java.time.Duration;
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

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.infrastructure.jobactions.JobAction;

/**
 * Sample job action demonstrating a branching/parallel Spring Batch flow, per
 * docs/batch-banking/banking_batch_test_guide.md. Registered under jobType
 * {@code BANKING_EOD}.
 */
@Component
public class BankingEodJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(BankingEodJobAction.class);

    private final JobOperator jobOperator;
    private final Job bankingEndOfDayJob;
    private final AsyncTaskExecutor jobActionTaskExecutor;
    private final Duration executionTimeout;

    public BankingEodJobAction(
            JobOperator jobOperator,
            Job bankingEndOfDayJob,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
            @Value("${app.batch.eod.execution-timeout:5m}") Duration executionTimeout) {
        this.jobOperator = jobOperator;
        this.bankingEndOfDayJob = bankingEndOfDayJob;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean matches(String jobType) {
        return "BANKING_EOD".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        log.info("expression {}", definition.expression());
        Future<Void> future = jobActionTaskExecutor.submit(() -> runJob(definition));
        try {
            future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            throw new IllegalStateException(
                    "BANKING_EOD job action thất bại: " + definition.id() + " (" + message + ")", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "BANKING_EOD job action quá thời gian chờ (" + executionTimeout + "): " + definition.id(), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("BANKING_EOD job action bị gián đoạn: " + definition.id(), e);
        }
    }

    private Void runJob(JobDefinition definition) throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("jobDefinitionId", definition.id().toString())
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobOperator.start(bankingEndOfDayJob, jobParameters);
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    "BANKING_EOD batch job kết thúc với trạng thái " + execution.getStatus() + ": " + definition.id());
        }
        log.info("BANKING_EOD job {} hoàn tất, exitStatus={}", definition.id(), execution.getExitStatus());
        return null;
    }
}
