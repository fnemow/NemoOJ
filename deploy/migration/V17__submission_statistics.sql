-- Stop submission writers for this migration, including judge and maintenance jobs.
-- The counters belong to immutable version IDs; public reads sum across problemNo.
CREATE TABLE question_submission_stats (
    questionId BIGINT NOT NULL PRIMARY KEY,
    submitNum BIGINT NOT NULL DEFAULT 0,
    acceptedNum BIGINT NOT NULL DEFAULT 0,
    CHECK (submitNum >= 0 AND acceptedNum >= 0 AND acceptedNum <= submitNum)
);

INSERT INTO question_submission_stats (questionId, submitNum, acceptedNum)
SELECT questionId, COUNT(*),
       SUM(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(judgeInfo, '$.message')) = 'accepted', 0))
FROM question_submit WHERE isDelete = 0 AND questionId IS NOT NULL GROUP BY questionId;

DELIMITER $$
CREATE TRIGGER submission_stats_insert AFTER INSERT ON question_submit FOR EACH ROW
BEGIN
    IF NEW.isDelete = 0 AND NEW.questionId IS NOT NULL THEN
        INSERT INTO question_submission_stats (questionId, submitNum, acceptedNum)
        VALUES (NEW.questionId, 1, COALESCE(JSON_UNQUOTE(JSON_EXTRACT(NEW.judgeInfo, '$.message')) = 'accepted', 0))
        ON DUPLICATE KEY UPDATE submitNum = submitNum + 1, acceptedNum = acceptedNum + VALUES(acceptedNum);
    END IF;
END$$

CREATE TRIGGER submission_stats_update AFTER UPDATE ON question_submit FOR EACH ROW
BEGIN
    -- Lease heartbeats and duplicate terminal writes do not touch counters.
    IF NOT (OLD.questionId <=> NEW.questionId) OR NOT (OLD.isDelete <=> NEW.isDelete)
       OR NOT (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(OLD.judgeInfo, '$.message')) = 'accepted', 0)
            <=> COALESCE(JSON_UNQUOTE(JSON_EXTRACT(NEW.judgeInfo, '$.message')) = 'accepted', 0)) THEN
        IF OLD.isDelete = 0 AND OLD.questionId IS NOT NULL THEN
            UPDATE question_submission_stats SET submitNum = submitNum - 1,
                acceptedNum = acceptedNum - COALESCE(JSON_UNQUOTE(JSON_EXTRACT(OLD.judgeInfo, '$.message')) = 'accepted', 0)
            WHERE questionId = OLD.questionId;
        END IF;
        IF NEW.isDelete = 0 AND NEW.questionId IS NOT NULL THEN
            INSERT INTO question_submission_stats (questionId, submitNum, acceptedNum)
            VALUES (NEW.questionId, 1, COALESCE(JSON_UNQUOTE(JSON_EXTRACT(NEW.judgeInfo, '$.message')) = 'accepted', 0))
            ON DUPLICATE KEY UPDATE submitNum = submitNum + 1, acceptedNum = acceptedNum + VALUES(acceptedNum);
        END IF;
    END IF;
END$$

CREATE TRIGGER submission_stats_delete AFTER DELETE ON question_submit FOR EACH ROW
BEGIN
    IF OLD.isDelete = 0 AND OLD.questionId IS NOT NULL THEN
        UPDATE question_submission_stats SET submitNum = submitNum - 1,
            acceptedNum = acceptedNum - COALESCE(JSON_UNQUOTE(JSON_EXTRACT(OLD.judgeInfo, '$.message')) = 'accepted', 0)
        WHERE questionId = OLD.questionId;
    END IF;
END$$
DELIMITER ;
