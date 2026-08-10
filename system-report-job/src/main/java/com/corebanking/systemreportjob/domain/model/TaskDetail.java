package com.corebanking.systemreportjob.domain.model;

public record TaskDetail(ScheduledTask task, JobDefinition jobDefinition, TriggerState state) {}
