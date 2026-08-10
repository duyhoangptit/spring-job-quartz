package com.corebanking.systemreportjob.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void carriesErrorCodeAndArgs() {
        UUID id = UUID.randomUUID();
        TaskNotFoundException ex = new TaskNotFoundException(id);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_NOT_FOUND);
        assertThat(ex.getMessageArgs()).containsExactly(id);
    }

    @Test
    void jobDefinitionNotFoundCarriesCode() {
        UUID id = UUID.randomUUID();
        JobDefinitionNotFoundException ex = new JobDefinitionNotFoundException(id);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.JOB_DEFINITION_NOT_FOUND);
    }
}
