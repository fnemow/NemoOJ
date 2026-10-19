-- ============================================================
-- V3 事务性发件箱（T05 可靠消息投递）
--
-- 目的：把"提交落库"与"消息待投递"放进同一个数据库事务，
--       消除"提交入库成功但消息没发出去"的静默丢任务窗口。
--
-- 投递语义：至少一次（at-least-once）。消费者必须幂等。
--           不宣称恰好一次——发布确认之后、标记已投递之前仍可能崩溃，
--           此时消息会被重发，这是设计上接受的代价。
-- ============================================================

CREATE TABLE IF NOT EXISTS outbox_event
(
    id            bigint AUTO_INCREMENT COMMENT 'id' PRIMARY KEY,
    eventId       varchar(64)  NOT NULL COMMENT '事件唯一 id（随机串），同时作为消费者幂等键',
    -- 业务 id 单独存为数值列，不要用 CAST 拼字符串去关联业务表：
    -- varchar 与 CAST(bigint AS CHAR) 的排序规则可能不一致（本库为 utf8mb4_unicode_ci，
    -- 而连接默认 utf8mb4_0900_ai_ci），实际会报
    -- "Illegal mix of collations"，对账查询直接失败。
    bizId         bigint       NOT NULL COMMENT '关联的业务 id，如提交 id',
    eventType     varchar(64)  NOT NULL COMMENT '事件类型，如 JUDGE_SUBMIT',
    payload       text         NOT NULL COMMENT '消息体（JSON）',
    status        tinyint      NOT NULL DEFAULT 0 COMMENT '0=待投递 1=投递中 2=已投递 3=永久失败（需人工处理）',
    retryCount    int          NOT NULL DEFAULT 0 COMMENT '已尝试投递次数',
    nextAttemptAt datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可尝试时间（退避用）',
    leaseOwner    varchar(128) NULL COMMENT '投递租约持有者（含每轮唯一标记），防止多实例重复投递',
    leaseExpireAt datetime     NULL COMMENT '租约到期时间，到期后可被其它实例回收',
    lastError     varchar(512) NULL COMMENT '最后一次失败原因',
    createTime    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updateTime    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_outbox_eventId (eventId),
    -- 取待投递任务
    KEY idx_outbox_due (status, nextAttemptAt),
    -- 回收过期租约
    KEY idx_outbox_lease (status, leaseExpireAt),
    -- 对账：按业务 id 反查事件
    KEY idx_outbox_biz (bizId, eventType)
) COMMENT '事务性发件箱' COLLATE = utf8mb4_unicode_ci;
