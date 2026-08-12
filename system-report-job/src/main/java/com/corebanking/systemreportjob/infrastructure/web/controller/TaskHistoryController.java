package com.corebanking.systemreportjob.infrastructure.web.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.infrastructure.common.ApiResponse;
import com.corebanking.systemreportjob.infrastructure.common.PageResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.TaskExecutionHistoryResponse;
import com.corebanking.systemreportjob.usecase.ports.in.TaskHistoryQueryUseCase;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/task-history")
@Tag(name = "Task history", description = "Task execution history")
@SecurityRequirement(name = "bearerAuth")
public class TaskHistoryController {

    private final TaskHistoryQueryUseCase taskHistoryQueryUseCase;

    public TaskHistoryController(TaskHistoryQueryUseCase taskHistoryQueryUseCase) {
        this.taskHistoryQueryUseCase = taskHistoryQueryUseCase;
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<TaskExecutionHistoryResponse>> search(
            @RequestParam(required = false) String taskName, Pageable pageable) {
        PageResult<TaskExecutionRecord> result = taskHistoryQueryUseCase.search(taskName, pageable);
        return ApiResponse.ok(PageResponse.from(result, TaskExecutionHistoryResponse::from));
    }
}
