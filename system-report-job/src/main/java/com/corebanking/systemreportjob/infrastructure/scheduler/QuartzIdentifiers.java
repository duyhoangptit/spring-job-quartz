package com.corebanking.systemreportjob.infrastructure.scheduler;

import java.util.UUID;

import org.quartz.JobKey;
import org.quartz.TriggerKey;

public final class QuartzIdentifiers {
    public static final String JOB_GROUP = "system-report-job";

    private QuartzIdentifiers() {}

    public static JobKey jobKey(UUID taskId) {
        return JobKey.jobKey(taskId.toString(), JOB_GROUP);
    }

    public static TriggerKey triggerKey(UUID taskId) {
        return TriggerKey.triggerKey(taskId + "-trigger", JOB_GROUP);
    }
}
