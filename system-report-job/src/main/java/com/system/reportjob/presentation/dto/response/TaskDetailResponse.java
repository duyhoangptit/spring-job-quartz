package com.system.reportjob.presentation.dto.response;

import com.system.reportjob.domain.model.TaskDetail;

public record TaskDetailResponse(TaskResponse task, JobDefinitionResponse jobDefinition, String triggerState) {
    public static TaskDetailResponse from(TaskDetail detail) {
        return new TaskDetailResponse(
                TaskResponse.from(detail.task()),
                JobDefinitionResponse.from(detail.jobDefinition()),
                detail.state().name());
    }
}
