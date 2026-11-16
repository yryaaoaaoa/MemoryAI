-- Apply after 20261113_learning_notes.sql. Idempotent; existing notes start at version 0.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='learning_note' AND column_name='version')=0,
    'ALTER TABLE learning_note ADD COLUMN version BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='learning_note' AND column_name='create_request_id')=0,
    'ALTER TABLE learning_note ADD COLUMN create_request_id VARCHAR(36) DEFAULT NULL', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='learning_note' AND column_name='create_request_hash')=0,
    'ALTER TABLE learning_note ADD COLUMN create_request_hash CHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema=DATABASE() AND table_name='learning_note' AND index_name='uk_note_create_request')=0,
    'ALTER TABLE learning_note ADD UNIQUE KEY uk_note_create_request (user_id,create_request_id)', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
