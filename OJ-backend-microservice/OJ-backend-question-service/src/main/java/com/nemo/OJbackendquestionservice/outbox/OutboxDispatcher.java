package com.nemo.OJbackendquestionservice.outbox;

import com.nemo.OJbackendquestionservice.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import jakarta.annotation.Resource;
import com.nemo.OJbackendquestionservice.observability.OutboxMetrics;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 发件箱投递器。
 * <p>
 * 核心要求与对应做法：
 * <ol>
 *   <li><b>多实例竞争</b>：通过条件更新抢占任务并持有租约，
 *       同一事件同一时刻只可能被一个实例投递。</li>
 *   <li><b>租约回收</b>：实例崩溃时租约到期，其它实例会把它当作"待投递"重新抢占。
 *       因此崩溃只会导致重复投递，不会丢投递。</li>
 *   <li><b>成功判定</b>：必须同时满足"broker 确认（ACK）"与"路由成功（未被 return）"。
 *       仅凭 {@code convertAndSend} 返回就当作成功是错的——它只表示写入了本地
 *       channel，broker 可能根本没收下，或收下了却找不到队列。</li>
 *   <li><b>有界退避</b>：失败按指数退避重试，超过上限转 {@link OutboxStatus#DEAD}
 *       持久留痕，不丢任务、不无限重试。</li>
 * </ol>
 * <p>
 * 投递语义是**至少一次**：ACK 之后、标记已投递之前若崩溃，该事件会被重发。
 * 这是刻意的取舍（换掉"静默丢失"），由消费者的幂等来兜底。
 */
@Slf4j
@Component
public class OutboxDispatcher {

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;
    @Resource private OutboxMetrics outboxMetrics;

    @Value("${outbox.batch-size:50}")
    private int batchSize;

    @Value("${outbox.lease-seconds:60}")
    private int leaseSeconds;

    /** 等待 broker 确认的超时时间 */
    @Value("${outbox.confirm-timeout-ms:5000}")
    private long confirmTimeoutMs;

    @Value("${outbox.max-retries:8}")
    private int maxRetries;

    @Value("${outbox.backoff-base-seconds:5}")
    private int backoffBaseSeconds;

    @Value("${outbox.backoff-max-seconds:600}")
    private int backoffMaxSeconds;

    /** 本实例标识，进入租约持有者字段，便于排查是哪台机器在投递 */
    private final String instanceId = resolveInstanceId();

    /** 连续失败次数，用于告警节流（避免每轮都刷日志） */
    private final ConcurrentMap<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final ReentrantLock dispatchLock = new ReentrantLock();
    private final AtomicBoolean wakeRequested = new AtomicBoolean();

    public OutboxDispatcher(OutboxEventMapper outboxEventMapper, RabbitTemplate rabbitTemplate) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    private static String resolveInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + "-" + Integer.toHexString((int) (ProcessHandle.current().pid() & 0xffff));
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:2000}")
    public void dispatchPending() {
        if (!dispatchLock.tryLock()) {
            return;
        }
        try {
            int rounds = 0;
            int claimed;
            do {
                wakeRequested.set(false);
                claimed = dispatchOneBatch();
                rounds++;
            } while ((wakeRequested.get() || claimed >= batchSize) && rounds < 20);
        } finally {
            dispatchLock.unlock();
        }
    }

    /**
     * Low-latency path. Multiple commits are coalesced onto one small executor;
     * the database claim remains authoritative, so duplicate signals are safe.
     */
    @Async("outboxDispatchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOutboxEnqueued(OutboxEnqueuedEvent ignored) {
        wakeRequested.set(true);
        if (!dispatchLock.tryLock()) {
            return;
        }
        try {
            int rounds = 0;
            int claimed;
            do {
                wakeRequested.set(false);
                claimed = dispatchOneBatch();
                rounds++;
            } while ((wakeRequested.get() || claimed >= batchSize) && rounds < 20);
        } finally {
            dispatchLock.unlock();
        }
    }

    private int dispatchOneBatch() {
        String claimId = instanceId + "#" + UUID.randomUUID();
        int claimed;
        try {
            claimed = outboxEventMapper.claimDueEvents(claimId, leaseSeconds, batchSize);
        } catch (Exception e) {
            log.error("抢占发件箱事件失败", e);
            return 0;
        }
        if (claimed == 0) {
            return 0;
        }

        List<OutboxEvent> events = outboxEventMapper.listByClaim(claimId);
        int delivered = 0;
        for (OutboxEvent event : events) {
            if (publish(event, claimId)) {
                delivered++;
                if (outboxMetrics != null) outboxMetrics.recordDelivered(event.getDispatchAgeMicros());
            }
        }
        log.info("发件箱本轮: 抢占 {} 条, 投递成功 {} 条", events.size(), delivered);
        return claimed;
    }

    /**
     * 投递单条事件。
     *
     * @return 是否确认送达
     */
    private boolean publish(OutboxEvent event, String claimId) {
        try {
            CorrelationData correlation = new CorrelationData(event.getEventId());
            rabbitTemplate.convertAndSend(
                    OutboxService.JUDGE_EXCHANGE,
                    OutboxService.JUDGE_ROUTING_KEY,
                    event.getPayload(),
                    message -> {
                        message.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties()
                                .setHeader("eventId", event.getEventId());
                        message.getMessageProperties()
                                .setHeader("publishedAtEpochMs", System.currentTimeMillis());
                        return message;
                    },
                    correlation);

            // 等待 broker 确认。convertAndSend 返回不代表投递成功，必须等到 confirm。
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);

            if (!confirm.isAck()) {
                return onFailure(event, claimId,
                        "broker NACK: " + confirm.getReason());
            }

            // ACK 之后还要确认没有被退回：交换机收下了但找不到队列时，
            // broker 会先 ACK 再 return，只看 ACK 会误判为投递成功。
            if (correlation.getReturned() != null) {
                return onFailure(event, claimId,
                        "消息不可路由: " + correlation.getReturned().getReplyText());
            }

            int updated = outboxEventMapper.markDelivered(event.getId(), claimId);
            if (updated == 0) {
                // 租约已被回收（本实例卡顿过久），事件会被其它实例重新投递。
                // 这是允许的重复投递，由消费者幂等兜底。
                log.warn("投递确认后标记已投递失败（租约已转移），将由其它实例重投, eventId={}",
                        event.getEventId());
            }
            consecutiveFailures.remove(event.getEventType());
            return true;

        } catch (java.util.concurrent.TimeoutException e) {
            return onFailure(event, claimId, "等待 broker 确认超时(" + confirmTimeoutMs + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 不记为失败：交给租约到期后回收，避免中断瞬间误判
            log.warn("投递被中断, eventId={}", event.getEventId());
            return false;
        } catch (AmqpException e) {
            return onFailure(event, claimId, "发送异常: " + rootMessage(e));
        } catch (Exception e) {
            return onFailure(event, claimId, "投递异常: " + rootMessage(e));
        }
    }

    /**
     * 记录失败并按退避安排重试；超过上限转永久失败。
     * <p>
     * 永久失败**不删除记录**："长期失败任务进入可人工重试的持久记录"，
     * 处理方式见 deploy/migration/README.md。
     */
    private boolean onFailure(OutboxEvent event, String claimId, String reason) {
        int attempt = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        boolean exhausted = attempt >= maxRetries;

        int nextStatus = exhausted ? OutboxStatus.DEAD : OutboxStatus.PENDING;
        int delay = exhausted ? 0 : backoffSeconds(attempt);

        String message = truncate(reason, 500);
        try {
            outboxEventMapper.markFailed(event.getId(), claimId, nextStatus, delay, message);
        } catch (Exception e) {
            // 连失败状态都写不进去：租约到期后会被重新抢占，不会丢
            log.error("记录投递失败状态异常, eventId={}", event.getEventId(), e);
        }

        if (exhausted) {
            // 需要人工介入：这条提交将永远停留在待判题状态
            log.error("发件箱事件重试耗尽转入永久失败，需人工处理: eventId={}, submitId 见 payload, 原因={}",
                    event.getEventId(), message);
        } else {
            int fails = consecutiveFailures.merge(event.getEventType(), 1, Integer::sum);
            // 节流：首次与每 10 次记一次 warn，其余降为 debug，避免每 2 秒刷屏
            if (fails == 1 || fails % 10 == 0) {
                log.warn("发件箱投递失败(第 {} 次), eventId={}, {} 秒后重试, 原因={}",
                        attempt, event.getEventId(), delay, message);
            }
        }
        return false;
    }

    /** 指数退避，上限 backoffMaxSeconds */
    private int backoffSeconds(int attempt) {
        long delay = (long) backoffBaseSeconds * (1L << Math.min(attempt - 1, 16));
        return (int) Math.min(delay, backoffMaxSeconds);
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
