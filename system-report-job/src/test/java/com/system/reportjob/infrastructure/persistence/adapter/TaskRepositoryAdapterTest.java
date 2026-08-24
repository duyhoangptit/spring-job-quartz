package com.system.reportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.system.reportjob.domain.model.PageResult;
import com.system.reportjob.domain.model.ScheduledTask;
import com.system.reportjob.domain.model.TriggerDefinition;
import com.system.reportjob.infrastructure.persistence.entity.JobDefinitionEntity;
import com.system.reportjob.infrastructure.persistence.repository.JobDefinitionJpaRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(TaskRepositoryAdapter.class)
class TaskRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TaskRepositoryAdapter adapter;

    @Autowired
    JobDefinitionJpaRepository jobDefinitionJpaRepository;

    // tasks.job_definition_id carries a FK to job_definitions(id) (see V3__create_tasks.sql), so
    // every sample task must point at a JobDefinitionEntity that actually exists in the DB.
    private ScheduledTask sample(TriggerDefinition trigger) {
        JobDefinitionEntity jobDefinition = new JobDefinitionEntity();
        jobDefinition.setId(UUID.randomUUID());
        jobDefinition.setJobType("HTTP_CALL");
        jobDefinition.setExpression("{}");
        jobDefinitionJpaRepository.save(jobDefinition);

        return new ScheduledTask(
                UUID.randomUUID(), "daily-report", "reports", jobDefinition.getId(), trigger, "UTC", 5, "desc");
    }

    @Test
    void roundTripsACronTriggerTask() {
        ScheduledTask saved = adapter.save(sample(new TriggerDefinition.Cron("0 0 1 * * ?")));

        assertThat(adapter.findById(saved.id())).contains(saved);
    }

    @Test
    void roundTripsADailyTimeIntervalTriggerTask() {
        var trigger = new TriggerDefinition.DailyTimeInterval(LocalTime.of(9, 0), LocalTime.of(17, 0), 15);

        ScheduledTask saved = adapter.save(sample(trigger));

        assertThat(adapter.findById(saved.id())).contains(saved);
    }

    @Test
    void searchFiltersByNameAndPaginates() {
        adapter.save(sample(new TriggerDefinition.Simple(60, 0)));

        PageResult<ScheduledTask> result = adapter.search("daily", PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void existsByJobDefinitionIdSeesLiveTasksOnlyAfterSoftDelete() {
        ScheduledTask saved = adapter.save(sample(new TriggerDefinition.Simple(60, 0)));

        assertThat(adapter.existsByJobDefinitionId(saved.jobDefinitionId())).isTrue();
        assertThat(adapter.existsByJobDefinitionId(UUID.randomUUID())).isFalse();

        adapter.delete(saved.id());

        assertThat(adapter.existsByJobDefinitionId(saved.jobDefinitionId())).isFalse();
    }

    @Test
    void deletedTaskIsNoLongerFound() {
        ScheduledTask saved = adapter.save(sample(new TriggerDefinition.Simple(60, 0)));

        adapter.delete(saved.id());

        assertThat(adapter.findById(saved.id())).isEmpty();
    }
}
