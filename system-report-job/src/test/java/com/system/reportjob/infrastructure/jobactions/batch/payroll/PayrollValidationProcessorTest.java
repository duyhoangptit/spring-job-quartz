package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PayrollValidationProcessorTest {

    private final PayrollValidationProcessor processor = new PayrollValidationProcessor();

    @Test
    void mapsAValidRecordThrough() throws Exception {
        PayrollCsvRecord input =
                new PayrollCsvRecord("FPT000001", "9000000001234", "Nguyen Van A", new BigDecimal("15000000"));

        PayrollDisbursementRecord result = processor.process(input);

        assertThat(result.employeeId()).isEqualTo("FPT000001");
        assertThat(result.accountNumber()).isEqualTo("9000000001234");
        assertThat(result.fullName()).isEqualTo("Nguyen Van A");
        assertThat(result.amount()).isEqualByComparingTo("15000000");
    }

    @Test
    void rejectsAMalformedAccountNumber() {
        PayrollCsvRecord input =
                new PayrollCsvRecord("FPT000002", "BAD-ACCOUNT", "Nguyen Van B", new BigDecimal("15000000"));

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(PayrollValidationException.class)
                .hasMessageContaining("FPT000002");
    }

    @Test
    void rejectsANonPositiveSalary() {
        PayrollCsvRecord input = new PayrollCsvRecord("FPT000003", "9000000001234", "Nguyen Van C", BigDecimal.ZERO);

        assertThatThrownBy(() -> processor.process(input))
                .isInstanceOf(PayrollValidationException.class)
                .hasMessageContaining("FPT000003");
    }

    @Test
    void rejectsANullSalary() {
        PayrollCsvRecord input = new PayrollCsvRecord("FPT000004", "9000000001234", "Nguyen Van D", null);

        assertThatThrownBy(() -> processor.process(input)).isInstanceOf(PayrollValidationException.class);
    }
}
