package com.corebanking.systemreportjob.infrastructure.scheduler.listeners;

import java.time.Instant;
import java.util.UUID;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.springframework.stereotype.Component;

import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;

@Component
public class QuartzJobListener implements JobListener {

    private static final String START_TIME_KEY = "startTime";

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
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // no history to record — the job never actually ran
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
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
    }
}
