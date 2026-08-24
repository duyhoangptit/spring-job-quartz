package com.system.reportjob.usecase.ports.in;

import java.util.UUID;

import com.system.reportjob.domain.model.JobDefinition;

public interface JobDefinitionUseCase {
    JobDefinition create(CreateJobDefinitionCommand command);

    JobDefinition update(UUID id, UpdateJobDefinitionCommand command);

    void delete(UUID id);
}
