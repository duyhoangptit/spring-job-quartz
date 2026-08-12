package com.corebanking.systemreportjob.infrastructure.web.dto.request;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.corebanking.systemreportjob.infrastructure.common.ValidCron;

public record CreateTaskRequest(
        @NotBlank String name,
        @NotBlank String group,
        @NotNull UUID jobDefinitionId,
        @NotBlank String triggerType,
        @ValidCron String cronExpression,
        Integer intervalInSeconds,
        Integer repeatCount,
        Integer intervalInDays,
        Integer intervalInMinutes,
        LocalTime startingDailyAt,
        LocalTime endingDailyAt,
        String timezoneId,
        Integer priority,
        String description) {}
