-- Invitations are mandatory for the public registration endpoint.
-- Only SHA-256 digests are persisted; the plaintext is returned once on creation.
CREATE TABLE registration_invitation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    codeHash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    note VARCHAR(120) NOT NULL DEFAULT '',
    createdBy BIGINT NULL COMMENT 'NULL only for host-side administrator bootstrap',
    expiresAt DATETIME NOT NULL COMMENT 'UTC',
    usedBy BIGINT NULL,
    usedAt DATETIME NULL COMMENT 'UTC',
    revokedAt DATETIME NULL COMMENT 'UTC',
    createTime DATETIME NOT NULL COMMENT 'UTC',
    UNIQUE KEY uk_invitation_code_hash (codeHash),
    KEY idx_invitation_created (createTime, id)
) COLLATE=utf8mb4_unicode_ci;
