package com.system.reportjob.usecase.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.system.reportjob.usecase.ports.in.HolidayQueryUseCase;
import com.system.reportjob.usecase.ports.out.HolidayRepositoryPort;

@Service
public class HolidayQueryService implements HolidayQueryUseCase {

    private final HolidayRepositoryPort repositoryPort;

    public HolidayQueryService(HolidayRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public List<LocalDate> findAllHolidayDates() {
        return repositoryPort.findAllHolidayDates();
    }

    @Override
    public LocalDate getNextWorkingDay(LocalDate startDate, String countryCode, String branchId) {
        return repositoryPort.getNextWorkingDay(startDate, countryCode, branchId);
    }
}
