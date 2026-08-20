package com.corebanking.systemreportjob.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import javax.sql.DataSource;

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

import com.corebanking.systemreportjob.usecase.ports.out.JobActionExecutorPort;
import com.corebanking.systemreportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.SchedulerGatewayPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import com.corebanking.systemreportjob.usecase.ports.out.TaskRepositoryPort;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Ports with no adapter implementation yet (added in Tasks 10-12/15/17) — mocked here so the
    // full Spring context can boot this early in the plan.
    @MockitoBean
    TaskRepositoryPort taskRepositoryPort;

    @MockitoBean
    JobDefinitionRepositoryPort jobDefinitionRepositoryPort;

    @MockitoBean
    TaskExecutionHistoryRepositoryPort taskExecutionHistoryRepositoryPort;

    @MockitoBean
    SchedulerGatewayPort schedulerGatewayPort;

    @MockitoBean
    JobActionExecutorPort jobActionExecutorPort;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    DataSource dataSource;

    @Test
    void migratesAllExpectedTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(tableExists(metaData, "tasks")).isTrue();
            assertThat(tableExists(metaData, "job_definitions")).isTrue();
            assertThat(tableExists(metaData, "task_execution_history")).isTrue();
            assertThat(tableExists(metaData, "qrtz_job_details")).isTrue();
            assertThat(tableExists(metaData, "users")).isTrue();
            assertThat(tableExists(metaData, "user_exports")).isTrue();
            assertThat(tableExists(metaData, "batch_job_instance")).isTrue();
        }
    }

    private boolean tableExists(DatabaseMetaData metaData, String tableName) throws Exception {
        try (ResultSet rs = metaData.getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }
}
