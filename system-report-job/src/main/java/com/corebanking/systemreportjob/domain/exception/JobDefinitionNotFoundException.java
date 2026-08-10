package com.corebanking.systemreportjob.domain.exception;

import java.util.UUID;

public class JobDefinitionNotFoundException extends BusinessException {
    public JobDefinitionNotFoundException(UUID jobDefinitionId) {
        super(ErrorCode.JOB_DEFINITION_NOT_FOUND, jobDefinitionId);
    }
}
