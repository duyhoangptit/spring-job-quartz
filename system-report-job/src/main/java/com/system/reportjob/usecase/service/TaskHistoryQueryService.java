package com.system.reportjob.usecase.service;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.TaskExecutionRecord;
import com.system.reportjob.usecase.ports.in.TaskHistoryQueryUseCase;
import com.system.reportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;

@Service
public class TaskHistoryQueryService implements TaskHistoryQueryUseCase {

    private final TaskExecutionHistoryRepositoryPort repositoryPort;

    public TaskHistoryQueryService(TaskExecutionHistoryRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public PageResult<TaskExecutionRecord> search(String taskName, Pageable pageable) {
        return repositoryPort.search(taskName, pageable);
    }
}
