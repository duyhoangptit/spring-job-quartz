package com.system.reportjob.infrastructure.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import com.system.reportjob.shared.common.logging.MdcTaskDecorator;

@Configuration
public class VirtualThreadConfig {

    @Bean(name = "jobActionTaskExecutor")
    public AsyncTaskExecutor jobActionTaskExecutor() {
        TaskExecutorAdapter executor = new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
