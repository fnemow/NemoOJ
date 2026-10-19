package com.nemo.OJbackendquestionservice.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** At-least-once AI queue dispatcher backed by the MySQL transaction outbox. */
@Slf4j
@Component
public class AiOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    @Value("${ai.outbox.batch-size:20}") private int batchSize;
    @Value("${ai.outbox.max-retries:8}") private int maxRetries;

    public AiOutboxDispatcher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${ai.outbox.poll-interval-ms:2000}")
    public void dispatch() {
        List<Row> due;
        try {
            due = jdbc.query("SELECT id,taskId FROM ai_outbox WHERE "
                            + "(status='PENDING' AND nextAttemptAt<=NOW()) OR (status='SENDING' AND leaseExpireAt<NOW()) "
                            + "ORDER BY id LIMIT ?", (rs, row) -> new Row(rs.getLong("id"), rs.getString("taskId")), batchSize);
        } catch (Exception e) {
            log.warn("AI outbox scan failed", e);
            return;
        }
        for (Row row : due) publish(row);
    }

    private void publish(Row row) {
        int claimed = jdbc.update("UPDATE ai_outbox SET status='SENDING',leaseExpireAt=DATE_ADD(NOW(),INTERVAL 60 SECOND) "
                        + "WHERE id=? AND ((status='PENDING' AND nextAttemptAt<=NOW()) OR (status='SENDING' AND leaseExpireAt<NOW()))",
                row.id());
        if (claimed == 0) return;
        try {
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            String type=jdbc.queryForObject("SELECT type FROM ai_task WHERE id=?",String.class,row.taskId());
            String routing="ANALYSIS".equals(type)?"nemo.ai.analysis":List.of("PLAN","PLAN_ADJUST").contains(type)?"nemo.ai.plans":"PRIVATE_PROBLEM".equals(type)?"nemo.ai.private":"nemo.ai.task";
            rabbit.convertAndSend("nemo.ai.exchange", routing, Map.of("taskId", row.taskId()), message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setMessageId(row.taskId());
                return message;
            }, correlation);
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ rejected AI task event: " + confirm.getReason());
            }
            jdbc.update("UPDATE ai_outbox SET status='SENT',leaseExpireAt=NULL,lastError=NULL WHERE id=? AND status='SENDING'", row.id());
        } catch (Exception e) {
            Integer retry = jdbc.queryForObject("SELECT retryCount FROM ai_outbox WHERE id=?", Integer.class, row.id());
            int next = (retry == null ? 0 : retry) + 1;
            String status = next >= maxRetries ? "DEAD" : "PENDING";
            jdbc.update("UPDATE ai_outbox SET status=?,retryCount=?,leaseExpireAt=NULL,nextAttemptAt=DATE_ADD(NOW(),INTERVAL LEAST(600,5*POW(2,?)) SECOND),lastError=? WHERE id=?",
                    status, next, Math.min(next - 1, 7), e.getMessage() == null ? "publish failed" : e.getMessage().substring(0, Math.min(500, e.getMessage().length())), row.id());
            log.warn("AI outbox delivery failed, taskId={}, attempt={}", row.taskId(), next, e);
        }
    }

    private record Row(long id, String taskId) { }
}
