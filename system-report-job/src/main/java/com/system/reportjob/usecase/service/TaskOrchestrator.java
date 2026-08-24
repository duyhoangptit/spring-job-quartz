package com.system.reportjob.usecase.service;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.system.reportjob.domain.exception.JobDefinitionNotFoundException;
import com.system.reportjob.domain.exception.TaskNotFoundException;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TaskDetail;
import com.system.reportjob.usecase.ports.in.CreateTaskCommand;
import com.system.reportjob.usecase.ports.in.TaskManagementUseCase;
import com.system.reportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.system.reportjob.usecase.ports.out.SchedulerGatewayPort;
import com.system.reportjob.usecase.ports.out.TaskRepositoryPort;

@Service
public class TaskOrchestrator implements TaskManagementUseCase {

    private final TaskRepositoryPort taskRepositoryPort;
    private final JobDefinitionRepositoryPort jobDefinitionRepositoryPort;
    private final SchedulerGatewayPort schedulerGatewayPort;

    public TaskOrchestrator(
            TaskRepositoryPort taskRepositoryPort,
            JobDefinitionRepositoryPort jobDefinitionRepositoryPort,
            SchedulerGatewayPort schedulerGatewayPort) {
        this.taskRepositoryPort = taskRepositoryPort;
        this.jobDefinitionRepositoryPort = jobDefinitionRepositoryPort;
        this.schedulerGatewayPort = schedulerGatewayPort;
    }

    @Override
    public ScheduledTask create(CreateTaskCommand command) {
        jobDefinitionRepositoryPort
                .findById(command.jobDefinitionId())
                .orElseThrow(() -> new JobDefinitionNotFoundException(command.jobDefinitionId()));

        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                command.name(),
                command.group(),
                command.jobDefinitionId(),
                command.trigger(),
                command.timezoneId(),
                command.priority(),
                command.description());
        return taskRepositoryPort.save(task);
    }

    @Override
    public void start(UUID taskId) {
        schedulerGatewayPort.scheduleTask(requireTask(taskId));
    }

    @Override
    public void pause(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.pauseTask(taskId);
    }

    @Override
    public void resume(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.resumeTask(taskId);
    }

    @Override
    public void triggerNow(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.triggerNow(taskId);
    }

    @Override
    public void delete(UUID taskId) {
        requireTask(taskId);
        schedulerGatewayPort.unscheduleTask(taskId);
        taskRepositoryPort.delete(taskId);
    }

    @Override
    public void startAll() {
        taskRepositoryPort.findAll().forEach(schedulerGatewayPort::scheduleTask);
    }

    @Override
    public PageResult<ScheduledTask> search(String keyword, Pageable pageable) {
        return taskRepositoryPort.search(keyword, pageable);
    }

    @Override
    public TaskDetail getDetail(UUID taskId) {
        ScheduledTask task = requireTask(taskId);
        JobDefinition definition = jobDefinitionRepositoryPort
                .findById(task.jobDefinitionId())
                .orElseThrow(() -> new JobDefinitionNotFoundException(task.jobDefinitionId()));
        return new TaskDetail(task, definition, schedulerGatewayPort.getTriggerState(taskId));
    }

    private ScheduledTask requireTask(UUID taskId) {
        return taskRepositoryPort.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }
}
