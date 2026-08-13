package com.corebanking.systemreportjob.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class HttpClientConfigTest {

    @Test
    void buildsARequestFactoryWithTheConfiguredTimeouts() {
        ClientHttpRequestFactory requestFactory =
                HttpClientConfig.requestFactory(Duration.ofSeconds(5), Duration.ofSeconds(25));

        assertThat(requestFactory).isNotNull();
    }

    @Test
    void customizerAppliesTheTimeoutAwareRequestFactoryToTheBuilder() {
        RestClientCustomizer customizer =
                new HttpClientConfig().jobActionRestClientCustomizer(Duration.ofSeconds(5), Duration.ofSeconds(25));
        RestClient.Builder builder = mock(RestClient.Builder.class);

        customizer.customize(builder);

        verify(builder).requestFactory(any(ClientHttpRequestFactory.class));
    }
}
