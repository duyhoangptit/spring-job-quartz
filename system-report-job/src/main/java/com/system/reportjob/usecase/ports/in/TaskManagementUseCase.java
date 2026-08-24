package com.system.reportjob.usecase.ports.in;

import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TaskDetail;

public interface TaskManagementUseCase {
    ScheduledTask create(CreateTaskCommand command);

    void start(UUID taskId);

    void pause(UUID taskId);

    void resume(UUID taskId);

    /**
     * Kích hoạt job của task này chạy ngay lập tức (một lần), không cần đợi lịch cron/interval —
     * hữu ích để test. Task phải đã được {@link #start} trước đó.
     */
    void triggerNow(UUID taskId);

    void delete(UUID taskId);

    void startAll();

    PageResult<ScheduledTask> search(String keyword, Pageable pageable);

    TaskDetail getDetail(UUID taskId);
}
