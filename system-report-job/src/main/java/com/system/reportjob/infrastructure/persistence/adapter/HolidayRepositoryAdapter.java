package com.system.reportjob.infrastructure.persistence.adapter;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import com.system.reportjob.infrastructure.persistence.repository.HolidayRepository;
import com.system.reportjob.usecase.ports.out.HolidayRepositoryPort;

@Component
public class HolidayRepositoryAdapter implements HolidayRepositoryPort {

    private final HolidayRepository jpaRepository;

    public HolidayRepositoryAdapter(HolidayRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<LocalDate> findAllHolidayDates() {
        return jpaRepository.findAllHolidayDates();
    }

    @Override
    public LocalDate getNextWorkingDay(LocalDate startDate, String countryCode, String branchId) {
        return jpaRepository.getNextWorkingDay(startDate, countryCode, branchId);
    }
}
