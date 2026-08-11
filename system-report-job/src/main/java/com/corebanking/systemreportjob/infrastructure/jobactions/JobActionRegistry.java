package com.corebanking.systemreportjob.infrastructure.jobactions;

import java.util.List;

import org.springframework.stereotype.Component;

import com.corebanking.systemreportjob.domain.exception.BusinessException;
import com.corebanking.systemreportjob.domain.exception.ErrorCode;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;

@Component
public class JobActionRegistry implements JobActionExecutorPort {

    private final List<JobAction> actions;

    public JobActionRegistry(List<JobAction> actions) {
        this.actions = actions;
    }

    @Override
    public void execute(JobDefinition definition) {
        actions.stream()
                .filter(action -> action.matches(definition.jobType()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, definition.jobType()))
                .execute(definition);
    }
}
