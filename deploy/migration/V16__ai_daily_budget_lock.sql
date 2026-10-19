-- Serialize reservations across users and AI task categories for each business day.
CREATE TABLE ai_daily_budget_lock
(
    usageDate DATE NOT NULL PRIMARY KEY
) COMMENT='AI 平台每日预算预留事务锁' COLLATE=utf8mb4_unicode_ci;
