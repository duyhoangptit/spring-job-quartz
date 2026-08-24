package com.system.reportjob.e2e;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class EndToEndTaskExecutionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override the "test" profile's in-memory Quartz store for this one test — exercises the
        // real JDBC-backed job store (and the QRTZ_* tables from V1) against Testcontainers Postgres.
        registry.add("spring.quartz.job-store-type", () -> "jdbc");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsTaskAndRecordsExecutionHistoryEndToEnd() throws Exception {
        String jobDefinitionBody = mockMvc.perform(post("/api/job-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ECHO\",\"expression\":\"{\\\"msg\\\":\\\"e2e\\\"}\"}"))
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
								{"name":"e2e-task","group":"e2e","jobDefinitionId":"%s",
								"triggerType":"SIMPLE","intervalInSeconds":1,"repeatCount":0}
								"""
                                        .formatted(jobDefinitionId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(taskBody).path("data").path("id").asText();

        mockMvc.perform(post("/api/tasks/start/{id}", taskId)).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mockMvc.perform(
                        get("/api/task-history/search").param("taskName", "e2e-task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.length()", is(1)))
                .andExpect(jsonPath("$.data.data[0].taskName", is("e2e-task"))));
    }
}
