package com.corebanking.systemreportjob.usecase.ports.in;

import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

public interface JobDefinitionUseCase {
    JobDefinition create(CreateJobDefinitionCommand command);

    JobDefinition update(UUID id, UpdateJobDefinitionCommand command);

    void delete(UUID id);
}
