package com.system.reportjob.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;

/**
 * Regression guard for the CONSOLE_LOG_PATTERN/FILE_LOG_PATTERN naming collision with Spring
 * Boot's own defaults.xml (see logback-spring.xml comment): a same-named override there reads
 * back correctly via LoggerContext#getProperty but never actually reaches the appender, because
 * Boot resolves its own defaults.xml property during its early bootstrap phase, before this
 * file's &lt;springProperty&gt; can run. This asserts the pattern the CONSOLE/FILE appenders were
 * *actually built with* — not just the context property — includes requestId.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class LogbackConfigTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void consoleAndFileAppendersActuallyLogTheRequestIdMdcValue() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        assertThat(actualPatternOf(root.getAppender("CONSOLE"))).contains("requestId");
        assertThat(actualPatternOf(root.getAppender("FILE"))).contains("requestId");
    }

    private String actualPatternOf(Appender<?> appender) {
        Object encoder = ((OutputStreamAppender<?>) appender).getEncoder();
        return ((PatternLayoutEncoder) encoder).getPattern();
    }
}
