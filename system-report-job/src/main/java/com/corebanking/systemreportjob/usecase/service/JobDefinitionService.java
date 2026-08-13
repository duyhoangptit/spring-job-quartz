package com.corebanking.systemreportjob.usecase.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionInUseException;
import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.in.JobDefinitionUseCase;
import com.corebanking.systemreportjob.usecase.ports.in.UpdateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;

@Service
public class JobDefinitionService implements JobDefinitionUseCase {

    private final JobDefinitionRepositoryPort repositoryPort;
    private final TaskRepositoryPort taskRepositoryPort;

    public JobDefinitionService(JobDefinitionRepositoryPort repositoryPort, TaskRepositoryPort taskRepositoryPort) {
        this.repositoryPort = repositoryPort;
        this.taskRepositoryPort = taskRepositoryPort;
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
        if (taskRepositoryPort.existsByJobDefinitionId(id)) {
            throw new JobDefinitionInUseException(id);
        }
        repositoryPort.delete(id);
    }
}
