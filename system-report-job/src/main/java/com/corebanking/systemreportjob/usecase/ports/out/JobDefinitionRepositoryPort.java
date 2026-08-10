package com.corebanking.systemreportjob.usecase.ports.out;

import java.util.Optional;
import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

public interface JobDefinitionRepositoryPort {
    JobDefinition save(JobDefinition definition);

    Optional<JobDefinition> findById(UUID id);

    void delete(UUID id);
}
