package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import org.springframework.core.retry.RetryPolicy;

public class CustomPayrollRetryPolicy implements RetryPolicy {

    private int currentRetryCount =
            0; // Lưu ý: Nếu lưu biến này tại đây, Policy phải là PROTOTYPE (mỗi dòng dữ liệu/chunk dùng 1 instance mới)
    // để tránh xung đột luồng

    @Override
    public boolean shouldRetry(Throwable throwable) {
        currentRetryCount++;

        // Chỉ cho phép retry tối đa 3 lần VÀ lỗi phải là lỗi kết nối mạng
        if (currentRetryCount <= 3 && throwable instanceof RemoteAccessException) {
            return true; // Tiếp tục thử lại
        }
        return false; // Thất bại luôn, không thử nữa
    }
}
