package com.corebanking.systemreportjob.usecase.ports.out;

import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerState;

public interface SchedulerGatewayPort {
    void scheduleTask(ScheduledTask task);

    void unscheduleTask(UUID taskId);

    void pauseTask(UUID taskId);

    void resumeTask(UUID taskId);

    TriggerState getTriggerState(UUID taskId);
}
