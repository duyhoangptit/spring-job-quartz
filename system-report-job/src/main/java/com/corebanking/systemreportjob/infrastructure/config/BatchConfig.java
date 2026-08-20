package com.corebanking.systemreportjob.infrastructure.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BatchConfig extends JdbcDefaultBatchConfiguration {}
