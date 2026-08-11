package com.corebanking.systemreportjob.infrastructure.scheduler;

import java.util.UUID;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;

import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;

import lombok.Setter;

public class ScheduledJobExecutor extends QuartzJobBean {

    @Setter(onMethod_ = @Autowired)
    private ExecuteScheduledJobUseCase executeScheduledJobUseCase;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        UUID taskId = UUID.fromString(context.getMergedJobDataMap().getString("taskId"));
        executeScheduledJobUseCase.execute(taskId);
    }
}
