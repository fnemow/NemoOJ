package com.nemo.OJbackendquestionservice.outbox;

import com.nemo.OJbackendquestionservice.mapper.OutboxEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 待判题超时对账。
 * <p>
 * 目标不是"自动修复"，而是**能解释每一条未完成提交当前的归属**：
 * 它卡在投递、卡在消费，还是根本没人管。只看 {@code question_submit.status}
 * 无法区分这些情况——这正是"静默丢任务"难以发现的原因。
 * <p>
 * 输出四类计数，其中"投递成功但仍待判题"是最需要关注的一类：
 * 说明消息已确认送达 broker，但判题服务没有把它推进（消费者故障、队列积压或消息被丢弃）。
 */
@Slf4j
@Component
public class SubmissionReconciler {

    private final JdbcTemplate jdbcTemplate;
    private final OutboxEventMapper outboxEventMapper;

    /** 提交后超过该时长仍未完成，视为异常 */
    @Value("${outbox.reconcile-timeout-seconds:300}")
    private int timeoutSeconds;

    public SubmissionReconciler(JdbcTemplate jdbcTemplate, OutboxEventMapper outboxEventMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.reconcile-interval-ms:60000}")
    public void reconcile() {
        try {
            reportOutboxHealth();
            reportStuckSubmissions();
        } catch (Exception e) {
            log.error("待判题对账异常", e);
        }
    }

    /** 发件箱自身健康度：积压、最老等待时间、永久失败数 */
    private void reportOutboxHealth() {
        List<Map<String, Object>> byStatus = outboxEventMapper.countByStatus();
        Long oldestWait = outboxEventMapper.oldestPendingWaitSeconds();
        long dead = outboxEventMapper.countDead();

        if (oldestWait != null && oldestWait > timeoutSeconds) {
            log.warn("发件箱投递积压: 最老待投递事件已等待 {} 秒, 状态分布={}",
                    oldestWait, byStatus);
        } else {
            log.debug("发件箱状态分布={}, 最老待投递等待={}秒", byStatus, oldestWait);
        }

        if (dead > 0) {
            log.error("存在 {} 条永久失败的发件箱事件，需人工处理（见 deploy/migration/README.md）", dead);
        }
    }

    /**
     * 分类统计超时仍未完成的提交。
     * <p>
     * 每一条未完成提交都能落到下面某一类，不存在"说不清去哪了"的情况。
     */
    private void reportStuckSubmissions() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT "
                        + "  SUM(CASE WHEN s.status = 0 AND e.status = 0 THEN 1 ELSE 0 END) AS pending_delivery, "
                        + "  SUM(CASE WHEN s.status = 0 AND e.status = 1 THEN 1 ELSE 0 END) AS in_flight, "
                        + "  SUM(CASE WHEN s.status = 0 AND e.status = 2 THEN 1 ELSE 0 END) AS delivered_not_consumed, "
                        + "  SUM(CASE WHEN s.status = 0 AND e.status = 3 THEN 1 ELSE 0 END) AS delivery_dead, "
                        + "  SUM(CASE WHEN s.status = 0 AND e.id IS NULL THEN 1 ELSE 0 END) AS no_event, "
                        + "  SUM(CASE WHEN s.status = 1 THEN 1 ELSE 0 END) AS judging, "
                        + "  COUNT(*) AS total "
                        + "FROM question_submit s "
                        + "LEFT JOIN outbox_event e ON e.bizId = s.id AND e.eventType = 'JUDGE_SUBMIT' "
                        + "WHERE s.isDelete = 0 AND s.status IN (0, 1) "
                        + "  AND s.createTime < DATE_SUB(NOW(), INTERVAL ? SECOND)",
                timeoutSeconds);

        long stuck = asLong(row.get("total"));
        if (stuck == 0) {
            log.debug("对账: 无超时未完成提交");
            return;
        }

        log.warn("对账: 超过 {} 秒仍未完成的提交共 {} 条 — "
                        + "待投递={}, 投递中={}, 已投递未消费(需查判题服务/队列)={}, "
                        + "投递永久失败={}, 无投递事件(数据异常)={}, 判题中={}",
                timeoutSeconds, stuck,
                asLong(row.get("pending_delivery")),
                asLong(row.get("in_flight")),
                asLong(row.get("delivered_not_consumed")),
                asLong(row.get("delivery_dead")),
                asLong(row.get("no_event")),
                asLong(row.get("judging")));

        long unaccounted = asLong(row.get("no_event"));
        if (unaccounted > 0) {
            log.error("有 {} 条提交没有任何投递事件记录，属于数据异常："
                    + "可能是旧版本（直接发 MQ）遗留的提交，或事件被误删。需人工核对。", unaccounted);
        }
    }

    private long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
