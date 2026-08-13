package com.corebanking.systemreportjob.usecase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.corebanking.systemreportjob.domain.exception.ErrorCode;
import com.corebanking.systemreportjob.domain.exception.JobDefinitionInUseException;
import com.corebanking.systemreportjob.domain.exception.JobDefinitionNotFoundException;
import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.usecase.ports.in.CreateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.in.UpdateJobDefinitionCommand;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;

class JobDefinitionServiceTest {

    private JobDefinitionRepositoryPort repositoryPort;
    private TaskRepositoryPort taskRepositoryPort;
    private JobDefinitionService service;

    @BeforeEach
    void setUp() {
        repositoryPort = mock(JobDefinitionRepositoryPort.class);
        taskRepositoryPort = mock(TaskRepositoryPort.class);
        service = new JobDefinitionService(repositoryPort, taskRepositoryPort);
    }

    @Test
    void createSavesNewJobDefinition() {
        when(repositoryPort.save(any(JobDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        JobDefinition result = service.create(new CreateJobDefinitionCommand("HTTP_CALL", "{}", "desc"));

        assertThat(result.jobType()).isEqualTo("HTTP_CALL");
        verify(repositoryPort).save(any(JobDefinition.class));
    }

    @Test
    void updateActuallyUpdatesTheRecord() {
        UUID id = UUID.randomUUID();
        JobDefinition existing = new JobDefinition(id, "HTTP_CALL", "{}", "old");
        when(repositoryPort.findById(id)).thenReturn(Optional.of(existing));
        when(repositoryPort.save(any(JobDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        JobDefinition result = service.update(id, new UpdateJobDefinitionCommand("ECHO", "{\"msg\":\"hi\"}", "new"));

        assertThat(result.jobType()).isEqualTo("ECHO");
        assertThat(result.description()).isEqualTo("new");
        verify(repositoryPort, never()).delete(any());
    }

    @Test
    void updateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repositoryPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, new UpdateJobDefinitionCommand("ECHO", "{}", null)))
                .isInstanceOf(JobDefinitionNotFoundException.class);
    }

    @Test
    void deleteDelegatesToRepositoryPort() {
        UUID id = UUID.randomUUID();
        when(taskRepositoryPort.existsByJobDefinitionId(id)).thenReturn(false);

        service.delete(id);

        verify(repositoryPort).delete(id);
    }

    @Test
    void deleteRejectedWhenStillReferencedByATask() {
        UUID id = UUID.randomUUID();
        when(taskRepositoryPort.existsByJobDefinitionId(id)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(JobDefinitionInUseException.class)
                .extracting(e -> ((JobDefinitionInUseException) e).getErrorCode())
                .isEqualTo(ErrorCode.JOB_DEFINITION_IN_USE);

        verify(repositoryPort, never()).delete(any());
    }
}
