package com.system.reportjob.domain.model;

public record TaskDetail(ScheduledTask task, JobDefinition jobDefinition, TriggerState state) {}
