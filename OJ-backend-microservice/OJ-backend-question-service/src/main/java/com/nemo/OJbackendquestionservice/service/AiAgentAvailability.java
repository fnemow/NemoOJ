package com.nemo.OJbackendquestionservice.service;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Fast broker-side readiness check for optional AI task creation. */
@Component
public class AiAgentAvailability {
    private static final long CACHE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    private final RabbitTemplate rabbit;
    private volatile long checkedAt;
    private volatile boolean available;

    public AiAgentAvailability(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public boolean isAvailable() {
        long now = System.nanoTime();
        if (checkedAt != 0 && now - checkedAt < CACHE_NANOS) return available;
        synchronized (this) {
            now = System.nanoTime();
            if (checkedAt != 0 && now - checkedAt < CACHE_NANOS) return available;
            try {
                Integer consumers = rabbit.execute(channel ->
                        channel.queueDeclarePassive("nemo.ai.tasks").getConsumerCount());
                available = consumers != null && consumers > 0;
            } catch (Exception ignored) {
                available = false;
            }
            checkedAt = System.nanoTime();
            return available;
        }
    }
}
