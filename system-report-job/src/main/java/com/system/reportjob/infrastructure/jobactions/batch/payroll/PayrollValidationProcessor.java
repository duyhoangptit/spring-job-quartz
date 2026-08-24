package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Validate 1 dòng CSV lương: số tài khoản phải đúng định dạng, lương phải > 0. Ném
 * {@link PayrollValidationException} khi không hợp lệ — disburseStep (xem PayrollBatchConfig)
 * cấu hình skip trên exception này nên 1 dòng lỗi không làm rollback cả chunk.
 */
public class PayrollValidationProcessor implements ItemProcessor<PayrollCsvRecord, PayrollDisbursementRecord> {

    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^\\d{10,14}$");

    @Override
    public PayrollDisbursementRecord process(PayrollCsvRecord item) {
        if (item.accountNumber() == null
                || !ACCOUNT_NUMBER_PATTERN.matcher(item.accountNumber()).matches()) {
            throw new PayrollValidationException(
                    "Số tài khoản không hợp lệ cho nhân viên " + item.employeeId() + ": " + item.accountNumber());
        }
        if (item.salaryAmount() == null || item.salaryAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PayrollValidationException(
                    "Lương không hợp lệ cho nhân viên " + item.employeeId() + ": " + item.salaryAmount());
        }
        return new PayrollDisbursementRecord(
                item.employeeId(), item.accountNumber(), item.fullName(), item.salaryAmount());
    }
}
