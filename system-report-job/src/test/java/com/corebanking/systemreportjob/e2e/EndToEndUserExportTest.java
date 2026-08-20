package com.corebanking.systemreportjob.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class EndToEndUserExportTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.quartz.job-store-type", () -> "jdbc");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private void seedUsers(int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO users (id, username, email, full_name, status) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    "e2e-user-" + i,
                    "e2e-user-" + i + "@example.com",
                    "E2E User " + i,
                    "ACTIVE");
        }
    }

    @Test
    void exportsUsersEndToEndWhenTaskFires() throws Exception {
        seedUsers(5);

        String jobDefinitionBody = mockMvc.perform(post("/api/job-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"EXPORT_USERS\",\"expression\":\"{}\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jobDefinitionId =
                objectMapper.readTree(jobDefinitionBody).path("data").path("id").asText();

        String taskBody = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
								{"name":"e2e-export-users","group":"e2e","jobDefinitionId":"%s",
								"triggerType":"SIMPLE","intervalInSeconds":1,"repeatCount":0}
								"""
                                        .formatted(jobDefinitionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(taskBody).path("data").path("id").asText();

        mockMvc.perform(post("/api/tasks/start/{id}", taskId)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Integer exportedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_exports", Integer.class);
            assertThat(exportedCount).isEqualTo(5);
        });
    }
}
