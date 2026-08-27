package com.system.reportjob.infrastructure.jobactions.batch.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.system.reportjob.domain.model.JobDefinition;
import com.system.reportjob.usecase.ports.in.DecryptCompanyFileUseCase;
import com.system.reportjob.usecase.ports.in.HolidayQueryUseCase;

class PayrollJobActionTest {

    private final Job fptPayrollJob = mock(Job.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DecryptCompanyFileUseCase decryptCompanyFileUseCase = mock(DecryptCompanyFileUseCase.class);

    private static final String EXPRESSION =
            "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\",\"branchId\":\"ALL\"}";

    private PayrollJobAction newAction(JobOperator jobOperator, HolidayQueryUseCase holidayQueryUseCase) {
        return new PayrollJobAction(
                jobOperator,
                fptPayrollJob,
                holidayQueryUseCase,
                objectMapper,
                decryptCompanyFileUseCase,
                new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()),
                Duration.ofSeconds(30));
    }

    @Test
    void matchesOnlyBankSalaryPayrollJobType() {
        PayrollJobAction action = newAction(mock(JobOperator.class), mock(HolidayQueryUseCase.class));

        assertThat(action.matches("BANK_SALARY_PAYROLL")).isTrue();
        assertThat(action.matches("HTTP_CALL")).isFalse();
    }

    @Test
    void skipsLaunchingTheBatchJobWhenTodayIsNotTheTargetPayDate() throws Exception {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any()))
                .thenReturn(LocalDate.now().plusDays(1));
        JobOperator jobOperator = mock(JobOperator.class);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", EXPRESSION, null);

        action.execute(definition);

        verify(jobOperator, never()).start(any(Job.class), any(JobParameters.class));
    }

    @Test
    void launchesTheBatchJobWhenTodayIsTheTargetPayDate() throws Exception {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution completed = mock(JobExecution.class);
        when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(completed.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobOperator.start(eq(fptPayrollJob), any(JobParameters.class))).thenReturn(completed);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", EXPRESSION, null);

        action.execute(definition);

        verify(jobOperator).start(eq(fptPayrollJob), any(JobParameters.class));
    }

    @Test
    void throwsWhenTheBatchJobDoesNotComplete() throws Exception {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution failed = mock(JobExecution.class);
        when(failed.getStatus()).thenReturn(BatchStatus.FAILED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(failed);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", EXPRESSION, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void usesPayDayOfMonthFromExpressionWhenPresent() throws Exception {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        ArgumentCaptor<LocalDate> startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        when(holidayQueryUseCase.getNextWorkingDay(startCaptor.capture(), any(), any()))
                .thenReturn(
                        LocalDate.now().plusDays(1)); // không phải hôm nay -> job sẽ không chạy, chỉ cần bắt được đúng
        // start date truyền vào
        JobOperator jobOperator = mock(JobOperator.class);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        String expression =
                "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\",\"branchId\":\"ALL\",\"payDayOfMonth\":25}";
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", expression, null);

        action.execute(definition);

        YearMonth thisMonth = YearMonth.now();
        assertThat(startCaptor.getValue())
                .isEqualTo(
                        thisMonth.atDay(Math.min(25, thisMonth.lengthOfMonth())).minusDays(1));
    }

    @Test
    void decryptsTheCsvFileFirstWhenPgpEncryptedIsTrue() throws Exception {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution completed = mock(JobExecution.class);
        when(completed.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(completed.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobOperator.start(eq(fptPayrollJob), any(JobParameters.class))).thenReturn(completed);
        Path decryptedFile = Files.createTempFile("payroll-decrypted", ".csv");
        when(decryptCompanyFileUseCase.decryptFile(eq("FPT_SOFTWARE"), any())).thenReturn(decryptedFile);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        String expression = "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\","
                + "\"branchId\":\"ALL\",\"pgpEncrypted\":true}";
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", expression, null);

        action.execute(definition);

        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobOperator).start(eq(fptPayrollJob), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().getString("csvFilePath")).isEqualTo(decryptedFile.toString());
        assertThat(Files.exists(decryptedFile)).isFalse(); // đã bị xoá trong finally sau khi job chạy xong
    }

    @Test
    void deletesTheDecryptedFileEvenWhenTheBatchJobFails() throws Exception {
        HolidayQueryUseCase holidayQueryUseCase = mock(HolidayQueryUseCase.class);
        when(holidayQueryUseCase.getNextWorkingDay(any(), any(), any())).thenReturn(LocalDate.now());
        JobOperator jobOperator = mock(JobOperator.class);
        JobExecution failed = mock(JobExecution.class);
        when(failed.getStatus()).thenReturn(BatchStatus.FAILED);
        when(jobOperator.start(any(Job.class), any(JobParameters.class))).thenReturn(failed);
        Path decryptedFile = Files.createTempFile("payroll-decrypted", ".csv");
        when(decryptCompanyFileUseCase.decryptFile(eq("FPT_SOFTWARE"), any())).thenReturn(decryptedFile);
        PayrollJobAction action = newAction(jobOperator, holidayQueryUseCase);
        String expression = "{\"companyCode\":\"FPT_SOFTWARE\",\"csvDirectory\":\"/tmp\",\"countryCode\":\"VN\","
                + "\"branchId\":\"ALL\",\"pgpEncrypted\":true}";
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "BANK_SALARY_PAYROLL", expression, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.exists(decryptedFile)).isFalse();
    }
}
