package com.corebanking.systemreportjob.domain.exception;

import java.util.UUID;

public class TaskNotFoundException extends BusinessException {
    public TaskNotFoundException(UUID taskId) {
        super(ErrorCode.TASK_NOT_FOUND, taskId);
    }
}
