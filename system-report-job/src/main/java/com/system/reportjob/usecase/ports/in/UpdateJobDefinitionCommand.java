package com.system.reportjob.usecase.ports.in;

public record UpdateJobDefinitionCommand(String jobType, String expression, String description) {}
