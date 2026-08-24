package com.system.reportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.system.reportjob.usecase.ports.out.HolidayRepositoryPort;

class HolidayQueryServiceTest {

    @Test
    void findAllHolidayDatesDelegatesToRepositoryPort() {
        HolidayRepositoryPort repositoryPort = mock(HolidayRepositoryPort.class);
        List<LocalDate> expected = List.of(LocalDate.of(2026, 9, 2));
        when(repositoryPort.findAllHolidayDates()).thenReturn(expected);
        HolidayQueryService service = new HolidayQueryService(repositoryPort);

        List<LocalDate> result = service.findAllHolidayDates();

        assertThat(result).isSameAs(expected);
    }

    @Test
    void getNextWorkingDayDelegatesToRepositoryPort() {
        HolidayRepositoryPort repositoryPort = mock(HolidayRepositoryPort.class);
        LocalDate startDate = LocalDate.of(2026, 8, 28);
        LocalDate expected = LocalDate.of(2026, 9, 3);
        when(repositoryPort.getNextWorkingDay(startDate, "VN", "ALL")).thenReturn(expected);
        HolidayQueryService service = new HolidayQueryService(repositoryPort);

        LocalDate result = service.getNextWorkingDay(startDate, "VN", "ALL");

        assertThat(result).isEqualTo(expected);
    }
}
