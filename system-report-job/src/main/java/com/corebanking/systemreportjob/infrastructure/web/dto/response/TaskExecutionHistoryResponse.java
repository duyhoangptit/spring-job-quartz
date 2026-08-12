package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;

public record TaskExecutionHistoryResponse(
        UUID id, UUID taskId, String taskName, Instant startTime, Instant endTime, String exceptionMessage) {
    public static TaskExecutionHistoryResponse from(TaskExecutionRecord record) {
        return new TaskExecutionHistoryResponse(
                record.id(),
                record.taskId(),
                record.taskName(),
                record.startTime(),
                record.endTime(),
                record.exceptionMessage());
    }
}
