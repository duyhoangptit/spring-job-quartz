package com.system.reportjob.usecase.ports.in;

import java.time.LocalDate;
import java.util.List;

public interface HolidayQueryUseCase {

    /**
     * All dates the bank is closed (holidays, bridge days), excluding any date flagged as a
     * compensatory working day. Used to build the Quartz "bankHolidays" AnnualCalendar.
     */
    List<LocalDate> findAllHolidayDates();

    /**
     * First working day strictly after {@code startDate} for the given country/branch, honoring
     * holidays, weekends, and compensatory working-day overrides.
     */
    LocalDate getNextWorkingDay(LocalDate startDate, String countryCode, String branchId);
}
