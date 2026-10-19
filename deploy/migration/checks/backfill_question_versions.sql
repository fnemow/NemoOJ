-- ============================================================
-- V5 之后：为存量已发布题目补生成版本快照
--
-- 背景：V5 之前发布的题目没有 question_version 行，其提交会因
--       "题目已发布但没有版本号"被拒绝。
--
-- **这是不可逆的数据写入，请先备份，并在预发环境演练。**
--
-- 重要说明：补生成的快照记录的是**当前**题目内容，不是历史上某个时刻的内容。
--   对 V5 之前已存在的历史提交而言，其真实判题依据已不可还原——这些提交的
--   versionReproducible 仍是 0，不会被本脚本"修好"，也不应该被伪造。
--   本脚本只解决"存量题目今后能否被提交"的问题。
--
-- 执行顺序：
--   1) 先跑「检测」段，确认待补数量；
--   2) 在预发环境执行「补发布」段并核对结果；
--   3) 生产执行前备份。
-- ============================================================

-- ---------- 1) 检测：哪些已发布题目缺少版本快照 ----------
SELECT q.id,
       q.title,
       q.problemVersion,
       q.isPublished,
       (SELECT COUNT(*) FROM question_version v WHERE v.questionId = q.id) AS version_rows,
       (SELECT COUNT(*) FROM question_submit s WHERE s.questionId = q.id) AS submit_rows
FROM question q
WHERE q.isDelete = 0
  AND q.isPublished = 1
  AND NOT EXISTS (SELECT 1 FROM question_version v WHERE v.questionId = q.id)
ORDER BY q.id;

-- ---------- 2) 补发布 ----------
-- 为缺少快照的已发布题目插入 version = 1 的快照（内容取自当前题目），
-- 并把题目的 problemVersion 同步为 1。
--
-- 注意：题目表与版本表的字段名不同（judgeCase / judgeConfig / problemSpec 在两边同名），
-- 这里直接按列复制，不需要应用层参与。
INSERT INTO question_version
    (questionId, version, title, content, tags, answer,
     judgeCase, judgeConfig, problemSpec, harnessVersion, publishedBy, createTime)
SELECT q.id,
       1,
       q.title,
       q.content,
       q.tags,
       q.answer,
       q.judgeCase,
       q.judgeConfig,
       q.problemSpec,
       -- harnessVersion 存放在 problemSpec 的 JSON 内，无法用纯 SQL 可靠提取，
       -- 置为 1（与建表默认一致）。若需要精确值，应由应用侧发布接口补发。
       1,
       q.userId,
       NOW()
FROM question q
WHERE q.isDelete = 0
  AND q.isPublished = 1
  AND NOT EXISTS (SELECT 1 FROM question_version v WHERE v.questionId = q.id);

-- 同步题目上的版本号
UPDATE question q
SET q.problemVersion = 1
WHERE q.isDelete = 0
  AND q.isPublished = 1
  AND q.problemVersion IS NULL;

-- ---------- 3) 复核：确认没有遗漏 ----------
SELECT COUNT(*) AS still_missing
FROM question q
WHERE q.isDelete = 0
  AND q.isPublished = 1
  AND NOT EXISTS (SELECT 1 FROM question_version v WHERE v.questionId = q.id);

-- ---------- 4) 历史提交的可复现性如实统计（不修改，仅报告） ----------
SELECT versionReproducible, COUNT(*) AS submits
FROM question_submit
WHERE isDelete = 0
GROUP BY versionReproducible;
