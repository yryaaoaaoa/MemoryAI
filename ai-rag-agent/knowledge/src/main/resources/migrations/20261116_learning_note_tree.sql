-- Additive and repeatable; old Markdown notes and source snapshots remain unchanged.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema=DATABASE() AND table_name='learning_note' AND column_name='tree_json')=0,
    'ALTER TABLE learning_note ADD COLUMN tree_json JSON DEFAULT NULL COMMENT ''可编辑知识树；NULL 为旧版 Markdown 笔记''', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
