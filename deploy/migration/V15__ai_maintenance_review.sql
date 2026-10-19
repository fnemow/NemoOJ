-- Track the unpublished question draft created after an administrator approves
-- an editorial-only Agent proposal. The public version remains unchanged until
-- the administrator reviews and publishes that draft through the normal flow.
ALTER TABLE ai_maintenance_proposal
    ADD COLUMN approvedDraftId BIGINT NULL AFTER reviewNote,
    ADD KEY idx_ai_proposal_approved_draft (approvedDraftId);
