package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;

/** Record đã qua validate, sẵn sàng ghi vào payroll_disbursement với status = SUCCESS. */
public record PayrollDisbursementRecord(String employeeId, String accountNumber, String fullName, BigDecimal amount) {}
