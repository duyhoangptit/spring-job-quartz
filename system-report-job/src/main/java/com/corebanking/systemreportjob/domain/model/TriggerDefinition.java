package com.corebanking.systemreportjob.domain.model;

import java.time.LocalTime;

public sealed interface TriggerDefinition {
    record Cron(String cronExpression) implements TriggerDefinition {
        public Cron {
            if (cronExpression == null || cronExpression.isBlank()) {
                throw new IllegalArgumentException("cronExpression là bắt buộc với trigger kiểu CRON");
            }
        }
    }

    record Simple(int intervalInSeconds, int repeatCount) implements TriggerDefinition {
        public Simple {
            if (intervalInSeconds <= 0) {
                throw new IllegalArgumentException("intervalInSeconds phải lớn hơn 0 với trigger kiểu SIMPLE");
            }
        }
    }

    record CalendarInterval(int intervalInDays) implements TriggerDefinition {
        public CalendarInterval {
            if (intervalInDays <= 0) {
                throw new IllegalArgumentException("intervalInDays phải lớn hơn 0 với trigger kiểu CALENDAR_INTERVAL");
            }
        }
    }

    record DailyTimeInterval(LocalTime startingDailyAt, LocalTime endingDailyAt, int intervalInMinutes)
            implements TriggerDefinition {
        public DailyTimeInterval {
            if (startingDailyAt == null) {
                throw new IllegalArgumentException("startingDailyAt là bắt buộc với trigger kiểu DAILY_TIME_INTERVAL");
            }
            if (endingDailyAt == null) {
                throw new IllegalArgumentException("endingDailyAt là bắt buộc với trigger kiểu DAILY_TIME_INTERVAL");
            }
            if (intervalInMinutes <= 0) {
                throw new IllegalArgumentException(
                        "intervalInMinutes phải lớn hơn 0 với trigger kiểu DAILY_TIME_INTERVAL");
            }
        }
    }
}
