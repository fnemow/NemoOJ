CREATE TABLE ai_model_call (
    id CHAR(36) NOT NULL PRIMARY KEY,
    taskId CHAR(36) NOT NULL,
    workerId VARCHAR(128) NOT NULL,
    leaseAttempt INT NOT NULL,
    usageDate DATE NOT NULL COMMENT '本次调用开始时的 Asia/Shanghai 日期',
    modelId VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL COMMENT 'STARTED/KNOWN/UNKNOWN；UNKNOWN 保留预算',
    inputTokens BIGINT NOT NULL DEFAULT 0,
    outputTokens BIGINT NOT NULL DEFAULT 0,
    estimatedCost DECIMAL(18,8) NOT NULL DEFAULT 0,
    reservedCost DECIMAL(18,8) NOT NULL DEFAULT 0,
    createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completedAt DATETIME NULL,
    KEY idx_model_call_task (taskId),
    KEY idx_model_call_budget (usageDate,state)
) COMMENT='逐次模型调用的估算费用与不确定费用预留，不含提示词或用户代码'
COLLATE=utf8mb4_unicode_ci;
