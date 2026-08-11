package com.corebanking.systemreportjob.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.domain.model.TriggerState;
import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class QuartzSchedulerGatewayAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    QuartzSchedulerGatewayAdapter adapter;

    @MockitoBean
    ExecuteScheduledJobUseCase executeScheduledJobUseCase;

    private ScheduledTask sample() {
        return new ScheduledTask(
                UUID.randomUUID(),
                "fast-task",
                "test",
                UUID.randomUUID(),
                new TriggerDefinition.Simple(1, 0),
                "UTC",
                1,
                null);
    }

    @Test
    void scheduledTaskFiresAndInvokesUseCase() {
        ScheduledTask task = sample();

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> verify(executeScheduledJobUseCase).execute(task.id()));
    }

    @Test
    void pauseThenResumeChangesTriggerState() {
        ScheduledTask task = sample();
        adapter.scheduleTask(task);

        adapter.pauseTask(task.id());
        await().atMost(Duration.ofSeconds(5)).until(() -> adapter.getTriggerState(task.id()) == TriggerState.PAUSED);

        adapter.resumeTask(task.id());
        await().atMost(Duration.ofSeconds(5)).until(() -> adapter.getTriggerState(task.id()) != TriggerState.PAUSED);
    }

    @Test
    void unscheduleRemovesTheJob() {
        ScheduledTask task = sample();
        adapter.scheduleTask(task);

        adapter.unscheduleTask(task.id());

        assertThat(adapter.getTriggerState(task.id())).isEqualTo(TriggerState.NONE);
    }
}
