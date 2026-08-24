package com.system.reportjob.infrastructure.persistence.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.system.reportjob.infrastructure.persistence.entity.HolidayEntity;

public interface HolidayRepository extends JpaRepository<HolidayEntity, Long> {

    /**
     * All dates that are actually off, across every country/branch. Excludes rows flagged {@code
     * is_working_day = true} (compensatory work days, e.g. a Saturday worked to make up for a
     * holiday) — those must stay open on the Quartz calendar. Used by HolidayCalendarLoader to
     * populate the shared "bankHolidays" AnnualCalendar.
     */
    @Query("select h.holidayDate from HolidayEntity h where h.workingDay = false")
    List<LocalDate> findAllHolidayDates();

    /**
     * Delegates to the {@code get_next_working_day(date, country_code, branch_id)} Postgres
     * function defined in {@code V9__create_holidays.sql} — the calendar-walking logic
     * (weekend/holiday/compensatory-day rules) lives once in the DB so this repository and any
     * ad-hoc SQL/reporting stay consistent instead of re-implementing it in Java.
     */
    @Query(value = "select get_next_working_day(:startDate, :countryCode, :branchId)", nativeQuery = true)
    LocalDate getNextWorkingDay(
            @Param("startDate") LocalDate startDate,
            @Param("countryCode") String countryCode,
            @Param("branchId") String branchId);
}
