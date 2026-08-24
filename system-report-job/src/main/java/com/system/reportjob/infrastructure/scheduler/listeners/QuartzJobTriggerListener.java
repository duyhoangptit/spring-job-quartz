package com.system.reportjob.infrastructure.scheduler.listeners;

import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.TriggerListener;
import org.springframework.stereotype.Component;

@Component
public class QuartzJobTriggerListener implements TriggerListener {

    @Override
    public String getName() {
        return "systemReportJobTriggerListener";
    }

    @Override
    public void triggerFired(Trigger trigger, JobExecutionContext context) {}

    @Override
    public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
        return false;
    }

    @Override
    public void triggerMisfired(Trigger trigger) {}

    @Override
    public void triggerComplete(
            Trigger trigger, JobExecutionContext context, Trigger.CompletedExecutionInstruction instruction) {}
}
