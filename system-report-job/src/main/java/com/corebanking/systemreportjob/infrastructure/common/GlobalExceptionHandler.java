package com.corebanking.systemreportjob.infrastructure.common;

import java.util.Objects;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.corebanking.systemreportjob.domain.exception.BusinessException;
import com.corebanking.systemreportjob.domain.exception.ErrorCode;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusinessException(BusinessException exception) {
        HttpStatus status = statusFor(exception.getErrorCode());
        String message = resolveMessage(exception.getErrorCode(), exception.getMessageArgs());
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = Objects.requireNonNull(exception.getFieldError()).getDefaultMessage();
        return ResponseEntity.badRequest().body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message));
    }

    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case TASK_NOT_FOUND, JOB_DEFINITION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CRON_INVALID, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        };
    }

    private String resolveMessage(ErrorCode errorCode, Object[] args) {
        try {
            return messageSource.getMessage(errorCode.getMessageKey(), args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return errorCode.name();
        }
    }
}
