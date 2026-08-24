package com.system.reportjob.infrastructure.scheduler;

import java.util.Date;
import java.util.TimeZone;

import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CronScheduleBuilder;
import org.quartz.DailyTimeIntervalScheduleBuilder;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TimeOfDay;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.stereotype.Component;

import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TriggerDefinition;

@Component
public class QuartzTriggerFactory {

    private static final String DEFAULT_TIMEZONE_ID = "UTC";

    public Trigger build(ScheduledTask task) {
        TriggerBuilder<Trigger> builder = TriggerBuilder.newTrigger()
                .withIdentity(QuartzIdentifiers.triggerKey(task.id()))
                .forJob(QuartzIdentifiers.jobKey(task.id()))
                .startAt(new Date());
        if (task.priority() != null) {
            builder = builder.withPriority(task.priority());
        }
        if (task.calendarName() != null && !task.calendarName().isBlank()) {
            builder = builder.modifiedByCalendar(task.calendarName());
        }

        return switch (task.trigger()) {
            case TriggerDefinition.Cron c -> builder.withSchedule(CronScheduleBuilder.cronSchedule(c.cronExpression())
                            .inTimeZone(TimeZone.getTimeZone(
                                    task.timezoneId() != null ? task.timezoneId() : DEFAULT_TIMEZONE_ID))
                            .withMisfireHandlingInstructionFireAndProceed())
                    .build();
            case TriggerDefinition.Simple s -> builder.withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInSeconds(s.intervalInSeconds())
                            .withRepeatCount(s.repeatCount()))
                    .build();
            case TriggerDefinition.CalendarInterval ci -> builder.withSchedule(
                            CalendarIntervalScheduleBuilder.calendarIntervalSchedule()
                                    .withIntervalInDays(ci.intervalInDays()))
                    .build();
            case TriggerDefinition.DailyTimeInterval d -> builder.withSchedule(
                            DailyTimeIntervalScheduleBuilder.dailyTimeIntervalSchedule()
                                    .startingDailyAt(new TimeOfDay(
                                            d.startingDailyAt().getHour(),
                                            d.startingDailyAt().getMinute()))
                                    .endingDailyAt(new TimeOfDay(
                                            d.endingDailyAt().getHour(),
                                            d.endingDailyAt().getMinute()))
                                    .withIntervalInMinutes(d.intervalInMinutes()))
                    .build();
        };
    }
}
