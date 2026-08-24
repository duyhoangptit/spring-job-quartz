package com.system.reportjob.usecase.ports.out;

import java.util.Optional;
import java.util.UUID;

import com.system.reportjob.domain.model.JobDefinition;

public interface JobDefinitionRepositoryPort {
    JobDefinition save(JobDefinition definition);

    Optional<JobDefinition> findById(UUID id);

    void delete(UUID id);
}
