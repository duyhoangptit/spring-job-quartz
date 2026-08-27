package com.system.reportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import com.system.reportjob.presentation.controller.TaskController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.system.reportjob.domain.exception.TaskNotFoundException;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TriggerDefinition;
import com.system.reportjob.infrastructure.common.GlobalExceptionHandler;
import com.system.reportjob.usecase.ports.in.TaskManagementUseCase;

@WebMvcTest(TaskController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
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

    @Test
    void createReturns400WhenSimpleTriggerMissesInterval() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
								{"name":"broken","group":"reports","jobDefinitionId":"%s","triggerType":"SIMPLE"}
								"""
                                        .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("intervalInSeconds")));
    }

    @Test
    void createReturns400WhenCronTriggerMissesExpression() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
								{"name":"broken","group":"reports","jobDefinitionId":"%s","triggerType":"CRON"}
								"""
                                        .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cronExpression")));
    }

    @Test
    void createReturns400WhenTriggerTypeUnknown() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
								{"name":"broken","group":"reports","jobDefinitionId":"%s","triggerType":"NOPE"}
								"""
                                        .formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NOPE")));
    }
}
