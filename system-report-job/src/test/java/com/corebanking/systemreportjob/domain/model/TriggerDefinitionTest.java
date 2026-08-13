package com.corebanking.systemreportjob.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class TriggerDefinitionTest {

    @Test
    void cronRejectsNullExpression() {
        assertThatThrownBy(() -> new TriggerDefinition.Cron(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cronExpression");
    }

    @Test
    void cronRejectsBlankExpression() {
        assertThatThrownBy(() -> new TriggerDefinition.Cron("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void simpleRejectsNonPositiveInterval() {
        assertThatThrownBy(() -> new TriggerDefinition.Simple(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalInSeconds");
        assertThatThrownBy(() -> new TriggerDefinition.Simple(-5, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void calendarIntervalRejectsNonPositiveInterval() {
        assertThatThrownBy(() -> new TriggerDefinition.CalendarInterval(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalInDays");
    }

    @Test
    void dailyTimeIntervalRejectsNullBoundaries() {
        assertThatThrownBy(() -> new TriggerDefinition.DailyTimeInterval(null, LocalTime.of(17, 0), 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startingDailyAt");
        assertThatThrownBy(() -> new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), null, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endingDailyAt");
    }

    @Test
    void dailyTimeIntervalRejectsNonPositiveInterval() {
        assertThatThrownBy(() -> new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalInMinutes");
    }

    @Test
    void acceptsValidDefinitions() {
        assertThatCode(() -> {
                    new TriggerDefinition.Cron("0 0 1 * * ?");
                    new TriggerDefinition.Simple(60, 0);
                    new TriggerDefinition.CalendarInterval(1);
                    new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);
                })
                .doesNotThrowAnyException();
    }
}
