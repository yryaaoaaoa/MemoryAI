# 13 项审查问题修复与验证

日期：2026-10-22。范围是本次审查列出的 13 项代码问题；没有连接或修改现有应用数据库、Elasticsearch，也没有调用真实模型。原有 `src/api/sse.ts` 改动和 `docs/项目介绍.md` 保留。

## 修复对照

| 编号 | 问题 | 修复 | 验证依据 |
| --- | --- | --- | --- |
| 1 | 普通用户访问 ES 管理和全局设置 | 安全过滤链查询数据库当前角色；ES 服务和配置写入另有管理员检查，前端隐藏普通用户不可用菜单 | `AdminEndpointSecurityTest`、`EsAdminAuthorizationTest`；浏览器验证两种角色菜单 |
| 2 | 匿名 OCR 接口读取 URL/服务器文件 | 预览接口仅在 dev profile 注册且仅管理员可用；图片只从本次请求附件解析，不再访问 Markdown 中的 URL 或本地路径 | `MdPostProcessorSecurityTest`、`AdminEndpointSecurityTest` 覆盖 prod 404、匿名 401、普通用户 403、dev 管理员附件预览成功 |
| 3 | 面试跨用户读写 | 会话数据库归属校验覆盖缓存命中、读写、报告、删除；列表及历史题限定用户；简历关联独立校验；异步评估显式验证 owner | `InterviewSessionServiceTest`、`InterviewPersistenceServiceTest`、`InterviewEvaluationServiceTest` |
| 4 | 聊天会话越权 | 服务层先锁定并校验会话，再保存用户消息和读取历史；异步回答落库再次检查归属与会话存活 | `ChatOwnershipTest`、`RagOwnershipTest`（403 时模型服务不被调用） |
| 5 | 聊天关联他人简历 | 关联前核对简历归属；忽略旧的非法关联；取消关联显式写入 SQL NULL | `ChatOwnershipTest` |
| 6 | 聊天 HTML/XSS | Markdown 转换后统一经过 DOMPurify 清洗，覆盖用户输入、模型流和历史消息 | 8 项 Markdown 测试；真实 Chrome 中验证历史、输入和流式内容不执行事件脚本 |
| 7 | 前端 7 处类型错误 | 移除无调用者且依赖不存在 API 的旧 RAG store；修正数字知识库 ID 使用和未使用变量 | `pnpm build`；浏览器验证勾选知识库发送数字 ID 并进入答题 |
| 8 | 动态面试缓存失效丢进度 | 新增题目、索引、配额、简历上下文持久化；数据库优先，缓存不一致时重建；完成与待评估状态在同一事务保存 | 面试测试覆盖动态推进、缓存失效恢复、数据库失败不发布新进度、旧缓存敏感信息拒绝 |
| 9 | DDL 与实体不一致 | 更新初始化脚本和可重复执行的 MySQL 8 迁移；补齐实体字段、索引和画像表，保留并隔离未知归属旧记录 | 独立 MySQL 8.0.41 验证新库、10 个实体映射、旧库升级、重复执行、原内容保留、归属回填、画像 upsert |
| 10 | 公共库内容无法检索 | 文档与 ES 新写入元数据使用知识库归属；读取权限以知识库为准；公共库写入/删除仍仅管理员 | `DocumentIsolationTest` 验证新公共文档 owner=0、普通用户读取、普通用户删除被拒绝；旧 ES 同步见迁移说明 |
| 11 | 全局文件去重混淆归属 | 文档按知识库+哈希、简历按用户+哈希去重；软删/失败文档条件恢复；重解析同步哈希、文件元数据并清除旧正文 | `DocumentIsolationTest`、`ResumeIsolationTest`；MySQL 验证跨作用域允许重复、作用域内唯一 |
| 12 | 完成后改答案抹掉评分 | 服务入口与持久化行锁内均验证活动状态；草稿不覆盖评分字段；迟到状态/摘要更新不能重开已完成会话 | `InterviewPersistenceServiceTest`、`InterviewSessionServiceTest` |
| 13 | 分类成绩丢失 | 保存分类成绩 JSON 并在报告中返回；旧报告按已保存的逐题成绩重建 | `InterviewPersistenceServiceTest`、`InterviewSessionServiceTest` |

DOMPurify 使用其官方推荐的 `sanitize` 及 HTML profile，清洗后不再拼接 HTML：[官方说明](https://github.com/cure53/DOMPurify#how-do-i-use-it)。

## 实际执行结果

- 后端目录运行 `mvn -o clean verify -q`：退出 0，52 个 JUnit/Mockito/MockMvc 测试，0 失败、0 错误，生成 Spring Boot JAR。先清理再编译，未复用旧 class 文件。
- 前端目录运行 `pnpm build`：类型检查及 Vite 生产构建成功。保留已有的包体积和动态导入提示，非构建错误。
- 前端目录运行 `pnpm test`：8 项通过，覆盖 Markdown 可读性、多种可执行 HTML、流式半截 HTML。
- 前端目录运行 `pnpm test:browser`：5 项通过，使用真实 Chrome、独立 Vite 测试端口及模拟 API 响应，不连接真实后端。覆盖角色菜单、XSS、跨账号会话存储和知识库选题。
- `python ai-rag-agent/tests/verify_database_schema.py`：独立临时 MySQL 验证退出 0。
- `git diff --check`：通过。

后端测试使用模拟外部 I/O；数据库验证独立运行真实 MySQL。以上不是对现有部署执行的全链路验收，也不是对项目所有代码的无缺陷保证。

## 运行与升级

已有部署需要在使用新代码前按 [数据库迁移说明](database-migration.md) 执行 SQL 迁移及旧 ES 文档归属同步。不能推断归属的旧记录保留隔离，不能自动变为公共数据。已经从 Redis 过期且从未持久化的历史动态题目无法由补丁恢复。

OCR 开发预览需要启用 dev profile，并使用管理员登录后的会话；Markdown 中 `![scan](scan.png)` 对应随请求上传的图片：

```powershell
curl.exe -b cookies.txt -F file=@notes.md -F images=@scan.png http://localhost:8080/api/test/ocr-preview
```

浏览器测试需要安装 Chrome（Playwright 配置的 channel 为 chrome）。普通开发和构建仍使用 pnpm。

## 测试环境清理记录

首次隔离 MySQL 验证留下 `C:\Users\17363\AppData\Local\Temp\memoryai-schema-test-u0vz1dh6`。对该已核对路径执行的清理被自动审批机制拒绝，工具仅返回 `blocked by policy`，未提供更具体原因；未改用其他方式绕过。该临时实例已通过 SQL SHUTDOWN 关闭，端口 55740 无监听。后续验证正常关闭并清理了自己的临时目录。遗留目录仅包含本次测试数据，不是现有应用数据库。
