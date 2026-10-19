-- AI membership, asynchronous task ledger, quota reservations and result tables.
-- Keep AI writes isolated from the legacy user role and public problem catalog.

CREATE TABLE user_membership
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    userId      BIGINT       NOT NULL,
    tier        VARCHAR(16)  NOT NULL DEFAULT 'FREE',
    validFrom   DATETIME     NULL,
    validUntil  DATETIME     NULL,
    updatedBy   VARCHAR(64)  NOT NULL,
    rowVersion  BIGINT       NOT NULL DEFAULT 0,
    createTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_membership_user (userId),
    KEY idx_membership_expiry (tier, validUntil)
) COMMENT='独立会员权益，不改变用户角色' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_task
(
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    type            VARCHAR(32)  NOT NULL COMMENT 'ANALYSIS/PLAN/PLAN_ADJUST/PRIVATE_PROBLEM/FEEDBACK/INSPECTION',
    ownerUserId     BIGINT       NULL,
    resourceId      VARCHAR(64)  NULL,
    idempotencyKey  VARCHAR(64)  NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
    stage           VARCHAR(48)  NULL,
    attemptCount    INT          NOT NULL DEFAULT 0,
    leaseOwner      VARCHAR(128) NULL,
    leaseExpireAt   DATETIME     NULL,
    errorCode       VARCHAR(48)  NULL,
    errorMessage    VARCHAR(512) NULL,
    modelId         VARCHAR(128) NULL,
    promptVersion   VARCHAR(64)  NULL,
    inputTokens     BIGINT       NOT NULL DEFAULT 0,
    outputTokens    BIGINT       NOT NULL DEFAULT 0,
    estimatedCost   DECIMAL(18,8) NOT NULL DEFAULT 0,
    createTime      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    startedAt       DATETIME     NULL,
    completedAt     DATETIME     NULL,
    updateTime      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_task_idempotency (ownerUserId, type, idempotencyKey),
    KEY idx_ai_task_queue (status, createTime),
    KEY idx_ai_task_owner_time (ownerUserId, createTime),
    KEY idx_ai_task_lease (status, leaseExpireAt)
) COMMENT='AI 异步任务' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_usage
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    taskId         CHAR(36)     NOT NULL,
    userId         BIGINT       NOT NULL,
    usageDate      DATE         NOT NULL COMMENT 'Asia/Shanghai 自然日，创建任务时固化',
    category       VARCHAR(32)  NOT NULL,
    state          VARCHAR(16)  NOT NULL COMMENT 'RESERVED/COMMITTED/RELEASED',
    quotaExempt    TINYINT      NOT NULL DEFAULT 0 COMMENT '创建任务时是否为有效 VIP',
    inputTokens    BIGINT       NOT NULL DEFAULT 0,
    outputTokens   BIGINT       NOT NULL DEFAULT 0,
    estimatedCost  DECIMAL(18,8) NOT NULL DEFAULT 0,
    reservedCost   DECIMAL(18,8) NOT NULL DEFAULT 0 COMMENT '执行前按单任务上限预占的预算',
    createTime     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_usage_task (taskId),
    KEY idx_ai_usage_quota (userId, usageDate, state)
) COMMENT='AI 用量与额度预占' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_analysis
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    submissionId    BIGINT       NOT NULL,
    userId          BIGINT       NOT NULL,
    sourceHash      CHAR(64)     NOT NULL,
    verdict         VARCHAR(32)  NOT NULL,
    reportJson      MEDIUMTEXT   NOT NULL,
    modelId         VARCHAR(128) NOT NULL,
    promptVersion   VARCHAR(64)  NOT NULL,
    createTime      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_analysis_submission (submissionId),
    KEY idx_ai_analysis_user_time (userId, createTime)
) COMMENT='提交代码 AI 分析结果' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_outbox
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    taskId        CHAR(36)     NOT NULL,
    eventType     VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retryCount    INT          NOT NULL DEFAULT 0,
    nextAttemptAt DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    leaseExpireAt DATETIME     NULL,
    lastError     VARCHAR(512) NULL,
    createTime    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_outbox_task_type (taskId, eventType),
    KEY idx_ai_outbox_due (status, nextAttemptAt)
) COMMENT='AI 消息事务发件箱' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_action_audit
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    taskId        CHAR(36)     NULL,
    actorType     VARCHAR(16)  NOT NULL COMMENT 'USER/ADMIN/AI/OPS',
    actorId       VARCHAR(64)  NULL,
    action        VARCHAR(64)  NOT NULL,
    resourceType  VARCHAR(32)  NULL,
    resourceId    VARCHAR(64)  NULL,
    inputHash     CHAR(64)     NULL,
    outcome       VARCHAR(24)  NOT NULL,
    detail        VARCHAR(1000) NULL,
    createTime    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_audit_task (taskId, createTime),
    KEY idx_ai_audit_resource (resourceType, resourceId, createTime)
) COMMENT='AI 与运维操作审计，不存完整代码或密钥' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_notification_outbox
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    dedupeKey       VARCHAR(160) NOT NULL,
    recipient       VARCHAR(320) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    bodyText        TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retryCount      INT          NOT NULL DEFAULT 0,
    nextAttemptAt   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastError       VARCHAR(512) NULL,
    sentAt          DATETIME     NULL,
    createTime      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_notification_dedupe (dedupeKey),
    KEY idx_ai_notification_due (status, nextAttemptAt)
) COMMENT='人工升级邮件发件箱' COLLATE=utf8mb4_unicode_ci;
