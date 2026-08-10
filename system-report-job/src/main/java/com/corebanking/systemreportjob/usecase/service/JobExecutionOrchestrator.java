package com.corebanking.systemreportjob.usecase.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;

@Service
public class JobExecutionOrchestrator implements ExecuteScheduledJobUseCase {

    private final TaskRepositoryPort taskRepositoryPort;
    private final JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private final JobActionExecutorPort jobActionExecutorPort;

    public JobExecutionOrchestrator(
            TaskRepositoryPort taskRepositoryPort,
            JobDefinitionRepositoryPort jobDefinitionRepositoryPort,
            JobActionExecutorPort jobActionExecutorPort) {
        this.taskRepositoryPort = taskRepositoryPort;
        this.jobDefinitionRepositoryPort = jobDefinitionRepositoryPort;
        this.jobActionExecutorPort = jobActionExecutorPort;
    }

    @Override
    public void execute(UUID taskId) {
        ScheduledTask task = taskRepositoryPort.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        JobDefinition definition = jobDefinitionRepositoryPort
                .findById(task.jobDefinitionId())
                .orElseThrow(() -> new JobDefinitionNotFoundException(task.jobDefinitionId()));
        jobActionExecutorPort.execute(definition);
    }
}
