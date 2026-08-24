package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TaskDetail;
import com.system.reportjob.domain.model.TriggerDefinition;
import com.system.reportjob.domain.model.TriggerState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import com.system.reportjob.domain.exception.JobDefinitionNotFoundException;
import com.system.reportjob.domain.exception.TaskNotFoundException;
import com.system.reportjob.domain.model.*;
import com.system.reportjob.usecase.ports.in.CreateTaskCommand;
import com.system.reportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.system.reportjob.usecase.ports.out.SchedulerGatewayPort;
import com.system.reportjob.usecase.ports.out.TaskRepositoryPort;

class TaskOrchestratorTest {

    private TaskRepositoryPort taskRepositoryPort;
    private JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private SchedulerGatewayPort schedulerGatewayPort;
    private TaskOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        taskRepositoryPort = mock(TaskRepositoryPort.class);
        jobDefinitionRepositoryPort = mock(JobDefinitionRepositoryPort.class);
        schedulerGatewayPort = mock(SchedulerGatewayPort.class);
        orchestrator = new TaskOrchestrator(taskRepositoryPort, jobDefinitionRepositoryPort, schedulerGatewayPort);
    }

    private JobDefinition sampleJobDefinition(UUID id) {
        return new JobDefinition(id, "ECHO", "{}", "sample");
    }

    private ScheduledTask sampleTask(UUID id, UUID jobDefinitionId) {
        return new ScheduledTask(
                id,
                "daily-report",
                "reports",
                jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"),
                "UTC",
                5,
                null);
    }

    @Test
    void createSavesTaskWhenJobDefinitionExists() {
        UUID jobDefinitionId = UUID.randomUUID();
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId))
                .thenReturn(Optional.of(sampleJobDefinition(jobDefinitionId)));
        when(taskRepositoryPort.save(any(ScheduledTask.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTaskCommand command = new CreateTaskCommand(
                "daily-report", "reports", jobDefinitionId, new TriggerDefinition.Cron("0 0 1 * * ?"), "UTC", 5, null);

        ScheduledTask result = orchestrator.create(command);

        assertThat(result.name()).isEqualTo("daily-report");
        verify(taskRepositoryPort).save(any(ScheduledTask.class));
    }

    @Test
    void createThrowsWhenJobDefinitionMissing() {
        UUID jobDefinitionId = UUID.randomUUID();
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.empty());

        CreateTaskCommand command = new CreateTaskCommand(
                "daily-report", "reports", jobDefinitionId, new TriggerDefinition.Cron("0 0 1 * * ?"), "UTC", 5, null);

        assertThatThrownBy(() -> orchestrator.create(command)).isInstanceOf(JobDefinitionNotFoundException.class);
        verifyNoInteractions(taskRepositoryPort);
    }

    @Test
    void startSchedulesTaskViaGateway() {
        UUID taskId = UUID.randomUUID();
        ScheduledTask task = sampleTask(taskId, UUID.randomUUID());
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));

        orchestrator.start(taskId);

        verify(schedulerGatewayPort).scheduleTask(task);
    }

    @Test
    void startThrowsWhenTaskMissing() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.start(taskId)).isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(schedulerGatewayPort);
    }

    @Test
    void pauseDelegatesToGatewayAfterExistenceCheck() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(sampleTask(taskId, UUID.randomUUID())));

        orchestrator.pause(taskId);

        verify(schedulerGatewayPort).pauseTask(taskId);
    }

    @Test
    void resumeDelegatesToGatewayAfterExistenceCheck() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(sampleTask(taskId, UUID.randomUUID())));

        orchestrator.resume(taskId);

        verify(schedulerGatewayPort).resumeTask(taskId);
    }

    @Test
    void deleteUnschedulesThenRemovesFromRepository() {
        UUID taskId = UUID.randomUUID();
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(sampleTask(taskId, UUID.randomUUID())));

        orchestrator.delete(taskId);

        var inOrder = inOrder(schedulerGatewayPort, taskRepositoryPort);
        inOrder.verify(schedulerGatewayPort).unscheduleTask(taskId);
        inOrder.verify(taskRepositoryPort).delete(taskId);
    }

    @Test
    void startAllSchedulesEveryTask() {
        ScheduledTask t1 = sampleTask(UUID.randomUUID(), UUID.randomUUID());
        ScheduledTask t2 = sampleTask(UUID.randomUUID(), UUID.randomUUID());
        when(taskRepositoryPort.findAll()).thenReturn(List.of(t1, t2));

        orchestrator.startAll();

        verify(schedulerGatewayPort).scheduleTask(t1);
        verify(schedulerGatewayPort).scheduleTask(t2);
    }

    @Test
    void searchDelegatesToRepositoryPort() {
        PageResult<ScheduledTask> expected = new PageResult<>(List.of(), 0, 20, 0, 0);
        when(taskRepositoryPort.search("report", PageRequest.of(0, 20))).thenReturn(expected);

        PageResult<ScheduledTask> result = orchestrator.search("report", PageRequest.of(0, 20));

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getDetailCombinesTaskJobDefinitionAndTriggerState() {
        UUID taskId = UUID.randomUUID();
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = sampleTask(taskId, jobDefinitionId);
        JobDefinition definition = sampleJobDefinition(jobDefinitionId);
        when(taskRepositoryPort.findById(taskId)).thenReturn(Optional.of(task));
        when(jobDefinitionRepositoryPort.findById(jobDefinitionId)).thenReturn(Optional.of(definition));
        when(schedulerGatewayPort.getTriggerState(taskId)).thenReturn(TriggerState.NORMAL);

        TaskDetail detail = orchestrator.getDetail(taskId);

        assertThat(detail.task()).isEqualTo(task);
        assertThat(detail.jobDefinition()).isEqualTo(definition);
        assertThat(detail.state()).isEqualTo(TriggerState.NORMAL);
    }
}
