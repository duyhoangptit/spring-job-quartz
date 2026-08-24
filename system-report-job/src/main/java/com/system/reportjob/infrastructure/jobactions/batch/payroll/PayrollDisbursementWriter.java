package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ghi các record giải ngân thành công (SUCCESS) vào payroll_disbursement và trừ dần
 * gl_suspense_account. Đóng vai trò kép là SkipListener: khi processor skip 1 dòng lỗi, ghi luôn
 * dòng đó vào payroll_disbursement với status = SKIPPED — mọi dòng trong CSV đầu vào đều có đúng
 * 1 dòng kết quả, không dòng nào bị bỏ qua âm thầm.
 */
public class PayrollDisbursementWriter
        implements ItemWriter<PayrollDisbursementRecord>, SkipListener<PayrollCsvRecord, PayrollDisbursementRecord> {

    private static final Logger log = LoggerFactory.getLogger(PayrollDisbursementWriter.class);

    private final JdbcTemplate jdbcTemplate;
    private final String companyCode;
    private final LocalDate targetPayDate;
    private Long batchRunId;

    public PayrollDisbursementWriter(JdbcTemplate jdbcTemplate, String companyCode, LocalDate targetPayDate) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyCode = companyCode;
        this.targetPayDate = targetPayDate;
    }

    @Override
    public void write(Chunk<? extends PayrollDisbursementRecord> chunk) {
        BigDecimal chunkTotal = BigDecimal.ZERO;
        for (PayrollDisbursementRecord item : chunk) {
            jdbcTemplate.update(
                    "INSERT INTO payroll_disbursement "
                            + "(batch_run_id, employee_id, account_number, full_name, amount, status) "
                            + "VALUES (?, ?, ?, ?, ?, 'SUCCESS')",
                    resolveBatchRunId(),
                    item.employeeId(),
                    item.accountNumber(),
                    item.fullName(),
                    item.amount());
            chunkTotal = chunkTotal.add(item.amount());
        }
        if (chunkTotal.signum() > 0) {
            jdbcTemplate.update(
                    "UPDATE gl_suspense_account SET balance = balance - ?, updated_at = now() "
                            + "WHERE account_code = 'PAYROLL_SUSPENSE_GL'",
                    chunkTotal);
        }
    }

    @Override
    public void onSkipInProcess(PayrollCsvRecord item, Throwable t) {
        jdbcTemplate.update(
                "INSERT INTO payroll_disbursement "
                        + "(batch_run_id, employee_id, account_number, full_name, amount, status, error_reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'SKIPPED', ?)",
                resolveBatchRunId(),
                item.employeeId(),
                item.accountNumber(),
                item.fullName(),
                item.salaryAmount() == null ? BigDecimal.ZERO : item.salaryAmount(),
                t.getMessage());
        log.warn("[BANK_SALARY_PAYROLL] disburseStep - bỏ qua nhân viên {}: {}", item.employeeId(), t.getMessage());
    }

    private Long resolveBatchRunId() {
        if (batchRunId == null) {
            batchRunId = jdbcTemplate.queryForObject(
                    "SELECT id FROM payroll_batch_run WHERE company_code = ? AND target_pay_date = ?",
                    Long.class,
                    companyCode,
                    targetPayDate);
        }
        return batchRunId;
    }
}
