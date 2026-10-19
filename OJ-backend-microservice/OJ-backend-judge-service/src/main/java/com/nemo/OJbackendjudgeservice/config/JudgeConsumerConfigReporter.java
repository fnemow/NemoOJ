package com.nemo.OJbackendjudgeservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
public class JudgeConsumerConfigReporter {
    @Value("${spring.rabbitmq.listener.simple.concurrency:2}") private int concurrency;
    @Value("${spring.rabbitmq.listener.simple.prefetch:1}") private int prefetch;

    @PostConstruct
    void report() {
        log.info("判题消费者配置已生效: concurrency={}, prefetch={}", concurrency, prefetch);
    }
}
