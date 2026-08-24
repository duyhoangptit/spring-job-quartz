package com.system.reportjob.infrastructure.jobactions.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
class UserExportBatchConfigTest {

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

    // Both test methods share one Testcontainers Postgres instance for the whole class, and the
    // batch job reads/writes the full `users`/`user_exports` tables (no per-test scoping), so each
    // test must start from a clean slate to keep its row-count assertions independent of test order.
    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE user_exports, users, batch_job_instance CASCADE");
    }

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

    @Test
    void runningJobTwiceSequentiallyReopensReaderCleanlyAndAppendsBothRuns() throws Exception {
        seedUsers(20);
        JobOperatorTestUtils testUtils = new JobOperatorTestUtils(jobOperator, jobRepository);
        testUtils.setJob(exportUsersJob);

        // Two full, non-overlapping runs against the real @StepScope + JdbcDefaultBatchConfiguration
        // wiring via actual JobOperator.start() calls: the reader's open() -> read -> close()
        // lifecycle completes entirely before the second run starts. This guards the functional
        // behaviour the design relies on: repeated fires of the same append-only job must each
        // complete cleanly and accumulate correctly (also the scenario Finding 4 asked for).
        JobExecution firstExecution = testUtils.startJob(new JobParametersBuilder()
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters());
        assertThat(firstExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution secondExecution = testUtils.startJob(new JobParametersBuilder()
                .addString("run", UUID.randomUUID().toString())
                .toJobParameters());
        assertThat(secondExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer exportedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_exports", Integer.class);
        assertThat(exportedCount).isEqualTo(40);

        Integer completedSteps = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM batch_step_execution WHERE step_name = 'exportUsersStep' AND status = 'COMPLETED'",
                Integer.class);
        assertThat(completedSteps).isEqualTo(2);
    }
}
