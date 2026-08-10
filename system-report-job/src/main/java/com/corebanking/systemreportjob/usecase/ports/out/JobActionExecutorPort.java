package com.corebanking.systemreportjob.usecase.ports.out;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

public interface JobActionExecutorPort {
    void execute(JobDefinition definition);
}
