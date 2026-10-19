-- ============================================================
-- V4 判题执行租约与执行代次（T06）
--
-- 目的：让"谁在执行这次判题"变成可验证的事实，解决三类问题：
--   1. 消费者崩溃后任务永久卡在"判题中"，没有接管机制；
--   2. 旧消费者迟到写回，覆盖新一轮执行或已完成的结果；
--   3. 反复失败的任务没有次数上限，也没有失败原因留痕。
--
-- 对外状态值**不变**（0 待判题 / 1 判题中 / 2 成功 / 3 失败），
-- 新增字段都是内部执行控制信息。
-- ============================================================

ALTER TABLE question_submit
    -- 执行代次令牌：每次抢占生成新值。所有写回都必须带上它，
    -- 令牌不匹配说明本执行已被接管或已完成，写入必须拒绝。
    ADD COLUMN executionToken varchar(64) NULL COMMENT '当前执行代次令牌',
    -- 租约到期时间：执行方需在到期前续租，否则视为失联可被回收
    ADD COLUMN leaseExpireAt  datetime    NULL COMMENT '执行租约到期时间',
    -- 执行次数：超过上限进入明确终态，不再无限重试
    ADD COLUMN attemptCount   int         NOT NULL DEFAULT 0 COMMENT '已执行次数',
    -- 最后一次失败原因，便于人工判断
    ADD COLUMN lastError      varchar(512) NULL COMMENT '最后一次执行失败原因';

-- 回收器按"判题中且租约已过期"扫描，需要索引避免全表扫描
ALTER TABLE question_submit
    ADD INDEX idx_submit_lease (status, leaseExpireAt);
