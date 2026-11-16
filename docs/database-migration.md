# 数据库初始化与升级（MySQL 8.0）

本次代码修复同时更新了数据库契约。新环境使用 `ai-rag-agent/knowledge/src/main/resources/schema.sql`；已有环境使用同目录下 `migrations/20261022_schema_repair.sql`。仅重启应用或再次执行 `CREATE TABLE IF NOT EXISTS` 不会给已有表补列。

## 执行方式

先停止应用写入，备份 MySQL 数据库和 Elasticsearch `knowledge_chunk` 索引。在 MySQL 客户端中明确选择要升级的数据库，执行下列命令（路径可用绝对路径）：

```sql
USE your_database;
SOURCE C:/workspace/MemoryAI/ai-rag-agent/knowledge/src/main/resources/migrations/20261022_schema_repair.sql;
```

新建空数据库则执行 `schema.sql`。不要给客户端添加 `--force`；任何失败都应停止，检查后再运行。脚本需要建表、改表、创建和删除存储过程的权限。MySQL DDL 会隐式提交，整个升级不是一个可回滚事务；因此必须保留升级前备份。脚本允许在停写状态下重复执行，已存在的字段和索引不会重复添加；不要并发执行多个副本。

升级覆盖用户角色和逻辑删除字段、知识库及文档归属、简历结构化内容、各实体审计字段、面试归属和完整状态快照，以及 `user_skill_profile` 表。不会创建管理员账号或提升已有账号权限；缺少角色的旧账号默认是 `user`，已有角色保持原值。

文件唯一约束改为 `document(kb_id, file_hash)` 和 `resume(user_id, file_hash)`。脚本先添加新约束，再移除只含 `file_hash` 的全局唯一索引（包括曾被改名的索引）。若历史库已经存在同一作用域内的重复文件，新约束会报错；脚本不会删除或合并记录。需根据业务记录人工确认重复项后重新执行。

## 旧记录归属

- 已有面试的 `user_id` 为 NULL 或 0 时，仅从关联简历的正数 `user_id` 回填；已有明确归属不覆盖。无简历或简历归属未知的会话保留原数据，不对普通用户开放。升级末尾会报告待确认数量。
- 老版知识库没有归属列时，新增列默认为 NULL。不能推断为公共知识库，也不能将全部数据分配给当前管理员。根据可信备份或业务记录确认归属后，才可设置用户 ID；确实公开的库才设为 0。
- 文档归属同步为所属知识库归属；公共库文档变成 0，未知归属库文档保持 NULL。上传者保留在审计字段，不作为访问权限依据。
- 未知归属的历史聊天、简历同样不能通过猜测 ID 分配给其他账号。原有全局去重曾使第二位上传者复用第一位上传者记录，这种历史关系无法由文件哈希自动恢复；应让第二位上传者重新上传。

升级后可检查：

```sql
SELECT id, name, user_id FROM knowledge_base WHERE user_id IS NULL;
SELECT session_id, resume_id, user_id FROM interview_session WHERE user_id IS NULL OR user_id=0;
SELECT d.id, d.kb_id, d.user_id, k.user_id AS kb_owner
FROM document d JOIN knowledge_base k ON k.id=d.kb_id
WHERE NOT (d.user_id <=> k.user_id);
```

新增的面试题目分配、简历文本快照和分类评分列初始为 NULL。已经从 Redis 过期而且从未写入 MySQL 的历史题目、配额、评分明细不能凭数据库迁移找回；有可信 Redis 备份时可另行核对恢复。新创建和继续执行的面试由修复后的代码持久化。

## Elasticsearch 归属同步

SQL 迁移不会修改 Elasticsearch。已有公共文档的 ES `user_id` 可能仍为原上传管理员，必须同步后普通用户才能检索；未知归属库的旧 ES 数据也必须隔离。发布时保持停写，在已备份的 `knowledge_chunk` 索引中，按 MySQL 确认的每个 `kb_id` 同步 `user_id`。先从 MySQL 导出 `SELECT id,user_id FROM knowledge_base`，不要从文档上传者反推公共权限。

例如确认知识库 123 为公共库后，在已认证的 ES 管理控制台执行以下请求；只把示例 ID 替换成已核实的 ID：

```http
POST knowledge_chunk/_update_by_query?refresh=true
{
  "query": {"term": {"kb_id": "123"}},
  "script": {
    "lang": "painless",
    "source": "ctx._source.user_id = params.owner",
    "params": {"owner": "0"}
  }
}
```

私有库使用对应的用户 ID 字符串；未知归属库使用 `"-1"` 隔离，不能设成 `"0"`。同步前还应逐条核对有效知识库、有效文档及其关联：已删除或不存在的知识库/文档、文档与切片的知识库不一致，以及私有库所有者账号已失效的切片，也应隔离。上面的按知识库更新示例只适用于关联已核实的切片。

请求应无 `failures`、无版本冲突，检查命中数与实际切片数量；有失败时保持停写并排查。再以两个普通账号验证：公共库都可检索、私有库仅所有者可检索、未知归属库不可检索。若索引映射或切片数据也不一致，应在隔离环境重建并核对后再切换，不要直接删除当前索引。

## 自动验证

```powershell
python ai-rag-agent/tests/verify_database_schema.py
```

需要 `mysql` 和 `mysqld` 在 PATH 中，以及 Python 包 `PyMySQL`、`redis`、`PyYAML`。脚本自行创建临时数据目录，使用随机端口、仅绑定 127.0.0.1 的隔离 MySQL 实例；不读取应用数据源配置，也不连接现有数据库。验证空库初始化、全部 10 个实体字段、旧库升级、重复升级、已有内容保留、归属回填和隔离、作用域唯一约束及画像 upsert。结束时关闭临时服务器并清理目录。

上述隔离验证已在本机 MySQL 8.0.41 跑通。2026-10-22 已另行完成本机实际应用数据库和 Elasticsearch 的备份、迁移与启动验证，详见 [实际迁移记录](migration-execution-2026-10-22.md)。其他环境仍需各自备份、迁移和核对，不能复用本机的验证结论。

## 2026-11-12：错题本持久化

新版错题本从 MySQL 中每题的最新答题记录查询。`quiz_record.wrong_removed` 保存手动移除状态；答题历史和掌握度不因移除而改变，再次答错会产生新的未移除记录。新建库的 `schema.sql` 已包含该字段；已有库不能只重启服务。

停止后端写入后，运行迁移（需要 `PyMySQL`、`redis`、`PyYAML`）：

```powershell
python ai-rag-agent/knowledge/src/main/resources/migrations/20261112_quiz_wrong_state.py --config ai-rag-agent/bootstrap/src/main/resources/application.yml --backup-dir C:/path/to/quiz-migration-backup
```

脚本先备份答题记录及旧 Redis 错题集合，再补列并保留当前错题本的移除状态。Redis 无法读取时停止，不会把读取失败当成空集合。备份包含答题内容，应保留在本机受控目录，不提交到 Git。失败后使用同一备份目录重跑；完成后重复执行不会覆盖后续答题状态。新后端不再依赖 Redis 保存错题本成员关系，旧缓存无需清空。

旧 Redis 已经丢失的手动移除意图无法从答题历史还原；本次迁移保留迁移时实际可见的成员关系，不猜测此前状态。2026-11-12 本机已完成该迁移并验证两账号隔离、移除后再次答错、答对移出及事务回滚。隔离数据库验证脚本也覆盖该升级、重复执行和掌握度 SQL。
