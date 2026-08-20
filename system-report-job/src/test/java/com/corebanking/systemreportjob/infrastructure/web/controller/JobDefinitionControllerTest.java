package com.corebanking.systemreportjob.infrastructure.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionInUseException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.infrastructure.common.GlobalExceptionHandler;
import com.corebanking.systemreportjob.usecase.ports.in.JobDefinitionUseCase;

@WebMvcTest(JobDefinitionController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class JobDefinitionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JobDefinitionUseCase jobDefinitionUseCase;

    @Test
    void putCallsUpdateNotDelete() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobDefinitionUseCase.update(eq(id), any())).thenReturn(new JobDefinition(id, "ECHO", "{}", "updated"));

        mockMvc.perform(put("/api/job-definitions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ECHO\",\"expression\":\"{}\",\"description\":\"updated\"}"))
                .andExpect(status().isOk());

        verify(jobDefinitionUseCase).update(eq(id), any());
        verify(jobDefinitionUseCase, never()).delete(any());
    }

    @Test
    void createReturnsOk() throws Exception {
        when(jobDefinitionUseCase.create(any()))
                .thenReturn(new JobDefinition(UUID.randomUUID(), "HTTP_CALL", "{}", null));

        mockMvc.perform(post("/api/job-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"HTTP_CALL\",\"expression\":\"{}\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReturns409WhenJobDefinitionStillReferenced() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new JobDefinitionInUseException(id)).when(jobDefinitionUseCase).delete(id);

        mockMvc.perform(delete("/api/job-definitions/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(id.toString())));
    }

    @Test
    void deleteReturnsOk() throws Exception {
        mockMvc.perform(delete("/api/job-definitions/{id}", UUID.randomUUID())).andExpect(status().isOk());

        verify(jobDefinitionUseCase).delete(any());
    }
}
