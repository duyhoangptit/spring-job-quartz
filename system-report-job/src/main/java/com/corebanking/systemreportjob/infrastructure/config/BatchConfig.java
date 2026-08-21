package com.corebanking.systemreportjob.infrastructure.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Isolation;

@Configuration
public class BatchConfig extends JdbcDefaultBatchConfiguration {

    /**
     * Spring Batch mặc định dùng SERIALIZABLE cho các transaction "create*" của JobRepository
     * (createJobInstance/createStepExecution...) để chống chạy trùng cùng 1 job. Nhưng với job có
     * Flow.split() chạy step song song trong CÙNG 1 JobExecution (vd. bankingEndOfDayJob), hoặc
     * nhiều JobExecution được launch đồng thời (vd. trigger-now gọi dồn dập), SERIALIZABLE khiến
     * Postgres huỷ transaction do xung đột SSI (lỗi "could not serialize access due to read/write
     * dependencies among transactions"). REPEATABLE_READ vẫn đủ để chống chạy trùng job (theo
     * chính javadoc của JdbcDefaultBatchConfiguration#getIsolationLevelForCreate) mà không kích
     * hoạt kiểm tra SSI của Postgres cho các bản ghi độc lập như vậy.
     */
    @Override
    protected Isolation getIsolationLevelForCreate() {
        return Isolation.REPEATABLE_READ;
    }
}
