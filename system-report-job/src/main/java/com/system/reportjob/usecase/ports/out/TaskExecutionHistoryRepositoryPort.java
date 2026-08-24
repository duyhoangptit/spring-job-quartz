package com.system.reportjob.usecase.ports.out;

import org.springframework.data.domain.Pageable;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.TaskExecutionRecord;

public interface TaskExecutionHistoryRepositoryPort {
    TaskExecutionRecord save(TaskExecutionRecord record);

    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}
