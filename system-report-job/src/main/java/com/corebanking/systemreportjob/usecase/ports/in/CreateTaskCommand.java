package com.corebanking.systemreportjob.usecase.ports.in;

import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.TriggerDefinition;

public record CreateTaskCommand(
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String timezoneId,
        Integer priority,
        String description) {}
