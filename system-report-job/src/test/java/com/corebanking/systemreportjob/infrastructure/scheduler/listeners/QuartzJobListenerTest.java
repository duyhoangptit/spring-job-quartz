package com.corebanking.systemreportjob.infrastructure.scheduler.listeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.corebanking.systemreportjob.domain.model.PageResult;
import com.corebanking.systemreportjob.domain.model.ScheduledTask;
import com.corebanking.systemreportjob.domain.model.TaskExecutionRecord;
import com.corebanking.systemreportjob.domain.model.TriggerDefinition;
import com.corebanking.systemreportjob.infrastructure.scheduler.QuartzSchedulerGatewayAdapter;
import com.corebanking.systemreportjob.usecase.ports.in.ExecuteScheduledJobUseCase;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class QuartzJobListenerTest {

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

    @Autowired
    TaskExecutionHistoryRepositoryPort historyRepositoryPort;

    @Autowired
    JobDefinitionRepositoryPort jobDefinitionRepositoryPort;

    @Autowired
    TaskRepositoryPort taskRepositoryPort;

    @MockitoBean
    ExecuteScheduledJobUseCase executeScheduledJobUseCase;

    // task_execution_history.task_id carries a FK to tasks.id (and tasks.job_definition_id carries a
    // FK to job_definitions.id), so the row the listener writes on job completion requires both
    // parent rows to actually exist — mirroring how TaskOrchestrator.create()/start() persist a task
    // before scheduling it. Persisting only an in-memory ScheduledTask and calling scheduleTask()
    // directly (as production code never does) trips those FK constraints.
    private ScheduledTask sample(String name) {
        JobDefinition definition =
                jobDefinitionRepositoryPort.save(new JobDefinition(UUID.randomUUID(), "test-job", null, null));
        ScheduledTask task = new ScheduledTask(
                UUID.randomUUID(), name, "test", definition.id(), new TriggerDefinition.Simple(1, 0), "UTC", 1, null);
        return taskRepositoryPort.save(task);
    }

    @Test
    void recordsSuccessfulExecution() {
        ScheduledTask task = sample("listener-success");
        doNothing().when(executeScheduledJobUseCase).execute(task.id());

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            PageResult<TaskExecutionRecord> result = historyRepositoryPort.search(task.name(), PageRequest.of(0, 10));
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).exceptionMessage()).isNull();
            assertThat(result.content().get(0).startTime()).isNotNull();
            assertThat(result.content().get(0).endTime()).isNotNull();
        });
    }

    @Test
    void recordsFailedExecutionWithExceptionMessage() {
        ScheduledTask task = sample("listener-failure");
        doThrow(new RuntimeException("boom")).when(executeScheduledJobUseCase).execute(task.id());

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            PageResult<TaskExecutionRecord> result = historyRepositoryPort.search(task.name(), PageRequest.of(0, 10));
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().get(0).exceptionMessage()).contains("boom");
        });
    }

    @Test
    void setsARequestIdInMdcForTheDurationOfTheJob() {
        ScheduledTask task = sample("listener-request-id");
        AtomicReference<String> requestIdSeenDuringExecution = new AtomicReference<>();
        doAnswer(invocation -> {
                    requestIdSeenDuringExecution.set(MDC.get("requestId"));
                    return null;
                })
                .when(executeScheduledJobUseCase)
                .execute(task.id());

        adapter.scheduleTask(task);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(requestIdSeenDuringExecution.get())
                .isNotNull());
    }

    @Test
    void eachExecutionGetsItsOwnRequestId() {
        ScheduledTask taskA = sample("listener-request-id-a");
        ScheduledTask taskB = sample("listener-request-id-b");
        AtomicReference<String> requestIdA = new AtomicReference<>();
        AtomicReference<String> requestIdB = new AtomicReference<>();
        doAnswer(invocation -> {
                    requestIdA.set(MDC.get("requestId"));
                    return null;
                })
                .when(executeScheduledJobUseCase)
                .execute(taskA.id());
        doAnswer(invocation -> {
                    requestIdB.set(MDC.get("requestId"));
                    return null;
                })
                .when(executeScheduledJobUseCase)
                .execute(taskB.id());

        adapter.scheduleTask(taskA);
        adapter.scheduleTask(taskB);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(requestIdA.get()).isNotNull();
            assertThat(requestIdB.get()).isNotNull();
        });
        assertThat(requestIdA.get()).isNotEqualTo(requestIdB.get());
    }
}
