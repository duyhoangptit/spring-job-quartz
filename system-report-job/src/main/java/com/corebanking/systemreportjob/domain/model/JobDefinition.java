package com.corebanking.systemreportjob.domain.model;

import java.util.UUID;

public record JobDefinition(UUID id, String jobType, String expression, String description) {
    public JobDefinition {
        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType không được rỗng");
        }
    }
}
