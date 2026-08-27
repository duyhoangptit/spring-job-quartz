package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HoldFundsStepExecutionListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info("Holding funds for 10 seconds");
        // check condition before start step, remove file temp if existing.
    }

    @Override
    public @Nullable ExitStatus afterStep(StepExecution stepExecution) {
        return StepExecutionListener.super.afterStep(stepExecution);
    }
}
