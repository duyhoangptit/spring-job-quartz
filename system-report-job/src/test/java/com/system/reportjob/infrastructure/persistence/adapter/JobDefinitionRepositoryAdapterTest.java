package com.system.reportjob.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.system.reportjob.domain.model.JobDefinition;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Import(JobDefinitionRepositoryAdapter.class)
class JobDefinitionRepositoryAdapterTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    JobDefinitionRepositoryAdapter adapter;

    @Test
    void savesAndReloadsAJobDefinition() {
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "HTTP_CALL", "{\"url\":\"http://x\"}", "desc");

        JobDefinition saved = adapter.save(definition);

        assertThat(adapter.findById(saved.id())).contains(saved);
    }

    @Test
    void deletedJobDefinitionIsNoLongerFound() {
        JobDefinition definition = new JobDefinition(UUID.randomUUID(), "ECHO", "{}", null);
        JobDefinition saved = adapter.save(definition);

        adapter.delete(saved.id());

        assertThat(adapter.findById(saved.id())).isEmpty();
    }
}
