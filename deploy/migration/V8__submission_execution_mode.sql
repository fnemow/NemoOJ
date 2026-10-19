-- Persist the mode selected in the editor so judge workers execute the immutable submission contract.
-- Existing rows stay NULL so judge code can retain the historical inference from the question snapshot.
ALTER TABLE question_submit
    ADD COLUMN executionMode varchar(16) NULL
        COMMENT '执行模式（ACM/FUNCTION）；NULL 仅用于历史兼容' AFTER language;
