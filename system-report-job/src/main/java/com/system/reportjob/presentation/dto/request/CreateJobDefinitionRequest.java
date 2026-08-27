package com.system.reportjob.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateJobDefinitionRequest(@NotBlank String jobType, String expression, String description) {}
