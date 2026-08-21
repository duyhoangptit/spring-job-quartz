package com.corebanking.systemreportjob.usecase.ports.out;

import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerState;

public interface SchedulerGatewayPort {
    void scheduleTask(ScheduledTask task);

    void unscheduleTask(UUID taskId);

    void pauseTask(UUID taskId);

    void resumeTask(UUID taskId);

    /**
     * Yêu cầu Quartz thực thi job của task này ngay lập tức, một lần, mà không ảnh hưởng tới lịch
     * chạy định kỳ hiện có. Task phải đã được {@link #scheduleTask} trước đó (job phải tồn tại
     * trong Quartz).
     */
    void triggerNow(UUID taskId);

    TriggerState getTriggerState(UUID taskId);
}
