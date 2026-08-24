package com.system.reportjob.usecase.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.JobDefinitionNotFoundException;
import com.system.reportjob.domain.exception.TaskNotFoundException;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import com.system.reportjob.usecase.ports.out.JobActionExecutorPort;
import com.system.reportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.system.reportjob.usecase.ports.out.TaskRepositoryPort;

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
