package com.system.reportjob.infrastructure.web.dto.response;

import java.util.UUID;

import com.system.reportjob.domain.model.JobDefinition;

public record JobDefinitionResponse(UUID id, String jobType, String expression, String description) {
    public static JobDefinitionResponse from(JobDefinition definition) {
        return new JobDefinitionResponse(
                definition.id(), definition.jobType(), definition.expression(), definition.description());
    }
}
