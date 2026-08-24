package com.system.reportjob.infrastructure.web.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateJobDefinitionRequest(@NotBlank String jobType, String expression, String description) {}
