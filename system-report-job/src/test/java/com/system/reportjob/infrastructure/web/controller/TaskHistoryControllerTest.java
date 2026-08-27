package com.system.reportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.system.reportjob.presentation.controller.TaskHistoryController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.infrastructure.common.GlobalExceptionHandler;
import com.system.reportjob.usecase.ports.in.TaskHistoryQueryUseCase;

@WebMvcTest(TaskHistoryController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class TaskHistoryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TaskHistoryQueryUseCase taskHistoryQueryUseCase;

    @Test
    void searchReturnsOk() throws Exception {
        when(taskHistoryQueryUseCase.search(any(), any())).thenReturn(new PageResult<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/task-history/search")).andExpect(status().isOk());
    }
}
