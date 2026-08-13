package com.corebanking.systemreportjob.infrastructure.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Áp timeout cho mọi {@code RestClient} dựng từ {@code RestClient.Builder} auto-configured.
 *
 * <p>Không có timeout thì một endpoint treo sẽ giữ luôn worker thread của Quartz (mặc định 10
 * thread) và làm đứng toàn bộ scheduler.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClientCustomizer jobActionRestClientCustomizer(
            @Value("${app.http-client.connect-timeout:5s}") Duration connectTimeout,
            @Value("${app.http-client.read-timeout:25s}") Duration readTimeout) {
        ClientHttpRequestFactory requestFactory = requestFactory(connectTimeout, readTimeout);
        return builder -> builder.requestFactory(requestFactory);
    }

    static ClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        return ClientHttpRequestFactoryBuilder.detect()
                .build(HttpClientSettings.defaults().withTimeouts(connectTimeout, readTimeout));
    }
}
