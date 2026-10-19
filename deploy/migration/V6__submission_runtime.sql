-- Additive migration; existing submissions retain NULL (runtime not historically recorded).
ALTER TABLE question_submit ADD COLUMN runtimeMetadata LONGTEXT NULL COMMENT 'Immutable internal runtime capability snapshot at acceptance';
