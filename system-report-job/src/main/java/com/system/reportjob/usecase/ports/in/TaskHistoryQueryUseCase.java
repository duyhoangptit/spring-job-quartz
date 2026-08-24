package com.system.reportjob.usecase.ports.in;

import org.springframework.data.domain.Pageable;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.TaskExecutionRecord;

public interface TaskHistoryQueryUseCase {
    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}
