package com.system.reportjob.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.system.reportjob.usecase.ports.out.JobActionExecutorPort;
import com.system.reportjob.usecase.ports.out.JobDefinitionRepositoryPort;
import com.system.reportjob.usecase.ports.out.SchedulerGatewayPort;
import com.system.reportjob.usecase.ports.out.TaskExecutionHistoryRepositoryPort;
import com.system.reportjob.usecase.ports.out.TaskRepositoryPort;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class QuartzClusterConfigTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

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

    @Autowired
    Scheduler scheduler;

    @Autowired
    QuartzClusterConfig.AutowiringSpringBeanJobFactory jobFactory;

    @Test
    void schedulerBeanIsConfiguredAndStarted() throws Exception {
        assertThat(scheduler.isStarted()).isTrue();
        assertThat(jobFactory).isNotNull();
    }
}
