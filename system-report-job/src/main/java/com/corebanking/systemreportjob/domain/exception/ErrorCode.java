package com.corebanking.systemreportjob.domain.exception;

public enum ErrorCode {
    TASK_NOT_FOUND("task.not_found"),
    JOB_DEFINITION_NOT_FOUND("job_definition.not_found"),
    JOB_DEFINITION_IN_USE("job_definition.in_use"),
    CRON_INVALID("cron.invalid"),
    VALIDATION_ERROR("validation.error");

    private final String messageKey;

    ErrorCode(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
