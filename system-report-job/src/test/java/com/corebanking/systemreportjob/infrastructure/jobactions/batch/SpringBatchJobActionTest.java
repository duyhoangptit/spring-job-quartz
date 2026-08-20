package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

class SpringBatchJobActionTest {

    private final Job exportUsersJob = mock(Job.class);

    private SpringBatchJobAction newAction(JobOperator jobOperator, AsyncTaskExecutor executor) {
        return new SpringBatchJobAction(jobOperator, exportUsersJob, executor, Duration.ofSeconds(30));
    }

    @Test
    void matchesOnlyExportUsersJobType() {
        SpringBatchJobAction action = newAction(
                mock(JobOperator.class), new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));

        assertThat(action.matches("EXPORT_USERS")).isTrue();
        assertThat(action.matches("HTTP_CALL")).isFalse();
    }

    @Test
    void completesSuccessfullyWhenBatchJobCompletes() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution completed = mock(JobExecution.class);
        when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(completed.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobOperator.start(eq(exportUsersJob), any(JobParameters.class))).thenReturn(completed);
        SpringBatchJobAction action =
                newAction(jobOperator, new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "EXPORT_USERS", "{}", null);

        action.execute(definition);

        verify(jobOperator).start(eq(exportUsersJob), any(JobParameters.class));
    }

    @Test
    void throwsWhenBatchJobDoesNotComplete() throws Exception {
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution failed = mock(JobExecution.class);
        when(failed.getStatus()).thenReturn(BatchStatus.FAILED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(failed);
        SpringBatchJobAction action =
                newAction(jobOperator, new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "EXPORT_USERS", "{}", null);

        assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void failsFastInsteadOfBlockingForeverWhenActionExceedsTimeout() {
        AsyncTaskExecutor hangingExecutor = new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                // không chạy gì cả
            }

            @Override
            public Future<?> submit(Runnable task) {
                return new CompletableFuture<>();
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return new CompletableFuture<>();
            }
        };
        SpringBatchJobAction action = new SpringBatchJobAction(
                mock(JobOperator.class), exportUsersJob, hangingExecutor, Duration.ofMillis(100));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "EXPORT_USERS", "{}", null);

        long start = System.nanoTime();
        assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quá thời gian chờ");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
