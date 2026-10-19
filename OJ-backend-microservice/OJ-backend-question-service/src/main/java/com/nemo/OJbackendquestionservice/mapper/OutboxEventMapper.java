package com.nemo.OJbackendquestionservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nemo.OJbackendquestionservice.outbox.OutboxEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 发件箱数据访问。
 * <p>
 * 抢占与回收用条件更新完成，不依赖应用层锁——多个实例同时轮询时，
 * 只有真正把状态从"待投递"改成"投递中"的那个实例会取到任务。
 */
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 抢占有资格投递的事件：
     * <ul>
     *   <li>待投递且已到下次尝试时间；或</li>
     *   <li>投递中但租约已过期（说明持有者已崩溃，需要回收重投）。</li>
     * </ul>
     * 抢占时写入本轮唯一标记，便于下一步精确取回本轮抢到的行。
     *
     * @return 抢到的行数
     */
    @Update("UPDATE outbox_event SET "
            + "  status = 1, "
            + "  leaseOwner = #{claimId}, "
            + "  leaseExpireAt = DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND) "
            + "WHERE id IN ( "
            + "  SELECT id FROM ( "
            + "    SELECT id FROM outbox_event "
            + "    WHERE (status = 0 AND nextAttemptAt <= NOW()) "
            + "       OR (status = 1 AND leaseExpireAt IS NOT NULL AND leaseExpireAt < NOW()) "
            + "    ORDER BY nextAttemptAt ASC, id ASC "
            + "    LIMIT #{batchSize} "
            + "  ) AS candidate "
            + ")")
    int claimDueEvents(@Param("claimId") String claimId,
                       @Param("leaseSeconds") int leaseSeconds,
                       @Param("batchSize") int batchSize);

    /** 取回本轮抢占到的事件 */
    @Select("SELECT *, TIMESTAMPDIFF(MICROSECOND, createTime, NOW(6)) AS dispatchAgeMicros "
            + "FROM outbox_event WHERE leaseOwner = #{claimId} ORDER BY id ASC")
    List<OutboxEvent> listByClaim(@Param("claimId") String claimId);

    /**
     * 标记投递成功。
     * <p>
     * 条件里带上 {@code status = 1} 与租约持有者：若租约已被回收并由其它实例接管，
     * 本实例的迟到更新不应把状态改回已投递（那条消息由接管者负责）。
     */
    @Update("UPDATE outbox_event SET "
            + "  status = 2, leaseOwner = NULL, leaseExpireAt = NULL, lastError = NULL "
            + "WHERE id = #{id} AND status = 1 AND leaseOwner = #{claimId}")
    int markDelivered(@Param("id") Long id, @Param("claimId") String claimId);

    /**
     * 投递失败：增加重试次数、按退避安排下次尝试，或达到上限转永久失败。
     * 同样带租约条件，避免迟到更新覆盖他人状态。
     */
    @Update("UPDATE outbox_event SET "
            + "  retryCount = retryCount + 1, "
            + "  status = #{nextStatus}, "
            + "  nextAttemptAt = DATE_ADD(NOW(), INTERVAL #{delaySeconds} SECOND), "
            + "  lastError = #{lastError}, "
            + "  leaseOwner = NULL, "
            + "  leaseExpireAt = NULL "
            + "WHERE id = #{id} AND status = 1 AND leaseOwner = #{claimId}")
    int markFailed(@Param("id") Long id,
                   @Param("claimId") String claimId,
                   @Param("nextStatus") int nextStatus,
                   @Param("delaySeconds") int delaySeconds,
                   @Param("lastError") String lastError);

    /** 统计各状态数量，用于对账与告警 */
    @Select("SELECT status, COUNT(*) AS cnt FROM outbox_event GROUP BY status")
    List<java.util.Map<String, Object>> countByStatus();

    /**
     * 最老的待投递事件已经等待了多少秒；无待投递时返回 null。
     * <p>
     * 必须用 createTime 而不是 nextAttemptAt：后者在退避期间是**未来时刻**，
     * 相减得到负数、被截断为 0，指标会永远显示"没有积压"——
     * 恰恰在最需要它的故障场景下失效。
     */
    @Select("SELECT TIMESTAMPDIFF(SECOND, MIN(createTime), NOW()) FROM outbox_event WHERE status IN (0, 1)")
    Long oldestPendingWaitSeconds();

    /** 永久失败事件数（需要人工处理） */
    @Select("SELECT COUNT(*) FROM outbox_event WHERE status = 3")
    long countDead();
}
