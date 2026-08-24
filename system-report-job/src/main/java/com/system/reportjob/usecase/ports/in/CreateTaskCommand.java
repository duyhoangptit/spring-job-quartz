package com.system.reportjob.usecase.ports.in;

import java.util.UUID;

import com.system.reportjob.domain.model.TriggerDefinition;

public record CreateTaskCommand(
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String timezoneId,
        Integer priority,
        String description) {}
