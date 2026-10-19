-- 管理后台：逻辑题目生命周期与用户反馈。
-- question.isPublished 继续表示“该行曾经发布，内容不可原地修改”；
-- 对外可见性统一由 question_public_number.status/currentQuestionId 决定。

ALTER TABLE question_public_number
    ADD COLUMN currentQuestionId BIGINT NULL COMMENT '当前对外版本的 question.id',
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ONLINE/OFFLINE',
    ADD COLUMN lifecycleVersion BIGINT NOT NULL DEFAULT 0 COMMENT '生命周期乐观锁',
    ADD COLUMN lastOperatorId BIGINT NULL COMMENT '最后操作管理员',
    ADD COLUMN offlineReason VARCHAR(512) NULL COMMENT '最近一次下线原因',
    ADD COLUMN updateTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD KEY idx_qpn_current (currentQuestionId),
    ADD KEY idx_qpn_status (status);

-- 每个自然题号选择最高已发布版本作为当前版本。没有发布版本的逻辑题保持 DRAFT。
UPDATE question_public_number p
LEFT JOIN question current_question ON current_question.id = (
    SELECT q.id
    FROM question q
    WHERE q.problemNo = p.problemNo
      AND q.isPublished = 1
      AND q.isDelete = 0
    ORDER BY q.problemVersion DESC, q.updateTime DESC, q.id DESC
    LIMIT 1
)
SET p.currentQuestionId = current_question.id,
    p.status = CASE WHEN current_question.id IS NULL THEN 'DRAFT' ELSE 'ONLINE' END;

CREATE TABLE feedback
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    userId         BIGINT       NOT NULL COMMENT '提交用户',
    type           VARCHAR(32)  NOT NULL COMMENT 'PROBLEM/BUG/SUGGESTION/OTHER',
    problemNo      BIGINT       NULL COMMENT '可选的自然题号',
    title          VARCHAR(120) NOT NULL COMMENT '反馈标题',
    content        TEXT         NOT NULL COMMENT '反馈正文',
    status         VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/RESOLVED',
    adminReply     TEXT         NULL COMMENT '管理员单次回复',
    handledBy      BIGINT       NULL COMMENT '最后处理管理员',
    handledAt      DATETIME     NULL COMMENT '解决时间',
    rowVersion     BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁',
    createTime     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    isDelete       TINYINT      NOT NULL DEFAULT 0,
    KEY idx_feedback_user_time (userId, createTime),
    KEY idx_feedback_status_time (status, createTime),
    KEY idx_feedback_problem (problemNo)
) COMMENT='用户反馈工单' COLLATE=utf8mb4_unicode_ci;
