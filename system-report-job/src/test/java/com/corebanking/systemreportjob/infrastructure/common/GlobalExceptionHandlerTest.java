package com.corebanking.systemreportjob.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.exception.TaskNotFoundException;

class GlobalExceptionHandlerTest {

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    GlobalExceptionHandlerTest() {
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messageSource);

    @Test
    void mapsTaskNotFoundTo404WithInterpolatedMessage() {
        UUID taskId = UUID.randomUUID();

        ResponseEntity<ApiResponse<Object>> response =
                handler.handleBusinessException(new TaskNotFoundException(taskId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).contains(taskId.toString());
    }

    @Test
    void mapsJobDefinitionNotFoundTo404() {
        ResponseEntity<ApiResponse<Object>> response =
                handler.handleBusinessException(new JobDefinitionNotFoundException(UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
