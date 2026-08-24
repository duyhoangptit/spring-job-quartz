package com.system.reportjob.infrastructure.scheduler.listeners;

import java.time.Instant;
import java.util.UUID;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.TaskExecutionRecord;
import com.system.reportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;

/**
 * Wraps every Quartz job firing — the single place a {@code requestId} is minted and put in MDC
 * so the whole execution flow (orchestrator, job action, and anything it hands off to the
 * virtual-thread {@code jobActionTaskExecutor}, see {@code MdcTaskDecorator}) can be traced
 * through one log correlation ID. Cleared unconditionally afterwards since Quartz worker threads
 * are pooled and reused for the next job firing.
 */
@Component
public class QuartzJobListener implements JobListener {

    private static final String START_TIME_KEY = "startTime";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private final TaskExecutionHistoryRepositoryPort historyRepositoryPort;

    public QuartzJobListener(TaskExecutionHistoryRepositoryPort historyRepositoryPort) {
        this.historyRepositoryPort = historyRepositoryPort;
    }

    @Override
    public String getName() {
        return "systemReportJobListener";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        context.put(START_TIME_KEY, Instant.now());
        MDC.put(REQUEST_ID_MDC_KEY, UUID.randomUUID().toString());
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // no history to record — the job never actually ran
        MDC.remove(REQUEST_ID_MDC_KEY);
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        try {
            Instant startTime = (Instant) context.get(START_TIME_KEY);
            UUID taskId = UUID.fromString(context.getMergedJobDataMap().getString("taskId"));
            String taskName = context.getMergedJobDataMap().getString("taskName");

            historyRepositoryPort.save(new TaskExecutionRecord(
                    UUID.randomUUID(),
                    taskId,
                    taskName,
                    startTime,
                    Instant.now(),
                    jobException == null ? null : jobException.getMessage()));
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }
}
