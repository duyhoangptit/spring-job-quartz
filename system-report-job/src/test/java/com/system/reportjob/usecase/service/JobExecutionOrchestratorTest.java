package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.system.reportjob.domain.exception.JobDefinitionNotFoundException;
import com.system.reportjob.domain.exception.TaskNotFoundException;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TriggerDefinition;
import com.system.reportjob.usecase.ports.out.JobActionExecutorPort;
import com.system.reportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.system.reportjob.usecase.ports.out.TaskRepositoryPort;

class JobExecutionOrchestratorTest {

    private TaskRepositoryPort taskRepositoryPort;
    private JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private JobActionExecutorPort jobActionExecutorPort;
    private JobExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        taskRepositoryPort = mock(TaskRepositoryPort.class);
        jobDefinitionRepositoryPort = mock(JobDefinitionRepositoryPort.class);
        jobActionExecutorPort = mock(JobActionExecutorPort.class);
        orchestrator =
                new JobExecutionOrchestrator(taskRepositoryPort, jobDefinitionRepositoryPort, jobActionExecutorPort);
    }

    @Test
    void dispatchesToJobActionExecutorForResolvedJobDefinition() {
        UUID taskId = UUID.randomUUID();
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                taskId,
                "daily-report",
                "reports",
                jobDefinitionId,
                new TriggerDefinition.Simple(60, 0),
                "UTC",
                1,
                null);
        JobDefinition definition = new JobDefinition(jobDefinitionId, "ECHO", "{}", null);
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.of(definition));

        orchestrator.execute(taskId);

        verify(jobActionExecutorPort).execute(definition);
    }

    @Test
    void throwsTaskNotFoundWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.execute(taskId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(jobActionExecutorPort);
    }

    @Test
    void throwsJobDefinitionNotFoundWhenDefinitionMissing() {
        UUID taskId = UUID.randomUUID();
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                taskId,
                "daily-report",
                "reports",
                jobDefinitionId,
                new TriggerDefinition.Simple(60, 0),
                "UTC",
                1,
                null);
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.execute(taskId)).isInstanceOf(JobDefinitionNotFoundException.class);
        verifyNoInteractions(jobActionExecutorPort);
    }
}
