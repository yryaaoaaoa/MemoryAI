-- Apply after 20261113_learning_notes.sql. Old notes retain NULL (unknown positions).
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='learning_note' AND column_name='source_ranges')=0,
    'ALTER TABLE learning_note ADD COLUMN source_ranges JSON DEFAULT NULL', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
