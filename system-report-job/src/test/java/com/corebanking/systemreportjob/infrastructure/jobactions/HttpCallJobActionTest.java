package com.corebanking.systemreportjob.infrastructure.jobactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

class HttpCallJobActionTest {

    private HttpCallJobAction newAction(RestClient.Builder builder) {
        return new HttpCallJobAction(
                builder, new ObjectMapper(), new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
    }

    @Test
    void callsConfiguredHttpEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://example.test/ping"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));
        JobDefinition definition = new JobDefinition(
                UUID.randomUUID(), "HTTP_CALL", "{\"url\":\"http://example.test/ping\",\"method\":\"POST\"}", null);

        newAction(builder).execute(definition);

        server.verify();
    }

    @Test
    void matchesOnlyHttpCallJobType() {
        HttpCallJobAction action = newAction(RestClient.builder());

        assertThat(action.matches("HTTP_CALL")).isTrue();
        assertThat(action.matches("ECHO")).isFalse();
    }
}
