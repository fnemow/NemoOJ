-- Internal evidence only. No reference code or hidden output is exposed to users.
CREATE TABLE IF NOT EXISTS private_validation_checkpoint (
 validationId CHAR(36) NOT NULL,
 executionHash CHAR(64) NOT NULL,
 evidenceJson MEDIUMTEXT NOT NULL,
 expiresAt DATETIME NOT NULL,
 PRIMARY KEY(validationId,executionHash), KEY idx_checkpoint_expiry(expiresAt)
) COLLATE=utf8mb4_unicode_ci;
