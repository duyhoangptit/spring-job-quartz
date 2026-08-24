package com.system.reportjob.infrastructure.jobactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.system.reportjob.domain.exception.BusinessException;
import com.system.reportjob.domain.model.JobDefinition;

class JobActionRegistryTest {

    @Test
    void dispatchesToMatchingAction() {
        AtomicReference<JobDefinition> executed = new AtomicReference<>();
        JobAction echoAction = new JobAction() {
            @Override
            public boolean matches(String jobType) {
                return "ECHO".equals(jobType);
            }

            @Override
            public void execute(JobDefinition definition) {
                executed.set(definition);
            }
        };
        JobActionRegistry registry = new JobActionRegistry(List.of(echoAction));
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "ECHO", "{}", null);

        registry.execute(definition);

        assertThat(executed.get()).isEqualTo(definition);
    }

    @Test
    void throwsWhenNoActionMatches() {
        JobActionRegistry registry = new JobActionRegistry(List.of());
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "UNKNOWN", "{}", null);

        assertThatThrownBy(() -> registry.execute(definition)).isInstanceOf(BusinessException.class);
    }
}
