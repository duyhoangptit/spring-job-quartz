package com.corebanking.systemreportjob.usecase.ports.out;

import org.springframework.data.domain.Pageable;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;

public interface TaskExecutionHistoryRepositoryPort {
    TaskExecutionRecord save(TaskExecutionRecord record);

    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}
