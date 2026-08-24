package com.system.reportjob.infrastructure.jobactions.batch.users;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.Order;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class UserExportBatchConfig {

    @Bean
    @StepScope
    public JdbcPagingItemReader<UserRecord> userItemReader(DataSource dataSource) throws Exception {
        return new JdbcPagingItemReaderBuilder<UserRecord>()
                .name("userItemReader")
                .dataSource(dataSource)
                .selectClause("id, username, email, full_name, phone_number, address, gender, dob, description, status")
                .fromClause("users")
                .sortKeys(Map.of("id", Order.ASCENDING))
                .pageSize(1000)
                .rowMapper((rs, rowNum) -> new UserRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("phone_number"),
                        rs.getString("address"),
                        rs.getString("gender"),
                        rs.getObject("dob", java.time.LocalDate.class),
                        rs.getString("description"),
                        rs.getString("status")))
                .build();
    }

    @Bean
    public ItemProcessor<UserRecord, UserExportRecord> userExportProcessor() {
        return user -> new UserExportRecord(
                UUID.randomUUID(),
                user.id(),
                user.username(),
                user.email(),
                user.fullName(),
                user.phoneNumber(),
                user.address(),
                user.gender(),
                user.dob(),
                user.description(),
                user.status(),
                Instant.now());
    }

    @Bean
    public ItemWriter<UserExportRecord> userExportWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<UserExportRecord>()
                .dataSource(dataSource)
                .sql("INSERT INTO user_exports "
                        + "(id, user_id, username, email, full_name, phone_number, address, gender, dob, description, status, exported_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setObject(1, item.id());
                    ps.setObject(2, item.userId());
                    ps.setString(3, item.username());
                    ps.setString(4, item.email());
                    ps.setString(5, item.fullName());
                    ps.setString(6, item.phoneNumber());
                    ps.setString(7, item.address());
                    ps.setString(8, item.gender());
                    ps.setObject(9, item.dob());
                    ps.setString(10, item.description());
                    ps.setString(11, item.status());
                    ps.setObject(12, Timestamp.from(item.exportedAt()));
                })
                .build();
    }

    @Bean
    public Step exportUsersStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ItemReader<UserRecord> userItemReader,
            ItemProcessor<UserRecord, UserExportRecord> userExportProcessor,
            ItemWriter<UserExportRecord> userExportWriter,
            @Value("${app.batch.export.chunk-size:1000}") int chunkSize) {
        return new StepBuilder("exportUsersStep", jobRepository)
                .<UserRecord, UserExportRecord>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(userItemReader)
                .processor(userExportProcessor)
                .writer(userExportWriter)
                .build();
    }

    @Bean
    public Job exportUsersJob(JobRepository jobRepository, Step exportUsersStep) {
        return new JobBuilder("exportUsersJob", jobRepository)
                .start(exportUsersStep)
                .build();
    }
}
