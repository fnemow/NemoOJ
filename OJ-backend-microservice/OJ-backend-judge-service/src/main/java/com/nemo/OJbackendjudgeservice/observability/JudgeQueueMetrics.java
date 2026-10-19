package com.nemo.OJbackendjudgeservice.observability;

import com.nemo.OJbackendcommon.constant.JudgeMqConstant;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Broker queue depth plus the number of deliveries currently held by this service. */
@Component
public class JudgeQueueMetrics {

    private final AmqpAdmin amqpAdmin;
    private final AtomicInteger ready = new AtomicInteger();
    private final AtomicInteger unacknowledged = new AtomicInteger();
    private final AtomicInteger consumers = new AtomicInteger();

    public JudgeQueueMetrics(AmqpAdmin amqpAdmin, MeterRegistry registry) {
        this.amqpAdmin = amqpAdmin;
        registry.gauge("nemo_judge_queue_ready", ready);
        registry.gauge("nemo_judge_queue_unacknowledged", unacknowledged);
        registry.gauge("nemo_judge_queue_consumers", consumers);
    }

    public void deliveryStarted() {
        unacknowledged.incrementAndGet();
    }

    public void deliveryFinished() {
        unacknowledged.updateAndGet(value -> Math.max(0, value - 1));
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${judge.metrics.queue-interval-ms:5000}")
    public void refreshBrokerState() {
        Properties properties = amqpAdmin.getQueueProperties(JudgeMqConstant.JUDGE_QUEUE);
        if (properties == null) {
            ready.set(0);
            consumers.set(0);
            return;
        }
        ready.set(number(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)));
        consumers.set(number(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)));
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
