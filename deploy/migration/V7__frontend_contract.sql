-- Frontend P0 contracts: idempotent submission and optimistic admin editing.
ALTER TABLE question_submit
    ADD COLUMN clientRequestId varchar(36) NULL COMMENT 'client-generated idempotency UUID',
    ADD COLUMN requestHash varchar(64) NULL COMMENT 'SHA-256 of immutable submission snapshot',
    ADD UNIQUE KEY uk_submit_user_request (userId, clientRequestId);

ALTER TABLE question
    ADD COLUMN rowVersion bigint NOT NULL DEFAULT 0 COMMENT 'optimistic edit version',
    ADD COLUMN sourceQuestionId bigint NULL COMMENT 'published source for a new-version draft',
    ADD KEY idx_question_source (sourceQuestionId);
