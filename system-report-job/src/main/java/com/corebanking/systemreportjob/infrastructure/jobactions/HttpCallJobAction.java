package com.corebanking.systemreportjob.infrastructure.jobactions;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public HttpCallJobAction(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Qualifier("jobActionTaskExecutor") AsyncTaskExecutor jobActionTaskExecutor) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.jobActionTaskExecutor = jobActionTaskExecutor;
    }

    @Override
    public boolean matches(String jobType) {
        return "HTTP_CALL".equals(jobType);
    }

    @Override
    public void execute(JobDefinition definition) {
        try {
            jobActionTaskExecutor.submit(() -> callHttp(definition)).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("HTTP_CALL job action thất bại: " + definition.id(), e.getCause());
        } catch (InterruptedException e) {
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
