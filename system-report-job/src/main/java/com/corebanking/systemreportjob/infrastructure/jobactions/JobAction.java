package com.corebanking.systemreportjob.infrastructure.jobactions;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

public interface JobAction {
    boolean matches(String jobType);

    void execute(JobDefinition definition);
}
