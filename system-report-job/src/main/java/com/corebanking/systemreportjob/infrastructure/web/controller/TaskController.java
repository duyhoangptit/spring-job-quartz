package com.corebanking.systemreportjob.infrastructure.web.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.common.ApiResponse;
import com.corebanking.systemreportjob.infrastructure.common.PageResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.request.CreateTaskRequest;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.TaskDetailResponse;
import com.corebanking.systemreportjob.infrastructure.web.dto.response.TaskResponse;
import com.corebanking.systemreportjob.usecase.ports.in.CreateTaskCommand;
import com.corebanking.systemreportjob.usecase.ports.in.TaskManagementUseCase;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/tasks")
@Tag(name = "Task", description = "Task management")
@SecurityRequirement(name = "bearerAuth")
public class TaskController {

    private final TaskManagementUseCase taskManagementUseCase;

    public TaskController(TaskManagementUseCase taskManagementUseCase) {
        this.taskManagementUseCase = taskManagementUseCase;
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<TaskResponse>> search(
            @RequestParam(required = false) String keyword, Pageable pageable) {
        PageResult<ScheduledTask> result = taskManagementUseCase.search(keyword, pageable);
        return ApiResponse.ok(PageResponse.from(result, TaskResponse::from));
    }

    @PostMapping
    public ApiResponse<TaskResponse> create(@RequestBody @Valid CreateTaskRequest request) {
        ScheduledTask task = taskManagementUseCase.create(toCommand(request));
        return ApiResponse.ok(TaskResponse.from(task));
    }

    @PostMapping("/start/{id}")
    public ApiResponse<Void> start(@PathVariable UUID id) {
        taskManagementUseCase.start(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetailResponse> detail(@PathVariable UUID id) {
        return ApiResponse.ok(TaskDetailResponse.from(taskManagementUseCase.getDetail(id)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        taskManagementUseCase.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/pause/{id}")
    public ApiResponse<Void> pause(@PathVariable UUID id) {
        taskManagementUseCase.pause(id);
        return ApiResponse.ok();
    }

    @PutMapping("/resume/{id}")
    public ApiResponse<Void> resume(@PathVariable UUID id) {
        taskManagementUseCase.resume(id);
        return ApiResponse.ok();
    }

    private CreateTaskCommand toCommand(CreateTaskRequest request) {
        String triggerType = request.triggerType().toUpperCase();
        TriggerDefinition trigger =
                switch (triggerType) {
                    case "CRON" -> new TriggerDefinition.Cron(
                            require(request.cronExpression(), "cronExpression", triggerType));
                    case "SIMPLE" -> new TriggerDefinition.Simple(
                            require(request.intervalInSeconds(), "intervalInSeconds", triggerType),
                            require(request.repeatCount(), "repeatCount", triggerType));
                    case "CALENDAR_INTERVAL" -> new TriggerDefinition.CalendarInterval(
                            require(request.intervalInDays(), "intervalInDays", triggerType));
                    case "DAILY_TIME_INTERVAL" -> new TriggerDefinition.DailyTimeInterval(
                            require(request.startingDailyAt(), "startingDailyAt", triggerType),
                            require(request.endingDailyAt(), "endingDailyAt", triggerType),
                            require(request.intervalInMinutes(), "intervalInMinutes", triggerType));
                    default -> throw new IllegalArgumentException("Unknown triggerType: " + request.triggerType());
                };
        return new CreateTaskCommand(
                request.name(),
                request.group(),
                request.jobDefinitionId(),
                trigger,
                request.timezoneId(),
                request.priority(),
                request.description());
    }

    /** Các field payload của trigger là optional trong DTO — thiếu thì trả 400 thay vì NPE/500. */
    private static <T> T require(T value, String field, String triggerType) {
        if (value == null) {
            throw new IllegalArgumentException(field + " là bắt buộc với triggerType " + triggerType);
        }
        return value;
    }
}
