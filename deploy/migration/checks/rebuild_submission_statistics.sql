-- Maintenance only: stop gateway, question, judge and other database writers first.
-- Audit with submission_statistics_audit.sql before and after. Never run concurrently
-- with submissions: the live triggers and this rebuild must not overlap.
START TRANSACTION;
DELETE FROM question_submission_stats;
INSERT INTO question_submission_stats(questionId, submitNum, acceptedNum)
SELECT questionId, COUNT(*),
    SUM(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(judgeInfo,'$.message'))='accepted',0))
FROM question_submit WHERE isDelete=0 AND questionId IS NOT NULL GROUP BY questionId;
COMMIT;
