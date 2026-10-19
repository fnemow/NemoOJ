-- ============================================================
-- V5 题目不可变版本快照（T07）
--
-- 目的：让"判题依据"在提交那一刻就固定下来。
-- 修改前提交只记录题目版本号（problemVersionId），判题时却去读**当前**题目，
-- 于是"提交后改题"会让历史提交按新数据重判，结果与当初不一致且无法复现。
--
-- 规则：
--   * 每次发布生成一个新的、**不可变**的版本行；
--   * 已发布版本不得原地修改，编辑后再次发布产生新版本；
--   * 提交绑定具体版本，判题只读该版本；
--   * 删除题目不删除版本行——历史判题依据必须保留。
--
-- 并发：唯一键 uk_question_version(questionId, version) 是版本号的数据库约束，
--       并发发布时冲突方会收到唯一键错误并重试，不会产生两个相同版本号。
-- ============================================================

CREATE TABLE IF NOT EXISTS question_version
(
    id             bigint AUTO_INCREMENT COMMENT 'id' PRIMARY KEY,
    questionId     bigint       NOT NULL COMMENT '题目 id',
    version        int          NOT NULL COMMENT '版本号，同一题目内唯一且递增',
    title          varchar(512) NULL COMMENT '标题（发布时快照）',
    content        text         NULL COMMENT '题面（发布时快照）',
    tags           varchar(1024) NULL COMMENT '标签（发布时快照）',
    answer         text         NULL COMMENT '参考答案（发布时快照）',
    judgeCase      text         NULL COMMENT '判题用例（发布时快照，含隐藏用例）',
    judgeConfig    text         NULL COMMENT '判题配置：时间/内存限制（发布时快照）',
    problemSpec    text         NULL COMMENT '判题规格：函数签名、比较器等（发布时快照）',
    harnessVersion int          NULL COMMENT 'Runner/harness 版本',
    publishedBy    bigint       NULL COMMENT '发布人 id',
    createTime     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    UNIQUE KEY uk_question_version (questionId, version),
    KEY idx_qv_question (questionId)
) COMMENT '题目版本快照（不可变）' COLLATE = utf8mb4_unicode_ci;

-- 标记"无法精确复现"的历史提交。
-- V5 之前产生的提交没有对应的版本行，其判题依据已不可还原；
-- 必须如实标记，不能伪造一个快照冒充原始版本。
ALTER TABLE question_submit
    ADD COLUMN versionReproducible tinyint NOT NULL DEFAULT 0
        COMMENT '绑定的题目版本是否可精确复现（0 表示上线前的历史提交，已无法还原原始判题依据）';
