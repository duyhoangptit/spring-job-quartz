package com.system.reportjob.presentation.dto.response;

import java.util.UUID;

import com.system.reportjob.domain.model.ScheduledTask;

public record TaskResponse(UUID id, String name, String group, UUID jobDefinitionId, String description) {
    public static TaskResponse from(ScheduledTask task) {
        return new TaskResponse(task.id(), task.name(), task.group(), task.jobDefinitionId(), task.description());
    }
}
