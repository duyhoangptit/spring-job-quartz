package com.system.reportjob.usecase.ports.out;

import com.system.reportjob.domain.model.JobDefinition;

public interface JobActionExecutorPort {
    void execute(JobDefinition definition);
}
