package com.system.reportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@link HolidayRepositoryAdapter} against the real V9__create_holidays.sql schema and
 * seed data (Flyway runs automatically against the Testcontainers Postgres) — in particular the
 * {@code get_next_working_day} native query, which a plain Mockito test can't verify.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(HolidayRepositoryAdapter.class)
class HolidayRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    HolidayRepositoryAdapter adapter;

    @Test
    void findAllHolidayDatesExcludesTheCompensatoryWorkingSaturday() {
        // Seed data (V9): 22/8 (Sat) is a compensatory *working* day for the 31/8 bridge holiday -
        // it must never show up as a day off.
        assertThat(adapter.findAllHolidayDates()).doesNotContain(LocalDate.of(2026, 8, 22));
        assertThat(adapter.findAllHolidayDates())
                .contains(
                        LocalDate.of(2026, 2, 14), // Mùng 1 Tết
                        LocalDate.of(2026, 2, 16), // Nghỉ bù Tết
                        LocalDate.of(2026, 8, 31), // Nghỉ cầu nối
                        LocalDate.of(2026, 9, 1), // Nghỉ liền kề Quốc khánh
                        LocalDate.of(2026, 9, 2)); // Quốc khánh 2/9
    }

    @Test
    void getNextWorkingDaySkipsTheFiveDayNationalHolidayBlock() {
        // 29/8 (T7) - 30/8 (CN) - 31/8 (nghỉ cầu nối) - 1/9 - 2/9 (Quốc khánh) đều nghỉ liên tục;
        // ngày làm việc tiếp theo sau 28/8 phải là 3/9.
        LocalDate nextWorkingDay = adapter.getNextWorkingDay(LocalDate.of(2026, 8, 28), "VN", "ALL");

        assertThat(nextWorkingDay).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void getNextWorkingDayReturnsTheCompensatorySaturdayItself() {
        // 22/8 là Thứ Bảy nhưng được đánh dấu đi làm bù (is_working_day = TRUE) cho ngày 31/8,
        // nên ngày làm việc tiếp theo sau 21/8 phải là chính 22/8, không bị coi là cuối tuần.
        LocalDate nextWorkingDay = adapter.getNextWorkingDay(LocalDate.of(2026, 8, 21), "VN", "ALL");

        assertThat(nextWorkingDay).isEqualTo(LocalDate.of(2026, 8, 22));
    }
}
