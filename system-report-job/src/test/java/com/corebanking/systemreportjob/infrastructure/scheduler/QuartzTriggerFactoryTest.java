package com.corebanking.systemreportjob.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronTrigger;
import org.quartz.DailyTimeIntervalTrigger;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;

class QuartzTriggerFactoryTest {

    private final QuartzTriggerFactory factory = new QuartzTriggerFactory();

    private ScheduledTask taskWith(TriggerDefinition trigger) {
        return taskWith(trigger, "UTC");
    }

    private ScheduledTask taskWith(TriggerDefinition trigger, String timezoneId) {
        return new ScheduledTask(UUID.randomUUID(), "t", "g", UUID.randomUUID(), trigger, timezoneId, 5, null);
    }

    @Test
    void buildsCronTriggerWithUtcWhenTimezoneIsNull() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.Cron("0 0 1 * * ?"), null));

        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getTimeZone()).isEqualTo(TimeZone.getTimeZone("UTC"));
    }

    @Test
    void buildsCronTrigger() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.Cron("0 0 1 * * ?")));

        assertThat(trigger).isInstanceOf(CronTrigger.class);
        assertThat(((CronTrigger) trigger).getCronExpression()).isEqualTo("0 0 1 * * ?");
    }

    @Test
    void buildsSimpleTrigger() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.Simple(60, 3)));

        assertThat(trigger).isInstanceOf(SimpleTrigger.class);
        SimpleTrigger simple = (SimpleTrigger) trigger;
        assertThat(simple.getRepeatInterval()).isEqualTo(60_000L);
        assertThat(simple.getRepeatCount()).isEqualTo(3);
    }

    @Test
    void buildsCalendarIntervalTrigger() {
        Trigger trigger = factory.build(taskWith(new TriggerDefinition.CalendarInterval(2)));

        assertThat(trigger).isInstanceOf(CalendarIntervalTrigger.class);
        assertThat(((CalendarIntervalTrigger) trigger).getRepeatInterval()).isEqualTo(2);
    }

    @Test
    void buildsDailyTimeIntervalTrigger() {
        Trigger trigger = factory.build(
                taskWith(new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0), 15)));

        assertThat(trigger).isInstanceOf(DailyTimeIntervalTrigger.class);
        assertThat(((DailyTimeIntervalTrigger) trigger).getRepeatInterval()).isEqualTo(15);
    }
}
