package com.corebanking.systemreportjob.infrastructure.jobactions.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.corebanking.systemreportjob.domain.model.JobDefinition;

class EchoInProcessJobActionTest {

    private final EchoInProcessJobAction action = new EchoInProcessJobAction();

    @Test
    void matchesOnlyEchoJobType() {
        assertThat(action.matches("ECHO")).isTrue();
        assertThat(action.matches("HTTP_CALL")).isFalse();
    }

    @Test
    void executeDoesNotThrow() {
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "ECHO", "{\"msg\":\"hi\"}", null);

        assertThatCode(() -> action.execute(definition)).doesNotThrowAnyException();
    }
}
