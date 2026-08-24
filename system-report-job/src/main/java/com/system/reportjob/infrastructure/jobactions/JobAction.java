package com.system.reportjob.infrastructure.jobactions;

import com.system.reportjob.domain.model.JobDefinition;

public interface JobAction {
    boolean matches(String jobType);

    void execute(JobDefinition definition);
}
