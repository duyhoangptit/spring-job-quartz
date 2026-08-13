package com.corebanking.systemreportjob.domain.exception;

import java.util.UUID;

public class JobDefinitionInUseException extends BusinessException {
    public JobDefinitionInUseException(UUID jobDefinitionId) {
        super(ErrorCode.JOB_DEFINITION_IN_USE, jobDefinitionId);
    }
}
