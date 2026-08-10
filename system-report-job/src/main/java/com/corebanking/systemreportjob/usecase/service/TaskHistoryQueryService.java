package com.corebanking.systemreportjob.usecase.service;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.usecase.ports.in.TaskHistoryQueryUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;

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
