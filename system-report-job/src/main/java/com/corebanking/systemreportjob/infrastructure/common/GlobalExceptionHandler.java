package com.corebanking.systemreportjob.infrastructure.common;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.corebanking.systemreportjob.domain.exception.BusinessException;
import com.corebanking.systemreportjob.domain.exception.ErrorCode;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    /**
     * Đầu vào không hợp lệ mà Bean Validation không bắt được (ví dụ: validation trong domain record,
     * triggerType không hỗ trợ) — trả 400 thay vì 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgumentException(IllegalArgumentException exception) {
        log.warn("Yêu cầu không hợp lệ: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), exception.getMessage()));
    }

    /**
     * Fallback cuối cùng: mọi exception còn lại vẫn trả về body dạng {@link ApiResponse} thay vì
     * error body mặc định của Spring.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpectedException(Exception exception) {
        HttpStatus status = statusFor(exception);
        if (status.is5xxServerError()) {
            log.error("Lỗi không mong đợi: {}", exception.getMessage(), exception);
        } else {
            log.warn("Request bị từ chối ({}): {}", status.value(), exception.getMessage());
        }
        return ResponseEntity.status(status).body(ApiResponse.error(status.value(), exception.getMessage()));
    }

    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case TASK_NOT_FOUND, JOB_DEFINITION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case JOB_DEFINITION_IN_USE, TASK_NOT_SCHEDULED -> HttpStatus.CONFLICT;
            case CRON_INVALID, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * Giữ nguyên status của các exception chuẩn Spring MVC (405, 415, 404 static resource...) —
     * nếu không, fallback sẽ biến chúng thành 500.
     */
    private HttpStatus statusFor(Exception exception) {
        if (exception instanceof ErrorResponse errorResponse) {
            return HttpStatus.valueOf(errorResponse.getStatusCode().value());
        }
        if (exception instanceof HttpMessageConversionException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveMessage(ErrorCode errorCode, Object[] args) {
        try {
            return messageSource.getMessage(errorCode.getMessageKey(), args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return errorCode.name();
        }
    }
}
