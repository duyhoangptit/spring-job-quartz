package com.system.reportjob.infrastructure.web.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.system.reportjob.infrastructure.common.ApiResponse;
import com.system.reportjob.infrastructure.web.dto.request.CreateJobDefinitionRequest;
import com.system.reportjob.infrastructure.web.dto.request.UpdateJobDefinitionRequest;
import com.system.reportjob.infrastructure.web.dto.response.JobDefinitionResponse;
import com.system.reportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.system.reportjob.usecase.ports.in.JobDefinitionUseCase;
import com.system.reportjob.usecase.ports.in.UpdateJobDefinitionCommand;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/job-definitions")
@Tag(name = "Job definition", description = "Job definition management")
@SecurityRequirement(name = "bearerAuth")
public class JobDefinitionController {

    private final JobDefinitionUseCase jobDefinitionUseCase;

    public JobDefinitionController(JobDefinitionUseCase jobDefinitionUseCase) {
        this.jobDefinitionUseCase = jobDefinitionUseCase;
    }

    @PostMapping
    public ApiResponse<JobDefinitionResponse> create(@RequestBody @Valid CreateJobDefinitionRequest request) {
        var definition = jobDefinitionUseCase.create(
                new CreateJobDefinitionCommand(request.jobType(), request.expression(), request.description()));
        return ApiResponse.ok(JobDefinitionResponse.from(definition));
    }

    @PutMapping("/{id}")
    public ApiResponse<JobDefinitionResponse> update(
            @PathVariable UUID id, @RequestBody @Valid UpdateJobDefinitionRequest request) {
        var definition = jobDefinitionUseCase.update(
                id, new UpdateJobDefinitionCommand(request.jobType(), request.expression(), request.description()));
        return ApiResponse.ok(JobDefinitionResponse.from(definition));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        jobDefinitionUseCase.delete(id);
        return ApiResponse.ok();
    }
}
