-- ============================================================
-- 重复账号检测报告（只读，可随时在生产库执行）
--
-- 用途：V2 要为 `user`.userAccount 加唯一约束。若存量数据里已有重复账号，
-- 加约束这一步会失败。**先跑本脚本拿到冲突清单，人工决定怎么处理，再执行 V2。**
--
-- 本脚本不修改任何数据。冲突账号的处理方案见 README.md「重复账号处理」。
--
-- 注意：比较使用列自身的排序规则 utf8mb4_unicode_ci——大小写不敏感、
-- 且忽略尾部空格。也就是说 'Alice' 与 'alice'、'abc' 与 'abc ' 都算重复。
-- 这与应用层现有的查询行为一致（同一排序规则），不是本脚本额外引入的规则。
-- ============================================================

-- 1) 冲突汇总：哪些账号重复、各重复几条（含软删除记录）
SELECT userAccount                          AS 重复账号,
       COUNT(*)                             AS 记录数,
       SUM(isDelete = 0)                    AS 有效记录数,
       SUM(isDelete = 1)                    AS 已软删除数,
       GROUP_CONCAT(id ORDER BY id)         AS 涉及id,
       MIN(createTime)                      AS 最早创建时间
FROM `user`
GROUP BY userAccount
HAVING COUNT(*) > 1
ORDER BY 记录数 DESC, 重复账号;

-- 2) 冲突明细：逐条列出，便于人工判断保留哪一条
SELECT u.id,
       u.userAccount,
       u.userRole,
       u.isDelete,
       u.createTime,
       (SELECT COUNT(*) FROM question_submit qs WHERE qs.userId = u.id) AS 提交数,
       (SELECT COUNT(*) FROM question     q  WHERE q.userId  = u.id)    AS 题目数
FROM `user` u
WHERE u.userAccount IN (
    SELECT userAccount FROM (
        SELECT userAccount FROM `user` GROUP BY userAccount HAVING COUNT(*) > 1
    ) AS dup
)
ORDER BY u.userAccount, u.id;

-- 3) 结论行：duplicate_account_count = 0 才可以执行 V2
SELECT COUNT(*) AS duplicate_account_count
FROM (
    SELECT userAccount FROM `user` GROUP BY userAccount HAVING COUNT(*) > 1
) AS dup;
