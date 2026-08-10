package com.corebanking.systemreportjob.usecase.ports.in;

public record CreateJobDefinitionCommand(String jobType, String expression, String description) {}
