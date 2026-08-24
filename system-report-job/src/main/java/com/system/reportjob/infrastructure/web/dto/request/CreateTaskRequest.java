package com.system.reportjob.infrastructure.web.dto.request;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.system.reportjob.domain.model.TriggerType;
import com.system.reportjob.infrastructure.common.ValidCron;

public record CreateTaskRequest(
        @NotBlank String name,
        @NotBlank String group,
        @NotNull UUID jobDefinitionId,
        @NotNull TriggerType triggerType,
        @ValidCron String cronExpression,
        Integer intervalInSeconds,
        Integer repeatCount,
        Integer intervalInDays,
        Integer intervalInMinutes,
        LocalTime startingDailyAt,
        LocalTime endingDailyAt,
        String calendarName,
        String timezoneId,
        Integer priority,
        String description) {}
