package com.corebanking.systemreportjob.domain.model;

import java.time.Instant;
import java.util.UUID;

public record TaskExecutionRecord(
        UUID id, UUID taskId, String taskName, Instant startTime, Instant endTime, String exceptionMessage) {}
