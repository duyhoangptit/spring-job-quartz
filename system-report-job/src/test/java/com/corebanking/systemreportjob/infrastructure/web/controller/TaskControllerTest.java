package com.corebanking.systemreportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.common.GlobalExceptionHandler;
import com.corebanking.systemreportjob.usecase.ports.in.TaskManagementUseCase;

@WebMvcTest(TaskController.class)
@Import(GlobalExceptionHandler.class)
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskManagementUseCase taskManagementUseCase;

    @Test
    void createReturnsCreatedTask() throws Exception {
        UUID jobDefinitionId = UUID.randomUUID();
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(),
                "daily-report",
                "reports",
                jobDefinitionId,
                new TriggerDefinition.Cron("0 0 1 * * ?"),
                "UTC",
                5,
                null);
        when(taskManagementUseCase.create(any())).thenReturn(task);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
								{"name":"daily-report","group":"reports","jobDefinitionId":"%s",
								"triggerType":"CRON","cronExpression":"0 0 1 * * ?"}
								"""
                                        .formatted(jobDefinitionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("daily-report"));
    }

    @Test
    void startReturnsOk() throws Exception {
        mockMvc.perform(post("/api/tasks/start/{id}", UUID.randomUUID())).andExpect(status().isOk());
    }

    @Test
    void detailReturns404WhenTaskMissing() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(taskManagementUseCase.getDetail(taskId)).thenThrow(new TaskNotFoundException(taskId));

        mockMvc.perform(get("/api/tasks/{id}", taskId)).andExpect(status().isNotFound());
    }

    @Test
    void pauseReturnsOk() throws Exception {
        mockMvc.perform(put("/api/tasks/pause/{id}", UUID.randomUUID())).andExpect(status().isOk());
    }
}
