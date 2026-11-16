-- MySQL 8.0. Stop application writes and back up before running.
-- Select the intended database explicitly. Never run with --force.
-- Idempotent, does not delete application records. See docs/database-migration.md.
-- 知识库
CREATE TABLE IF NOT EXISTS knowledge_base (
    INDEX idx_kb_owner (user_id, is_delete, created_at),
    user_id BIGINT DEFAULT NULL COMMENT '0=公共，NULL=旧记录归属待确认',
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    deleted_by BIGINT DEFAULT NULL,
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL COMMENT '知识库名称',
    description VARCHAR(500)  DEFAULT '' COMMENT '描述',
    priority    VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT '出题优先级: CORE/NORMAL',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete   TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '知识库';

-- 文档
CREATE TABLE IF NOT EXISTS document (
    INDEX idx_document_owner (user_id, is_delete),
    user_id BIGINT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    deleted_by BIGINT DEFAULT NULL,
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    kb_id         BIGINT       NOT NULL COMMENT '所属知识库',
    file_name     VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type     VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '文件类型',
    file_size     BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    file_hash     VARCHAR(64)  NOT NULL COMMENT 'SHA-256 文件哈希，去重用',
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING' COMMENT '状态',
    chunk_count   INT          NOT NULL DEFAULT 0 COMMENT '切片数',
    error_message VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_delete     TINYINT      NOT NULL DEFAULT 0,
    UNIQUE KEY uk_document_kb_hash (kb_id, file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '文档';

-- 简历（不进入 ES，Agent 直接读 MySQL）
CREATE TABLE IF NOT EXISTS resume (
    structured_json LONGTEXT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    deleted_by BIGINT DEFAULT NULL,
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL DEFAULT 0 COMMENT '用户ID',
    file_name       VARCHAR(255) NOT NULL COMMENT '文件名',
    file_hash       VARCHAR(64)  NOT NULL COMMENT 'SHA-256 文件哈希，去重用',
    file_size       BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    raw_text        MEDIUMTEXT   COMMENT 'PDFBox 提取的纯文本',
    status          VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING' COMMENT '状态',
    error_msg       VARCHAR(500) DEFAULT NULL COMMENT '失败信息',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_resume_user_hash (user_id, file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '简历';

-- 切片
CREATE TABLE IF NOT EXISTS chunk (
    INDEX idx_chunk_doc (doc_id),
    INDEX idx_chunk_kb (kb_id),
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    doc_id       BIGINT       NOT NULL COMMENT '所属文档',
    kb_id        BIGINT       NOT NULL COMMENT '所属知识库',
    content      TEXT         NOT NULL COMMENT '文本内容',
    heading_path VARCHAR(500) DEFAULT '' COMMENT '标题路径',
    chunk_index  INT          NOT NULL DEFAULT 0 COMMENT '切片序号',
    token_count  INT          NOT NULL DEFAULT 0 COMMENT 'token 数',
    vector_id    VARCHAR(100) DEFAULT NULL COMMENT 'ES document ID',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '文档切片';

-- 聊天会话
CREATE TABLE IF NOT EXISTS chat_session (
    INDEX idx_chat_owner (user_id, created_at),
    id                    BIGINT       AUTO_INCREMENT PRIMARY KEY,
    title                 VARCHAR(200) NOT NULL DEFAULT '' COMMENT '会话标题',
    kb_ids                VARCHAR(500) NOT NULL DEFAULT '' COMMENT '关联知识库 ID，逗号分隔',
    user_id               BIGINT       DEFAULT 0 COMMENT '用户ID',
    total_prompt_tokens   INT          DEFAULT 0 COMMENT '累计 prompt tokens',
    total_completion_tokens INT        DEFAULT 0 COMMENT '累计 completion tokens',
    summary               TEXT         DEFAULT NULL COMMENT '历史对话摘要',
    resume_id             BIGINT       DEFAULT NULL COMMENT '关联简历ID',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '聊天会话';

-- 聊天消息
CREATE TABLE IF NOT EXISTS chat_message (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id   BIGINT       NOT NULL COMMENT '会话 ID',
    role         VARCHAR(20)  NOT NULL COMMENT 'user/assistant/tool',
    content      TEXT         NOT NULL COMMENT '消息内容',
    message_order INT         NOT NULL DEFAULT 0 COMMENT '消息序号',
    metadata     JSON         DEFAULT NULL COMMENT '结构化元数据: tool_calls/tool_call_id/reasoning_content',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_order (session_id, message_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '聊天消息';

-- 题目
CREATE TABLE IF NOT EXISTS quiz_question (
    INDEX idx_question_kb (kb_id),
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    kb_id           BIGINT       DEFAULT 0 COMMENT '关联知识库',
    doc_id          BIGINT       DEFAULT 0 COMMENT '关联文档',
    topic           VARCHAR(100) NOT NULL DEFAULT '' COMMENT '知识点标签',
    question_text   TEXT         NOT NULL COMMENT '题目内容',
    question_type   VARCHAR(20)  NOT NULL DEFAULT 'choice' COMMENT 'choice/blank/essay',
    options_json    JSON         DEFAULT NULL COMMENT '选项',
    answer          VARCHAR(500) NOT NULL DEFAULT '' COMMENT '正确答案',
    explanation     TEXT         DEFAULT NULL COMMENT '解析',
    source_chunk_id BIGINT       DEFAULT 0 COMMENT '来源切片',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '题目';

-- 答题记录
CREATE TABLE IF NOT EXISTS quiz_record (
    INDEX idx_record_user_question (user_id, question_id, id),
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL DEFAULT 0,
    question_id     BIGINT       NOT NULL,
    user_answer     TEXT         NOT NULL COMMENT '用户答案',
    is_correct      TINYINT      NOT NULL DEFAULT 0,
    score           INT          NOT NULL DEFAULT 0,
    duration_sec    INT          NOT NULL DEFAULT 0,
    mastery_delta   INT          NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_topic (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '答题记录';

-- 用户掌握度
CREATE TABLE IF NOT EXISTS user_mastery (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    topic           VARCHAR(100) NOT NULL COMMENT '知识点',
    mastery         INT          NOT NULL DEFAULT 0,
    total_attempts  INT          NOT NULL DEFAULT 0,
    correct_attempts INT         NOT NULL DEFAULT 0,
    streak          INT          NOT NULL DEFAULT 0,
    next_review_at  DATETIME     DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_topic (user_id, topic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '用户掌握度';

-- ============================================================
-- User table (auth system)
-- ============================================================
CREATE TABLE IF NOT EXISTS `user` (
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    deleted_by BIGINT DEFAULT NULL,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名',
    password_hash   VARCHAR(255) NOT NULL COMMENT 'bcrypt 哈希',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '用户表';

-- ============================================================
-- Interview session (模拟面试)
-- ============================================================
CREATE TABLE IF NOT EXISTS interview_session (
    INDEX idx_interview_owner (user_id, created_at),
    user_id BIGINT DEFAULT NULL,
    created_by BIGINT DEFAULT NULL,
    updated_by BIGINT DEFAULT NULL,
    deleted_at DATETIME DEFAULT NULL,
    deleted_by BIGINT DEFAULT NULL,
    allocations_json LONGTEXT DEFAULT NULL,
    resume_text LONGTEXT DEFAULT NULL,
    category_scores_json LONGTEXT DEFAULT NULL,
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id       VARCHAR(36)  NOT NULL UNIQUE COMMENT '会话ID (short UUID)',
    skill_id         VARCHAR(64)  DEFAULT 'java-backend' COMMENT '面试方向',
    difficulty       VARCHAR(16)  DEFAULT 'mid' COMMENT '难度: junior/mid/senior',
    resume_id        BIGINT       DEFAULT NULL COMMENT '关联简历ID',
    total_questions  INT          NOT NULL DEFAULT 0 COMMENT '题目总数',
    current_question_index INT     NOT NULL DEFAULT 0 COMMENT '当前题目索引',
    status           VARCHAR(20)  NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/IN_PROGRESS/COMPLETED/EVALUATED',
    questions_json   TEXT         COMMENT '题目列表 JSON',
    overall_score    INT          DEFAULT NULL COMMENT '总分 0-100',
    overall_feedback TEXT         DEFAULT NULL COMMENT '总体评价',
    strengths_json   TEXT         COMMENT '优势 JSON array',
    improvements_json TEXT        COMMENT '改进建议 JSON array',
    reference_answers_json TEXT   COMMENT '参考答案 JSON',
    llm_provider     VARCHAR(50)  DEFAULT 'deepseek',
    evaluate_status  VARCHAR(20)  DEFAULT NULL COMMENT '评估状态: PENDING/PROCESSING/COMPLETED/FAILED',
    evaluate_error   VARCHAR(500) DEFAULT NULL COMMENT '评估失败原因',
    context_summary  TEXT         DEFAULT NULL COMMENT '上下文摘要（L2），面试过程中累积的早期轮次压缩摘要',
    mode             VARCHAR(10)  NOT NULL DEFAULT 'batch' COMMENT '面试模式: batch(一次性出题)/dynamic(动态自适应)',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at     DATETIME     DEFAULT NULL,
    INDEX idx_interview_resume (resume_id, created_at),
    INDEX idx_interview_skill (skill_id, created_at),
    INDEX idx_interview_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '模拟面试会话';

CREATE TABLE IF NOT EXISTS interview_answer (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    session_id       VARCHAR(36)  NOT NULL COMMENT '面试会话ID',
    question_index   INT          NOT NULL COMMENT '题目序号 0-based',
    question         TEXT         NOT NULL COMMENT '题目内容',
    category         VARCHAR(64)  DEFAULT NULL COMMENT '分类标签',
    user_answer      TEXT         DEFAULT NULL COMMENT '用户答案',
    score            INT          DEFAULT 0 COMMENT 'AI评分 0-100',
    feedback         TEXT         DEFAULT NULL COMMENT 'AI评估反馈',
    reference_answer TEXT         DEFAULT NULL COMMENT '参考答案',
    key_points_json  TEXT         DEFAULT NULL COMMENT '关键点 JSON array',
    answered_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_answer_session (session_id),
    UNIQUE KEY uk_session_question (session_id, question_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '面试答案';

-- 每个用户、技术方向一份画像；唯一键供 ON DUPLICATE KEY UPDATE 使用。
CREATE TABLE IF NOT EXISTS user_skill_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    profile_json LONGTEXT NOT NULL,
    interview_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_profile_user_skill (user_id, skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '用户技能画像';

DELIMITER $$
DROP PROCEDURE IF EXISTS memoryai_add_column$$
CREATE PROCEDURE memoryai_add_column(IN tbl VARCHAR(64), IN col VARCHAR(64), IN definition TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tbl AND column_name=col) THEN
        SET @ddl=CONCAT('ALTER TABLE `',tbl,'` ADD COLUMN `',col,'` ',definition);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DROP PROCEDURE IF EXISTS memoryai_add_index$$
CREATE PROCEDURE memoryai_add_index(IN tbl VARCHAR(64), IN idx VARCHAR(64), IN definition TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=tbl AND index_name=idx) THEN
        SET @ddl=CONCAT('ALTER TABLE `',tbl,'` ADD ',definition);
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
-- Remove obsolete global hash uniqueness even if its index was renamed.
DROP PROCEDURE IF EXISTS memoryai_drop_global_hash_unique$$
CREATE PROCEDURE memoryai_drop_global_hash_unique(IN tbl VARCHAR(64))
BEGIN
    DECLARE old_idx VARCHAR(64);
    DECLARE done INT DEFAULT 0;
    DECLARE indexes CURSOR FOR SELECT index_name FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=tbl AND non_unique=0 AND index_name <> 'PRIMARY'
        GROUP BY index_name HAVING COUNT(*)=1 AND MAX(column_name)='file_hash';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done=1;
    OPEN indexes;
    drop_loop: LOOP
        FETCH indexes INTO old_idx;
        IF done=1 THEN LEAVE drop_loop; END IF;
        SET @ddl=CONCAT('ALTER TABLE `',tbl,'` DROP INDEX `',old_idx,'`');
        PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END LOOP;
    CLOSE indexes;
END$$
DELIMITER ;
CALL memoryai_add_column('knowledge_base', 'user_id', 'BIGINT DEFAULT NULL COMMENT ''0=公共，NULL=旧记录归属待确认''');
CALL memoryai_add_column('knowledge_base', 'created_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('knowledge_base', 'updated_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('knowledge_base', 'deleted_at', 'DATETIME DEFAULT NULL');
CALL memoryai_add_column('knowledge_base', 'deleted_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('document', 'user_id', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('document', 'created_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('document', 'updated_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('document', 'deleted_at', 'DATETIME DEFAULT NULL');
CALL memoryai_add_column('document', 'deleted_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('resume', 'structured_json', 'LONGTEXT DEFAULT NULL');
CALL memoryai_add_column('resume', 'created_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('resume', 'updated_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('resume', 'deleted_at', 'DATETIME DEFAULT NULL');
CALL memoryai_add_column('resume', 'deleted_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('user', 'role', 'VARCHAR(20) NOT NULL DEFAULT ''user''');
CALL memoryai_add_column('user', 'updated_at', 'DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
CALL memoryai_add_column('user', 'deleted_at', 'DATETIME DEFAULT NULL');
CALL memoryai_add_column('user', 'deleted_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('user', 'is_deleted', 'TINYINT NOT NULL DEFAULT 0');
CALL memoryai_add_column('interview_session', 'user_id', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'created_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'updated_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'deleted_at', 'DATETIME DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'deleted_by', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'allocations_json', 'LONGTEXT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'resume_text', 'LONGTEXT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'category_scores_json', 'LONGTEXT DEFAULT NULL');
CALL memoryai_add_column('chat_message', 'metadata', 'JSON DEFAULT NULL');
CALL memoryai_add_column('chat_session', 'summary', 'TEXT DEFAULT NULL');
CALL memoryai_add_column('chat_session', 'resume_id', 'BIGINT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'context_summary', 'TEXT DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'mode', 'VARCHAR(10) NOT NULL DEFAULT ''batch''');
CALL memoryai_add_column('interview_session', 'evaluate_status', 'VARCHAR(20) DEFAULT NULL');
CALL memoryai_add_column('interview_session', 'evaluate_error', 'VARCHAR(500) DEFAULT NULL');
ALTER TABLE chat_message MODIFY COLUMN role VARCHAR(20) NOT NULL COMMENT 'user/assistant/tool';
CALL memoryai_add_index('knowledge_base', 'idx_kb_owner', 'INDEX idx_kb_owner (user_id, is_delete, created_at)');
CALL memoryai_add_index('document', 'idx_document_owner', 'INDEX idx_document_owner (user_id, is_delete)');
CALL memoryai_add_index('chunk', 'idx_chunk_doc', 'INDEX idx_chunk_doc (doc_id)');
CALL memoryai_add_index('chunk', 'idx_chunk_kb', 'INDEX idx_chunk_kb (kb_id)');
CALL memoryai_add_index('chat_session', 'idx_chat_owner', 'INDEX idx_chat_owner (user_id, created_at)');
CALL memoryai_add_index('quiz_question', 'idx_question_kb', 'INDEX idx_question_kb (kb_id)');
CALL memoryai_add_index('quiz_record', 'idx_record_user_question', 'INDEX idx_record_user_question (user_id, question_id, id)');
CALL memoryai_add_index('interview_session', 'idx_interview_owner', 'INDEX idx_interview_owner (user_id, created_at)');
-- Add the scoped unique indexes FIRST: if inconsistent duplicates exist, fail without dropping old uniqueness.
CALL memoryai_add_index('document', 'uk_document_kb_hash', 'UNIQUE KEY uk_document_kb_hash (kb_id, file_hash)');
CALL memoryai_add_index('resume', 'uk_resume_user_hash', 'UNIQUE KEY uk_resume_user_hash (user_id, file_hash)');
CALL memoryai_drop_global_hash_unique('document');
CALL memoryai_drop_global_hash_unique('resume');
-- Never infer ownership from an administrator/uploader when the KB has no known owner.
UPDATE document d JOIN knowledge_base k ON k.id=d.kb_id
SET d.user_id=k.user_id WHERE NOT (d.user_id <=> k.user_id);
-- 0/NULL denotes legacy owner unknown for interviews, never a public interview.
UPDATE interview_session s JOIN resume r ON r.id=s.resume_id
SET s.user_id=r.user_id WHERE (s.user_id IS NULL OR s.user_id=0) AND r.user_id>0;
DROP PROCEDURE memoryai_add_column;
DROP PROCEDURE memoryai_add_index;
DROP PROCEDURE memoryai_drop_global_hash_unique;
-- Review these quarantined rows before manually assigning a verified owner.
SELECT COUNT(*) AS unresolved_interview_owners FROM interview_session WHERE user_id IS NULL OR user_id=0;
SELECT COUNT(*) AS unresolved_kb_owners FROM knowledge_base WHERE user_id IS NULL;
