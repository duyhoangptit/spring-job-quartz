package com.corebanking.systemreportjob.usecase.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.in.JobDefinitionUseCase;
import com.corebanking.systemreportjob.usecase.ports.in.UpdateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;

@Service
public class JobDefinitionService implements JobDefinitionUseCase {

    private final JobDefinitionRepositoryPort repositoryPort;

    public JobDefinitionService(JobDefinitionRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public JobDefinition create(CreateJobDefinitionCommand command) {
        JobDefinition definition =
                new JobDefinition(UUID.randomUUID(), command.jobType(), command.expression(), command.description());
        return repositoryPort.save(definition);
    }

    @Override
    public JobDefinition update(UUID id, UpdateJobDefinitionCommand command) {
        repositoryPort.findById(id).orElseThrow(() -> new JobDefinitionNotFoundException(id));
        JobDefinition updated = new JobDefinition(id, command.jobType(), command.expression(), command.description());
        return repositoryPort.save(updated);
    }

    @Override
    public void delete(UUID id) {
        repositoryPort.delete(id);
    }
}
