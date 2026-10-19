-- Additive migration: legacy plans, private versions and submissions are untouched.
ALTER TABLE ai_task ADD COLUMN generationRepairCount INT NOT NULL DEFAULT 0;
CREATE TABLE private_generation_batch (
 id CHAR(36) PRIMARY KEY, ownerUserId BIGINT NOT NULL, goal VARCHAR(1000) NOT NULL,
 idempotencyKey CHAR(36) NOT NULL, rowVersion BIGINT NOT NULL DEFAULT 1,
 createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_private_batch_request(ownerUserId,idempotencyKey)
) COLLATE=utf8mb4_unicode_ci;
CREATE TABLE private_generation_batch_item (
 batchId CHAR(36) NOT NULL, difficulty VARCHAR(8) NOT NULL,
 privateProblemId CHAR(36) NOT NULL, taskId CHAR(36) NOT NULL,
 retryKey CHAR(36) NULL, revision INT NOT NULL DEFAULT 1,
 PRIMARY KEY(batchId,difficulty), UNIQUE KEY uk_batch_task(taskId)
) COLLATE=utf8mb4_unicode_ci;
CREATE TABLE private_validation_job (
 id CHAR(36) PRIMARY KEY, taskId CHAR(36) NOT NULL, revision INT NOT NULL,
 draftHash CHAR(64) NOT NULL, draftJson MEDIUMTEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED', resultJson MEDIUMTEXT NULL,
 leaseToken CHAR(36) NULL, leaseExpireAt DATETIME NULL,
 attemptCount INT NOT NULL DEFAULT 0, nextAttemptAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 createTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, startedAt DATETIME NULL, completedAt DATETIME NULL,
 UNIQUE KEY uk_validation_revision(taskId,revision), KEY idx_validation_queue(status,nextAttemptAt)
) COLLATE=utf8mb4_unicode_ci;
