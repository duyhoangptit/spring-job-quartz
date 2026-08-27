package com.system.reportjob.domain.exception;

public enum ErrorCode {
    TASK_NOT_FOUND("task.not_found"),
    TASK_NOT_SCHEDULED("task.not_scheduled"),
    JOB_DEFINITION_NOT_FOUND("job_definition.not_found"),
    JOB_DEFINITION_IN_USE("job_definition.in_use"),
    CRON_INVALID("cron.invalid"),
    VALIDATION_ERROR("validation.error"),
    PGP_KEY_CONFIG_NOT_FOUND("pgp_key_config.not_found"),
    PGP_KEY_CONFIG_ALREADY_EXISTS("pgp_key_config.already_exists"),
    PGP_DECRYPTION_FAILED("pgp.decryption_failed"),
    PGP_SIGNATURE_INVALID("pgp.signature_invalid"),
    PGP_ENCRYPTION_FAILED("pgp.encryption_failed");

    private final String messageKey;

    ErrorCode(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
