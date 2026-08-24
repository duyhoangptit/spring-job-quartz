package com.system.reportjob.infrastructure.jobactions.batch.payroll;

/** Ném ra khi 1 dòng CSV không hợp lệ (số TK sai định dạng, lương <= 0) — kích hoạt skip ở disburseStep. */
public class PayrollValidationException extends RuntimeException {
    public PayrollValidationException(String message) {
        super(message);
    }
}
