package com.system.reportjob.infrastructure.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import com.system.reportjob.domain.exception.JobDefinitionInUseException;
import com.system.reportjob.domain.exception.JobDefinitionNotFoundException;
import com.system.reportjob.domain.exception.TaskNotFoundException;

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

    @Test
    void mapsJobDefinitionInUseTo409() {
        UUID id = UUID.randomUUID();

        ResponseEntity<ApiResponse<Object>> response =
                handler.handleBusinessException(new JobDefinitionInUseException(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message()).contains(id.toString());
    }

    @Test
    void mapsIllegalArgumentExceptionTo400WithApiResponseBody() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleIllegalArgumentException(
                new IllegalArgumentException("intervalInSeconds phải lớn hơn 0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("intervalInSeconds phải lớn hơn 0");
    }

    @Test
    void fallbackHandlerMapsUnexpectedExceptionTo500WithApiResponseBody() {
        ResponseEntity<ApiResponse<Object>> response = handler.handleUnexpectedException(new Exception("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("boom");
    }

    @Test
    void fallbackHandlerKeepsStatusOfSpringMvcExceptions() {
        ResponseEntity<ApiResponse<Object>> response =
                handler.handleUnexpectedException(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().status()).isEqualTo(405);
    }
}
