package com.corebanking.systemreportjob.infrastructure.jobactions;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.corebanking.systemreportjob.domain.model.JobDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class HttpCallJobAction implements JobAction {

    private static final Logger log = LoggerFactory.getLogger(HttpCallJobAction.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor jobActionTaskExecutor;
    private final Duration executionTimeout;

    public HttpCallJobAction(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor,
            @Value("${app.job-action.execution-timeout:30s}") Duration executionTimeout) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
        this.executionTimeout = executionTimeout;
    }

    @Override
    public boolean matches(String jobType) {
        return "HTTP_CALL".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        Future<Void> future = jobActionTaskExecutor.submit(() -> callHttp(definition));
        try {
            future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("HTTP_CALL job action thất bại: " + definition.id(), e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(
                    "HTTP_CALL job action quá thời gian chờ (" + executionTimeout + "): " + definition.id(), e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP_CALL job action bị gián đoạn: " + definition.id(), e);
        }
    }

    private Void callHttp(JobDefinition definition) throws Exception {
        HttpCallExpression expression = objectMapper.readValue(definition.expression(), HttpCallExpression.class);
        HttpHeaders headers = new HttpHeaders();
        if (expression.headers() != null) {
            expression.headers().forEach(headers::set);
        }
        String result = restClient
                .method(HttpMethod.valueOf(expression.method()))
                .uri(expression.url())
                .headers(h -> h.addAll(headers))
                .retrieve()
                .body(String.class);
        log.info("HTTP_CALL job {} result: {}", definition.id(), result);
        return null;
    }

    record HttpCallExpression(String url, String method, Map<String, String> headers) {}
}
