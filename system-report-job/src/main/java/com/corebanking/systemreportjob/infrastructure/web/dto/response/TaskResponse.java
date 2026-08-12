package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;

public record TaskResponse(UUID id, String name, String group, UUID jobDefinitionId, String description) {
    public static TaskResponse from(ScheduledTask task) {
        return new TaskResponse(task.id(), task.name(), task.group(), task.jobDefinitionId(), task.description());
    }
}
