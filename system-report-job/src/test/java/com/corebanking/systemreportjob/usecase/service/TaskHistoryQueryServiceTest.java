package com.corebanking.systemreportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;

class TaskHistoryQueryServiceTest {

    @Test
    void searchDelegatesToRepositoryPort() {
        TaskExecutionHistoryRepositoryPort repositoryPort = mock(TaskExecutionHistoryRepositoryPort.class);
        PageResult<TaskExecutionRecord> expected = new PageResult<>(List.of(), 0, 20, 0, 0);
        when(repositoryPort.search("daily-report", PageRequest.of(0, 20))).thenReturn(expected);
        TaskHistoryQueryService service = new TaskHistoryQueryService(repositoryPort);

        PageResult<TaskExecutionRecord> result = service.search("daily-report", PageRequest.of(0, 20));

        assertThat(result).isSameAs(expected);
    }
}
