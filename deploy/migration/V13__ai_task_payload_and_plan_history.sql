-- Persist narrowly-scoped task input/output and immutable plan revision snapshots.
ALTER TABLE ai_task
    ADD COLUMN requestJson MEDIUMTEXT NULL COMMENT 'Validated business request; never contains source code',
    ADD COLUMN resultJson MEDIUMTEXT NULL COMMENT 'Validated task result before UI projection';

CREATE TABLE study_plan_revision
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    planId         CHAR(36) NOT NULL,
    planVersion    BIGINT NOT NULL,
    itemsJson      MEDIUMTEXT NOT NULL COMMENT 'Snapshot of items when a plan revision is replaced',
    changeRequest  VARCHAR(1000) NULL,
    taskId         CHAR(36) NULL,
    createTime     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_revision (planId, planVersion),
    KEY idx_plan_revision_task (taskId)
) COMMENT='学习计划变更历史' COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ai_notification_status_due ON ai_notification_outbox(status, nextAttemptAt);
