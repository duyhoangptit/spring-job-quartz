package com.corebanking.systemreportjob.infrastructure.web.dto.response;

import com.corebanking.systemreportjob.domain.model.TaskDetail;

public record TaskDetailResponse(TaskResponse task, JobDefinitionResponse jobDefinition, String triggerState) {
    public static TaskDetailResponse from(TaskDetail detail) {
        return new TaskDetailResponse(
                TaskResponse.from(detail.task()),
                JobDefinitionResponse.from(detail.jobDefinition()),
                detail.state().name());
    }
}
