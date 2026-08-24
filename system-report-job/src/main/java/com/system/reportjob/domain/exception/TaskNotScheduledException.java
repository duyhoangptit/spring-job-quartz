package com.system.reportjob.domain.exception;

import java.util.UUID;

public class TaskNotScheduledException extends BusinessException {
    public TaskNotScheduledException(UUID taskId) {
        super(ErrorCode.TASK_NOT_SCHEDULED, taskId);
    }
}
