package com.corebanking.systemreportjob.usecase.ports.in;

import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TaskDetail;

public interface TaskManagementUseCase {
    ScheduledTask create(CreateTaskCommand command);

    void start(UUID taskId);

    void pause(UUID taskId);

    void resume(UUID taskId);

    void delete(UUID taskId);

    void startAll();

    PageResult<ScheduledTask> search(String keyword, Pageable pageable);

    TaskDetail getDetail(UUID taskId);
}
