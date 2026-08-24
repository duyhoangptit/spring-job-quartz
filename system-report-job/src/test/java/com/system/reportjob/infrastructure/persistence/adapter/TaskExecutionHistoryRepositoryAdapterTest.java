package com.system.reportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import com.system.reportjob.domain.model.TaskExecutionRecord;
import com.system.reportjob.domain.model.TriggerType;
import com.system.reportjob.infrastructure.persistence.entity.JobDefinitionEntity;
import com.system.reportjob.infrastructure.persistence.entity.TaskEntity;
import com.system.reportjob.infrastructure.persistence.repository.JobDefinitionJpaRepository;
import com.system.reportjob.infrastructure.persistence.repository.TaskJpaRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(TaskExecutionHistoryRepositoryAdapter.class)
class TaskExecutionHistoryRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TaskExecutionHistoryRepositoryAdapter adapter;

    @Autowired
    TaskJpaRepository taskJpaRepository;

    @Autowired
    JobDefinitionJpaRepository jobDefinitionJpaRepository;

    private UUID persistTask() {
        JobDefinitionEntity jobDefinition = new JobDefinitionEntity();
        jobDefinition.setId(UUID.randomUUID());
        jobDefinition.setJobType("ECHO");
        jobDefinitionJpaRepository.save(jobDefinition);

        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setName("daily-report");
        task.setTaskGroup("reports");
        task.setJobDefinitionId(jobDefinition.getId());
        task.setTriggerType(TriggerType.SIMPLE);
        task.setIntervalInSeconds(60);
        task.setRepeatCount(0);
        return taskJpaRepository.save(task).getId();
    }

    @Test
    void savesAndSearchesByTaskName() {
        UUID taskId = persistTask();
        TaskExecutionRecord record =
                new TaskExecutionRecord(UUID.randomUUID(), taskId, "daily-report", Instant.now(), Instant.now(), null);

        adapter.save(record);
        PageResult<TaskExecutionRecord> result = adapter.search("daily", PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).taskName()).isEqualTo("daily-report");
    }

    @Test
    void generatesIdWhenSavingRecordWithNullId() {
        UUID taskId = persistTask();
        TaskExecutionRecord record =
                new TaskExecutionRecord(null, taskId, "daily-report", Instant.now(), Instant.now(), null);

        TaskExecutionRecord saved = adapter.save(record);

        assertThat(saved.id()).isNotNull();
    }
}
