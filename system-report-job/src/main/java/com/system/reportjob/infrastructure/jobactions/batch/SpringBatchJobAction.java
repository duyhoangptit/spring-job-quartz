package com.system.reportjob.infrastructure.jobactions.batch;

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

import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.infrastructure.jobactions.JobAction;

@Component
public class SpringBatchJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(SpringBatchJobAction.class);

    private final JobOperator jobOperator;
    private final Job exportUsersJob;
    private final AsyncTaskExecutor jobActionTaskExecutor;
    private final Duration executionTimeout;

    public SpringBatchJobAction(
            JobOperator jobOperator,
            Job exportUsersJob,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
            @Value("${app.batch.export.execution-timeout:30m}") Duration executionTimeout) {
        this.jobOperator = jobOperator;
        this.exportUsersJob = exportUsersJob;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean matches(String jobType) {
        return "EXPORT_USERS".equals(jobType);
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
                    "EXPORT_USERS job action thất bại: " + definition.id() + " (" + message + ")", cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "EXPORT_USERS job action quá thời gian chờ (" + executionTimeout + "): " + definition.id(), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EXPORT_USERS job action bị gián đoạn: " + definition.id(), e);
        }
    }

    private Void runJob(JobDefinition definition) throws Exception {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("jobDefinitionId", definition.id().toString())
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters();
        JobExecution execution = jobOperator.start(exportUsersJob, jobParameters);
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    "EXPORT_USERS batch job kết thúc với trạng thái " + execution.getStatus() + ": " + definition.id());
        }
        log.info("EXPORT_USERS job {} hoàn tất, exitStatus={}", definition.id(), execution.getExitStatus());
        return null;
    }
}
