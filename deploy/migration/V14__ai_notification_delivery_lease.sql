-- Recoverable claim lease for concurrent/crashed SMTP dispatchers.
ALTER TABLE ai_notification_outbox
    ADD COLUMN leaseExpireAt DATETIME NULL AFTER retryCount,
    ADD KEY idx_ai_notification_lease (status, leaseExpireAt);
