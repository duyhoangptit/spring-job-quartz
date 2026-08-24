package com.system.reportjob.domain.model;

import java.util.UUID;

public record ScheduledTask(
        UUID id,
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String timezoneId,
        Integer priority,
        String description) {
    public ScheduledTask {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên task không được rỗng");
        }
        if (jobDefinitionId == null) {
            throw new IllegalArgumentException("Task phải gắn với một JobDefinition");
        }
    }
}
