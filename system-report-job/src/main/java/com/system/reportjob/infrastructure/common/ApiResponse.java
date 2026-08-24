package com.system.reportjob.infrastructure.common;

public record ApiResponse<T>(boolean success, int status, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, 200, null, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, 200, null, null);
    }

    public static <T> ApiResponse<T> error(int status, String message) {
        return new ApiResponse<>(false, status, message, null);
    }
}
