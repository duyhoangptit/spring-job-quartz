package com.corebanking.systemreportjob.infrastructure.jobactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
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
                builder,
                new ObjectMapper(),
                new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()),
                Duration.ofSeconds(30));
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

    @Test
    void failsFastInsteadOfBlockingForeverWhenActionExceedsTimeout() {
        // Executor trả về future không bao giờ hoàn tất -> mô phỏng endpoint treo.
        AsyncTaskExecutor hangingExecutor = new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                // không chạy gì cả
            }

            @Override
            public Future<?> submit(Runnable task) {
                return new CompletableFuture<>();
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return new CompletableFuture<>();
            }
        };
        HttpCallJobAction action = new HttpCallJobAction(
                RestClient.builder(), new ObjectMapper(), hangingExecutor, Duration.ofMillis(100));
        JobDefinition definition = new JobDefinition(
                UUID.randomUUID(), "HTTP_CALL", "{\"url\":\"http://example.test/hang\",\"method\":\"GET\"}", null);

        long start = System.nanoTime();
        assertThatThrownBy(() -> action.execute(definition))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quá thời gian chờ");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
