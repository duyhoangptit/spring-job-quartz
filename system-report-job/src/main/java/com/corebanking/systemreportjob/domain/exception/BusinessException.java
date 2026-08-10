package com.corebanking.systemreportjob.domain.exception;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object[] messageArgs;

    public BusinessException(ErrorCode errorCode, Object... messageArgs) {
        super(errorCode.name());
        this.errorCode = errorCode;
        this.messageArgs = messageArgs;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object[] getMessageArgs() {
        return messageArgs;
    }
}
