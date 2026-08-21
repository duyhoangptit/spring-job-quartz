package com.corebanking.systemreportjob.domain.model;

/**
 * Loại trigger được hỗ trợ, khớp 1-1 với các implementation của {@link TriggerDefinition}. Thêm
 * một loại trigger mới bắt đầu từ việc thêm hằng số vào đây rồi thêm record tương ứng vào
 * {@link TriggerDefinition} — trình biên dịch sẽ báo lỗi ở mọi switch chưa xử lý case mới.
 */
public enum TriggerType {
    CRON,
    SIMPLE,
    CALENDAR_INTERVAL,
    DAILY_TIME_INTERVAL
}
