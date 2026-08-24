package com.system.reportjob.usecase.ports.in;

import java.util.UUID;

import com.system.reportjob.domain.model.TriggerDefinition;

public record CreateTaskCommand(
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String calendarName,
        String timezoneId,
        Integer priority,
        String description) {

    /** Tương thích ngược với code hiện có chưa truyền calendarName (mặc định null). */
    public CreateTaskCommand(
            String name,
            String group,
            UUID jobDefinitionId,
            TriggerDefinition trigger,
            String timezoneId,
            Integer priority,
            String description) {
        this(name, group, jobDefinitionId, trigger, null, timezoneId, priority, description);
    }
}
