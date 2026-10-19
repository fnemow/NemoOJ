-- ============================================================
-- V1 基线：NemoOJ 微服务初始表结构
--
-- 适用对象：**新库**。存量库不要执行本文件，直接用
--   apply_migrations.sh --baseline-through V1
-- 把 V1 标记为已应用（存量库的表结构等价于本文件内容）。
--
-- 本文件是当前表结构的完整快照，由 backend/sql/create_table.sql 整理而来：
-- 原脚本中的 "Phase 2 增量" 段落（problemSpec / problemVersion / isPublished /
-- problemVersionId / harnessVersion）已并入建表语句，新库无需再单独执行增量。
--
-- 约定：不使用 DELIMITER / 存储过程，保证 mysql CLI 与 JDBC 均可执行。
-- ============================================================

-- 用户表
CREATE TABLE IF NOT EXISTS `user`
(
    id           bigint AUTO_INCREMENT COMMENT 'id' PRIMARY KEY,
    userAccount  varchar(256)                           NOT NULL COMMENT '账号',
    userPassword varchar(512)                           NOT NULL COMMENT '密码',
    unionId      varchar(256)                           NULL COMMENT '微信开放平台id',
    mpOpenId     varchar(256)                           NULL COMMENT '公众号openId',
    userName     varchar(256)                           NULL COMMENT '用户昵称',
    userAvatar   varchar(1024)                          NULL COMMENT '用户头像',
    userProfile  varchar(512)                           NULL COMMENT '用户简介',
    userRole     varchar(256) DEFAULT 'user'            NOT NULL COMMENT '用户角色：user/admin/ban',
    createTime   datetime     DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    updateTime   datetime     DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete     tinyint      DEFAULT 0                 NOT NULL COMMENT '是否删除',
    INDEX idx_unionId (unionId)
) COMMENT '用户' COLLATE = utf8mb4_unicode_ci;

-- 题目表
CREATE TABLE IF NOT EXISTS question
(
    id             bigint AUTO_INCREMENT COMMENT 'id' PRIMARY KEY,
    title          varchar(512)                       NULL COMMENT '标题',
    content        text                               NULL COMMENT '内容',
    tags           varchar(1024)                      NULL COMMENT '标签列表（json 数组）',
    answer         text                               NULL COMMENT '题目答案',
    submitNum      int      DEFAULT 0                 NOT NULL COMMENT '题目提交数',
    acceptedNum    int      DEFAULT 0                 NOT NULL COMMENT '题目通过数',
    judgeCase      text                               NULL COMMENT '判题用例（json 数组）',
    judgeConfig    text                               NULL COMMENT '判题配置（json 对象）',
    problemSpec    text                               NULL COMMENT '判题规格（核心代码模式函数签名等, json 对象）',
    problemVersion int      DEFAULT 1                 NULL COMMENT '题目版本号',
    isPublished    tinyint  DEFAULT 0                 NULL COMMENT '是否已发布',
    rowVersion     bigint   DEFAULT 0                 NOT NULL COMMENT 'optimistic edit version',
    sourceQuestionId bigint                            NULL COMMENT 'published source for a new-version draft',
    thumbNum       int      DEFAULT 0                 NOT NULL COMMENT '点赞数',
    favourNum      int      DEFAULT 0                 NOT NULL COMMENT '收藏数',
    userId         bigint                             NOT NULL COMMENT '创建用户 id',
    createTime     datetime DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    updateTime     datetime DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete       tinyint  DEFAULT 0                 NOT NULL COMMENT '是否删除',
    INDEX idx_userId (userId)
    ,INDEX idx_question_source (sourceQuestionId)
) COMMENT '题目' COLLATE = utf8mb4_unicode_ci;

-- 题目提交表
CREATE TABLE IF NOT EXISTS question_submit
(
    id               bigint AUTO_INCREMENT COMMENT 'id' PRIMARY KEY,
    language         varchar(128)                       NOT NULL COMMENT '编程语言',
    code             text                               NOT NULL COMMENT '用户代码',
    judgeInfo        text                               NULL COMMENT '判题信息（json 对象）',
    status           int      DEFAULT 0                 NOT NULL COMMENT '判题状态（0 - 待判题、1 - 判题中、2 - 成功、3 - 失败）',
    problemVersionId int      DEFAULT 1                 NULL COMMENT '提交时刻的题目版本号',
    harnessVersion   int      DEFAULT 1                 NULL COMMENT '提交时刻的 Runner 版本号',
    questionId       bigint                             NOT NULL COMMENT '题目 id',
    userId           bigint                             NOT NULL COMMENT '创建用户 id',
    clientRequestId  varchar(36)                        NULL COMMENT 'client-generated idempotency UUID',
    requestHash      varchar(64)                        NULL COMMENT 'submission snapshot SHA-256',
    createTime       datetime DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    updateTime       datetime DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    isDelete         tinyint  DEFAULT 0                 NOT NULL COMMENT '是否删除',
    INDEX idx_questionId (questionId),
    INDEX idx_userId (userId),
    UNIQUE KEY uk_submit_user_request (userId, clientRequestId)
) COMMENT '题目提交';
