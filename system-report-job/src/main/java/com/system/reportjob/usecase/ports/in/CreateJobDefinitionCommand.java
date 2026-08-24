package com.system.reportjob.usecase.ports.in;

public record CreateJobDefinitionCommand(String jobType, String expression, String description) {}
