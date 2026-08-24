package com.system.reportjob.usecase.ports.out;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepositoryPort {

    List<LocalDate> findAllHolidayDates();

    LocalDate getNextWorkingDay(LocalDate startDate, String countryCode, String branchId);
}
