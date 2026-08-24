package com.system.reportjob.domain.model;

import java.util.UUID;

public record ScheduledTask(
        UUID id,
        String name,
        String group,
        UUID jobDefinitionId,
        TriggerDefinition trigger,
        String calendarName,
        String timezoneId,
        Integer priority,
        String description) {
    public ScheduledTask {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên task không được rỗng");
        }
        if (jobDefinitionId == null) {
            throw new IllegalArgumentException("Task phải gắn với một JobDefinition");
        }
    }

    /**
     * Tương thích ngược với code hiện có chưa biết tới calendarName (Quartz Calendar đã đăng ký,
     * vd "bankHolidays" — xem HolidayCalendarLoader). Mặc định null = không gắn calendar nào,
     * đúng hành vi hiện tại.
     */
    public ScheduledTask(
            UUID id,
            String name,
            String group,
            UUID jobDefinitionId,
            TriggerDefinition trigger,
            String timezoneId,
            Integer priority,
            String description) {
        this(id, name, group, jobDefinitionId, trigger, null, timezoneId, priority, description);
    }
}
