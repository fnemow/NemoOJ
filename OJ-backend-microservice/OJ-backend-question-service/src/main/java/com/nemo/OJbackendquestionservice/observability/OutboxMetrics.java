package com.nemo.OJbackendquestionservice.observability;

import com.nemo.OJbackendquestionservice.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发件箱与提交积压指标。
 * <p>
 * 指标设计要点：
 * <ul>
 *   <li><b>标签基数有界</b>。只用固定的状态类标签，绝不把 submitId、eventId
 *       这类每次调用都不同的值作为标签——那会让时间序列数量随业务量无限增长，
 *       最终把监控系统自己压垮。单条提交的排查靠日志的 requestId 串联，不靠指标。</li>
 *   <li><b>用 Gauge 而非定时打点</b>。积压量是"当前状态"而不是"事件计数"，
 *       由抓取时读取即可，不需要在应用里维护累计值。</li>
 *   <li><b>查询失败不清零</b>。取数异常时保留上一次的值并记录日志，
 *       否则数据库抖动会让积压指标瞬间归零，掩盖正在发生的故障。</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxEventMapper outboxEventMapper;
    private final Timer dispatchDelay;

    /** 最近一次成功读取的积压情况，用于取数失败时保留旧值 */
    private volatile double lastPending = 0;
    private volatile double lastOldestWaitSeconds = 0;
    private volatile double lastDead = 0;

    public OutboxMetrics(OutboxEventMapper outboxEventMapper, MeterRegistry registry) {
        this.outboxEventMapper = outboxEventMapper;
        this.dispatchDelay = Timer.builder("nemo_outbox_dispatch_delay")
                .description("发件箱事件创建到成功投递的耗时")
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(10)).register(registry);

        Gauge.builder("nemo_outbox_pending_events", this, OutboxMetrics::pendingEvents)
                .description("待投递（含投递中）的发件箱事件数")
                .register(registry);

        Gauge.builder("nemo_outbox_oldest_wait_seconds", this, OutboxMetrics::oldestWaitSeconds)
                .description("最老待投递事件的等待时长，单位秒")
                .register(registry);

        Gauge.builder("nemo_outbox_dead_events", this, OutboxMetrics::deadEvents)
                .description("重试耗尽、需人工处理的发件箱事件数")
                .register(registry);
    }

    public void recordDelivered(Long ageMicros) {
        if (ageMicros != null) dispatchDelay.record(Math.max(0, ageMicros), TimeUnit.MICROSECONDS);
    }

    /**
     * 定期刷新取数。Gauge 回调里直接查库会让每次抓取都打一次 DB，
     * 抓取频率高时会成为额外负载；这里按固定周期读取并缓存。
     */
    @Scheduled(fixedDelayString = "${management.metrics.outbox-refresh-ms:15000}")
    public void refresh() {
        try {
            Long oldest = outboxEventMapper.oldestPendingWaitSeconds();
            lastOldestWaitSeconds = oldest == null ? 0 : Math.max(0, oldest);
            lastDead = outboxEventMapper.countDead();
            lastPending = countPending();
        } catch (Exception e) {
            // 保留上一次的值，避免依赖抖动导致指标假性归零
            log.warn("刷新发件箱指标失败，保留上次取值: {}", e.getMessage());
        }
    }

    private double countPending() {
        long pending = 0;
        for (java.util.Map<String, Object> row : outboxEventMapper.countByStatus()) {
            Object status = row.get("status");
            Object cnt = row.get("cnt");
            if (status == null || cnt == null) {
                continue;
            }
            int st = ((Number) status).intValue();
            if (st == 0 || st == 1) { // 待投递 + 投递中
                pending += ((Number) cnt).longValue();
            }
        }
        return pending;
    }

    double pendingEvents() {
        return lastPending;
    }

    double oldestWaitSeconds() {
        return lastOldestWaitSeconds;
    }

    double deadEvents() {
        return lastDead;
    }
}
