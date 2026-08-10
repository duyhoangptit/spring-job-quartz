package com.corebanking.systemreportjob.usecase.ports.in;

public record UpdateJobDefinitionCommand(String jobType, String expression, String description) {}
