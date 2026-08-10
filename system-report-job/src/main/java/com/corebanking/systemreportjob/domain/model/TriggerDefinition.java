package com.corebanking.systemreportjob.domain.model;

import java.time.LocalTime;

public sealed interface TriggerDefinition {
    record Cron(String cronExpression) implements TriggerDefinition {}

    record Simple(int intervalInSeconds, int repeatCount) implements TriggerDefinition {}

    record CalendarInterval(int intervalInDays) implements TriggerDefinition {}

    record DailyTimeInterval(LocalTime startingDailyAt, LocalTime endingDailyAt, int intervalInMinutes)
            implements TriggerDefinition {}
}
