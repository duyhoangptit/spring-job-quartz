package com.corebanking.systemreportjob.usecase.ports.in;

import org.springframework.data.domain.Pageable;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;

public interface TaskHistoryQueryUseCase {
    PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable);
}
