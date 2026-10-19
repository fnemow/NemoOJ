-- Learning plans, private problems and human-reviewed maintenance proposals.

CREATE TABLE study_plan
(
    id             CHAR(36)     NOT NULL PRIMARY KEY,
    userId         BIGINT       NOT NULL,
    title          VARCHAR(160) NOT NULL,
    goal           VARCHAR(1000) NOT NULL,
    level          VARCHAR(32)  NOT NULL,
    tagsJson       TEXT         NOT NULL,
    days           INT          NOT NULL,
    minutesPerDay  INT          NOT NULL,
    sourceMode     VARCHAR(16)  NOT NULL DEFAULT 'CATALOG',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED/NEEDS_REVIEW',
    planVersion    BIGINT       NOT NULL DEFAULT 1,
    createTime     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_study_plan_owner_status (userId, status, updateTime)
) COMMENT='用户个性化学习计划' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE study_plan_item
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    planId               CHAR(36)     NOT NULL,
    dayNumber            INT          NOT NULL,
    orderNumber          INT          NOT NULL,
    source               VARCHAR(16)  NOT NULL COMMENT 'PUBLIC/PRIVATE',
    problemNo            BIGINT       NULL,
    publicQuestionId     BIGINT       NULL,
    publicVersion        INT          NULL,
    privateProblemId     CHAR(36)     NULL,
    privateVersionId     CHAR(36)     NULL,
    titleSnapshot        VARCHAR(512) NOT NULL,
    reason               VARCHAR(1000) NOT NULL,
    completedBySubmitId  BIGINT       NULL,
    completedAt          DATETIME     NULL,
    itemStatus           VARCHAR(16)  NOT NULL DEFAULT 'TODO' COMMENT 'TODO/DONE/UNAVAILABLE',
    createTime           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_item_order (planId, dayNumber, orderNumber),
    KEY idx_plan_items (planId, itemStatus),
    KEY idx_plan_public_binding (publicQuestionId, publicVersion),
    KEY idx_plan_private_binding (privateProblemId, privateVersionId)
) COMMENT='计划每日任务与题目版本绑定' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE private_problem
(
    id                CHAR(36)     NOT NULL PRIMARY KEY,
    ownerUserId       BIGINT       NOT NULL,
    title             VARCHAR(512) NOT NULL,
    tagsJson          TEXT         NOT NULL,
    difficulty        VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'GENERATING' COMMENT 'GENERATING/READY/REJECTED/ARCHIVED',
    currentVersionId  CHAR(36)     NULL,
    generationTaskId  CHAR(36)     NOT NULL,
    rejectReason      VARCHAR(1000) NULL,
    createTime        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_private_problem_owner (ownerUserId, status, createTime),
    KEY idx_private_problem_task (generationTaskId)
) COMMENT='仅所有者可见的私人题逻辑记录' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE private_problem_version
(
    id                 CHAR(36)     NOT NULL PRIMARY KEY,
    privateProblemId   CHAR(36)     NOT NULL,
    versionNumber      INT          NOT NULL,
    contentMarkdown    MEDIUMTEXT   NOT NULL,
    sampleJson         MEDIUMTEXT   NOT NULL,
    problemSpecJson    MEDIUMTEXT   NOT NULL,
    judgeCaseJson      MEDIUMTEXT   NOT NULL COMMENT '隐藏判题用例，不返回给浏览器或分析提示词',
    judgeConfigJson    TEXT         NOT NULL,
    referenceJson      MEDIUMTEXT   NOT NULL COMMENT '服务端参考代码',
    validationJson    MEDIUMTEXT   NOT NULL,
    contentHash        CHAR(64)     NOT NULL,
    status             VARCHAR(16)  NOT NULL COMMENT 'VALIDATING/READY/REJECTED',
    createTime         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_private_problem_version (privateProblemId, versionNumber),
    KEY idx_private_problem_version_hash (contentHash)
) COMMENT='私人题不可变判题版本' COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_maintenance_proposal
(
    id                     CHAR(36)     NOT NULL PRIMARY KEY,
    taskId                 CHAR(36)     NOT NULL,
    feedbackId             BIGINT       NULL,
    problemNo              BIGINT       NOT NULL,
    baseQuestionId         BIGINT       NOT NULL,
    baseProblemVersion     INT          NOT NULL,
    baseLifecycleVersion   BIGINT       NOT NULL,
    proposalJson           MEDIUMTEXT   NOT NULL,
    proposalHash           CHAR(64)     NOT NULL,
    diffMarkdown           MEDIUMTEXT   NOT NULL,
    evidenceJson           MEDIUMTEXT   NOT NULL,
    validationJson         MEDIUMTEXT   NOT NULL,
    status                 VARCHAR(24)  NOT NULL DEFAULT 'WAITING_REVIEW',
    rowVersion             BIGINT       NOT NULL DEFAULT 0,
    reviewedBy             BIGINT       NULL,
    reviewedAt             DATETIME     NULL,
    reviewNote             VARCHAR(1000) NULL,
    publishedQuestionId    BIGINT       NULL,
    createTime             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updateTime             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_ai_proposal_review (status, createTime),
    KEY idx_ai_proposal_problem (problemNo, createTime),
    KEY idx_ai_proposal_feedback (feedbackId)
) COMMENT='公共题库 AI 修改建议，审核前不写入正式题目' COLLATE=utf8mb4_unicode_ci;

ALTER TABLE question_submit
    ADD COLUMN problemSource VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/PRIVATE' AFTER questionId,
    ADD COLUMN privateProblemVersionId CHAR(36) NULL COMMENT '私人题版本快照' AFTER problemSource,
    MODIFY COLUMN questionId BIGINT NULL COMMENT '公共题 question.id；私人题提交为空',
    ADD KEY idx_submit_private_owner (problemSource, userId, privateProblemVersionId, createTime);

ALTER TABLE feedback
    ADD COLUMN agentStatus VARCHAR(24) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/AUTO_REPLIED/ESCALATED/TAKEN_OVER',
    ADD COLUMN replySource VARCHAR(16) NULL COMMENT 'ADMIN/AI',
    ADD COLUMN agentTaskId CHAR(36) NULL,
    ADD KEY idx_feedback_agent_status (agentStatus, updateTime);

-- Do not create a partial unique constraint through a generated active-plan
-- slot: creation takes a transaction-scoped lock on the user row, then checks
-- ACTIVE plans before inserting. The service must enforce this invariant.
