# 本机数据库 / ES 迁移与启动验证

执行时间：2026-10-22 16:40–16:52（Asia/Shanghai）。目标是本机当前配置指向的 MySQL `jobai` 和 ES `knowledge_chunk`，不是隔离测试库。执行迁移时应用未运行。

## 备份

目录：`C:\Users\17363\AppData\Local\MemoryAI\backups\20261022-164042`。

- `mysql-before.sql`：完整数据库结构、记录、例程、触发器及事件导出，439,598 字节。
- `es-index-before.json`：索引映射、设置及别名。
- `es-documents-before.jsonl`：404 条原始文档，包含全部正文、元数据和每条 1,024 维向量，5,981,528 字节。
- `backup-manifest.json`：文件大小和 SHA-256；`before-counts.json`：迁移前各表记录数。

ES 未配置 snapshot repository，因此此次备份是完整逻辑导出，不是 ES 原生快照。备份保留在源码仓库之外。

## 迁移结果

MySQL 以批处理标准输入执行 `20261022_schema_repair.sql`，未使用 `--force`，退出码为 0。迁移前检查未发现同作用域重复文件、无效聊天角色、孤儿文档或孤儿切片。

- `interview_session` 的 `allocations_json`、`resume_text`、`category_scores_json` 已存在且为 LONGTEXT。
- 文档唯一约束为 `(kb_id, file_hash)`；简历唯一约束为 `(user_id, file_hash)`；旧的单列哈希唯一约束已移除。
- 10 个 Java 数据实体的全部映射字段在真实数据库中查询验证通过。
- 知识库和面试的待确认归属数均为 0，文档与知识库的归属不一致数为 0。
- 原有 13 张表的记录数保持不变。

ES 按有效知识库、有效文档、两者关联和有效所有者账号逐条核对，再通过 bulk update 将 `user_id` 规范为字符串。404 条更新成功，没有错误或版本冲突，无需隔离的遗留切片。逐条重新读取后，正文、向量及其他字段与备份一致。业务索引为 green；集群 yellow 来自另一 `articles` 索引在单节点上的未分配副本，本次未修改该索引或副本设置。

## 真实运行验证

后端使用 Java 17.0.12 启动 `bootstrap-1.0.0.jar`，默认 profile，约 10 秒完成启动。前端直接运行 Vite，未执行会清理其他端口的 `predev`。

- 前端：<http://127.0.0.1:5173/>
- 后端：<http://127.0.0.1:8080/>
- 本次后端 PID：41048；前端监听进程 PID：27796。PID 仅代表本次启动，重启后会变化。
- 标准输出、错误输出及进程信息保存在备份目录的 `backend.*.log`、`frontend.*.log` 和 `processes.json`。

70 次真实 API 检查通过，覆盖注册、登录、Redis 会话、功能列表、知识库隔离、聊天读取/写入隔离、公共库权限和管理员撤权即时生效。普通账号提交 `system=true` 时，服务按既有设计创建私有库，已检查数据库归属，未产生公共库。

相同 Markdown 文件分别上传到两个私有库和一个公共库，三份均完成解析、切片、实际 embedding 调用、ES 写入和 READY 状态更新。检查了跨库去重、普通账号读取公共文档、禁止读取他人私有文档、禁止修改公共文档，以及三份 ES 记录的归属。

真实 Chrome（未 mock API）通过普通账号的 AI 问答、刷题、知识库、模拟面试、简历解析、用户画像 6 页，以及管理员 ES 管理、检索配置 2 页。没有 JavaScript 异常或意外接口错误。未登录时的 `/api/auth/me` 401 是预期行为。

页面检查发现并修复了 Element Plus 表格条纹行的深色主题白底浅字问题：补齐 `--el-fill-color-lighter` 主题变量。深浅两种主题重新截图核对通过，前端 `pnpm build` 通过。构建仍有既有的大包体积和静态/动态导入提示。

### 尚未通过的外部服务环节

实际 AI SSE 请求已执行：检索命中 1 条切片，但当前配置的 DeepSeek 服务返回 HTTP 402 `Insufficient Balance`。查询改写、相关性判断回退到现有兜底路径，最终回答生成失败。因此不能将 AI 回答、出题、面试生成/评分的完整业务链路标为通过。需要为当前账户补充余额，或由用户选择有效凭据后再验证。本次没有充值或更换模型/密钥。

后续状态：用户更换 Key 后，同日已重启后端并通过真实 AI 对话、出题、批量面试评分，以及动态面试推进和缓存恢复验证。上面的 402 是首次迁移验证时的历史结果，最新结果见 [新 Key 业务验证记录](ai-live-verification-2026-10-22.md)。

## 测试数据清理与最终核对

删除了仅供本次验证的 3 个账户、4 个知识库、3 个文档及其切片和测试聊天；注销了 7 个残留测试 Redis 会话，清除了临时账户凭据文件中的密码。保留全部原有业务数据。测试使用过的自增 ID 不回退。

| 表 | 清理后记录数（与迁移前相同） |
|---|---:|
| user | 6 |
| knowledge_base | 7 |
| document | 11 |
| chunk | 404 |
| resume | 1 |
| chat_session | 4 |
| chat_message | 14 |
| quiz_question | 11 |
| quiz_record | 11 |
| user_mastery | 1 |
| user_skill_profile | 0 |
| interview_session | 0 |
| interview_answer | 0 |

ES 恢复为原有 404 条，逐条确认仅预期的归属字段规范化发生变化，其余内容及向量均保留。前后端继续在后台运行。

证据位于备份目录：`mysql-migration.log`、`migration-summary.json`、`schema-contract-results.json`、`api-smoke-results.json`、`browser-smoke-results.json`、`es-theme-check-results.json`、`cleanup-results.json` 和页面截图。`live-chat.sse` 记录上述 402 失败。
