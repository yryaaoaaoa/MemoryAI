-- MySQL 8.0. Back up the target database before running. Idempotent, no data deletion.
CREATE TABLE IF NOT EXISTS learning_note (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    confusion TEXT NOT NULL,
    content MEDIUMTEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    source_session_id BIGINT NOT NULL,
    source_message_ids JSON NOT NULL,
    source_snapshot JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_note_owner_status (user_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '学习笔记及原始问答快照';

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'chat_session' AND column_name = 'note_id') = 0,
    'ALTER TABLE chat_session ADD COLUMN note_id BIGINT DEFAULT NULL COMMENT ''关联学习笔记ID''',
    'SELECT 1'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
