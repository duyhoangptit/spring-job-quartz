package com.corebanking.systemreportjob.infrastructure.jobactions.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UserExportBatchConfigIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    Job exportUsersJob;

    private void seedUsers(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, username, email, full_name, status) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    "it-user-" + i,
                    "it-user-" + i + "@example.com",
                    "IT User " + i,
                    "ACTIVE");
        }
    }

    @Test
    void exportsAllUsersIntoUserExportsTable() throws Exception {
        seedUsers(20);
        JobOperatorTestUtils testUtils = new JobOperatorTestUtils(jobOperator, jobRepository);
        testUtils.setJob(exportUsersJob);

        JobExecution execution = testUtils.startJob(new JobParametersBuilder()
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer exportedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_exports", Integer.class);
        assertThat(exportedCount).isEqualTo(20);

        // Proves the JDBC-backed JobRepository (BatchConfig) is actually wired, not Boot's
        // default in-memory ResourcelessJobRepository: this row only exists if Spring Batch
        // persisted step execution state to the real Postgres BATCH_* tables.
        Integer stepExecutions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_step_execution WHERE step_name = 'exportUsersStep' AND status = 'COMPLETED'",
                Integer.class);
        assertThat(stepExecutions).isEqualTo(1);
    }
}
