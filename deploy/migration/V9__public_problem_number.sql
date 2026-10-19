ALTER TABLE question ADD COLUMN problemNo BIGINT NULL COMMENT 'stable public problem number';
CREATE TABLE IF NOT EXISTS question_public_number (
    problemNo BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    questionId BIGINT NOT NULL,
    UNIQUE KEY uk_question_public_number_question (questionId)
) COMMENT='public natural-number allocation for logical questions';
INSERT INTO question_public_number (questionId)
SELECT q.id FROM question q
LEFT JOIN question qsrc ON qsrc.id = q.sourceQuestionId
WHERE q.isDelete = 0 AND qsrc.id IS NULL
ORDER BY q.createTime, q.id;
UPDATE question q JOIN question_public_number n ON n.questionId = q.id
SET q.problemNo = n.problemNo WHERE q.isDelete = 0 AND q.sourceQuestionId IS NULL;
UPDATE question child JOIN question parent ON parent.id = child.sourceQuestionId
SET child.problemNo = parent.problemNo WHERE child.isDelete = 0 AND child.problemNo IS NULL;
CREATE INDEX idx_question_problem_no ON question(problemNo);
