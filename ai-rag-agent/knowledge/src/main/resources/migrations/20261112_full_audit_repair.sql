-- Run while the backend is stopped, after backing up the affected tables.
-- Old quiz ownership and custom interview skills are not guessed or backfilled.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'quiz_question' AND column_name = 'user_id') = 0,
    'ALTER TABLE quiz_question ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT ''生成者；旧题未知归属保持NULL''',
    'DO 0');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- These legacy tasks had no scheduled retry worker. Expose the existing manual retry action.
UPDATE resume SET status = 'FAILED', error_msg = '上次解析未完成，请重新上传重试'
WHERE status = 'PENDING_RETRY';

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'interview_session' AND column_name = 'skill_snapshot_json') = 0,
    'ALTER TABLE interview_session ADD COLUMN skill_snapshot_json LONGTEXT DEFAULT NULL',
    'DO 0');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
