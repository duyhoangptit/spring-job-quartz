package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;

/** 1 dòng thô đọc từ file CSV lương FPT Software — employeeId,accountNumber,fullName,salaryAmount. */
public record PayrollCsvRecord(String employeeId, String accountNumber, String fullName, BigDecimal salaryAmount) {}
