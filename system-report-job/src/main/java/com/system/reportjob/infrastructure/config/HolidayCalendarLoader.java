package com.system.reportjob.infrastructure.config;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.calendar.AnnualCalendar;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.system.reportjob.usecase.ports.in.HolidayQueryUseCase;

@Component
public class HolidayCalendarLoader implements CommandLineRunner {

    private final Scheduler scheduler;
    private final HolidayQueryUseCase holidayQueryUseCase;

    public HolidayCalendarLoader(Scheduler scheduler, HolidayQueryUseCase holidayQueryUseCase) {
        this.scheduler = scheduler;
        this.holidayQueryUseCase = holidayQueryUseCase;
    }

    @Override
    public void run(String... args) throws Exception {
        loadBankHolidaysToQuartz();
    }

    public void loadBankHolidaysToQuartz() throws SchedulerException {
        // 1. Khởi tạo một Quartz AnnualCalendar
        AnnualCalendar quartzHolidayCalendar = new AnnualCalendar();

        // 2. Lấy danh sách toàn bộ ngày lễ qua use-case (không đụng trực tiếp repository/JPA)
        List<LocalDate> holidayDates = holidayQueryUseCase.findAllHolidayDates();

        for (LocalDate localDate : holidayDates) {
            // Chuyển đổi LocalDate (Java 8) sang java.util.Calendar (Quartz yêu cầu)
            Calendar cal = new GregorianCalendar();
            cal.setTime(java.util.Date.from(
                    localDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));

            // Thêm ngày nghỉ vào danh sách loại trừ của Quartz
            quartzHolidayCalendar.setDayExcluded(cal, true);
        }

        // 3. Đăng ký Calendar này với Quartz Scheduler dưới cái tên độc nhất "bankHolidays"
        // Nếu tên này đã tồn tại, Quartz sẽ tự động ghi đè/cập nhật mới dữ liệu
        scheduler.addCalendar("bankHolidays", quartzHolidayCalendar, true, true);
    }
}
