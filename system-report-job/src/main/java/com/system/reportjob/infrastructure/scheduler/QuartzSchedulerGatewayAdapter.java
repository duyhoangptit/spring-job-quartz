package com.system.reportjob.infrastructure.scheduler;

import java.util.Set;
import java.util.UUID;

import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.exception.TaskNotScheduledException;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TriggerState;
import com.system.reportjob.usecase.ports.out.SchedulerGatewayPort;

@Component
public class QuartzSchedulerGatewayAdapter implements SchedulerGatewayPort {

    private final Scheduler scheduler;
    private final QuartzTriggerFactory triggerFactory;

    public QuartzSchedulerGatewayAdapter(Scheduler scheduler, QuartzTriggerFactory triggerFactory) {
        this.scheduler = scheduler;
        this.triggerFactory = triggerFactory;
    }

    @Override
    public void scheduleTask(ScheduledTask task) {
        JobDetail jobDetail = JobBuilder.newJob(ScheduledJobExecutor.class)
                .withIdentity(QuartzIdentifiers.jobKey(task.id()))
                .usingJobData("taskId", task.id().toString())
                .usingJobData("taskName", task.name())
                .storeDurably()
                .build();
        Trigger trigger = triggerFactory.build(task);
        try {
            scheduler.scheduleJob(jobDetail, Set.of(trigger), true);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể lên lịch task " + task.id(), e);
        }
    }

    @Override
    public void unscheduleTask(UUID taskId) {
        try {
            scheduler.deleteJob(QuartzIdentifiers.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể huỷ lịch task " + taskId, e);
        }
    }

    @Override
    public void pauseTask(UUID taskId) {
        try {
            scheduler.pauseJob(QuartzIdentifiers.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể tạm dừng task " + taskId, e);
        }
    }

    @Override
    public void resumeTask(UUID taskId) {
        try {
            scheduler.resumeJob(QuartzIdentifiers.jobKey(taskId));
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể tiếp tục task " + taskId, e);
        }
    }

    @Override
    public void triggerNow(UUID taskId) {
        JobKey jobKey = QuartzIdentifiers.jobKey(taskId);
        try {
            if (!scheduler.checkExists(jobKey)) {
                throw new TaskNotScheduledException(taskId);
            }
            scheduler.triggerJob(jobKey);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể trigger ngay task " + taskId, e);
        }
    }

    @Override
    public TriggerState getTriggerState(UUID taskId) {
        try {
            JobKey jobKey = QuartzIdentifiers.jobKey(taskId);
            if (!scheduler.checkExists(jobKey)) {
                return TriggerState.NONE;
            }
            for (Trigger trigger : scheduler.getTriggersOfJob(jobKey)) {
                return mapState(scheduler.getTriggerState(trigger.getKey()));
            }
            return TriggerState.NONE;
        } catch (SchedulerException e) {
            throw new IllegalStateException("Không thể lấy trạng thái task " + taskId, e);
        }
    }

    private TriggerState mapState(Trigger.TriggerState state) {
        return switch (state) {
            case NORMAL -> TriggerState.NORMAL;
            case PAUSED -> TriggerState.PAUSED;
            case COMPLETE -> TriggerState.COMPLETE;
            case ERROR -> TriggerState.ERROR;
            case BLOCKED -> TriggerState.BLOCKED;
            case NONE -> TriggerState.NONE;
        };
    }
}
