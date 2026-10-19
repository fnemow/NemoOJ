package com.nemo.OJbackendquestionservice.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** Keeps broker confirmation waits away from the HTTP request thread. */
@Configuration
@EnableAsync
public class OutboxDispatchConfig {

    @Bean(name = "outboxDispatchExecutor")
    public Executor outboxDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("outbox-wakeup-");
        // A saturated wake-up is safe to discard because the scheduled scan is
        // always active and all work is already durable in MySQL.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "submissionProgressExecutor")
    public Executor submissionProgressExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("submission-progress-");
        executor.initialize();
        return executor;
    }
}
